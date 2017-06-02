package org.http4k.routing

import org.http4k.core.Body
import org.http4k.core.ContentType
import org.http4k.core.ContentType.Companion.OCTET_STREAM
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.Status.Companion.OK
import org.http4k.core.UriTemplate
import org.http4k.core.findSingle
import org.http4k.core.then
import java.nio.ByteBuffer
import javax.activation.MimetypesFileTypeMap

class StaticRouter constructor(private val httpHandler: StaticRouter.Companion.Handler) : RoutingHttpHandler {
    override fun withFilter(filter: Filter): RoutingHttpHandler = StaticRouter(httpHandler.copy(filter = httpHandler.filter.then(filter)))

    override fun withBasePath(basePath: String): RoutingHttpHandler = StaticRouter(httpHandler.copy(basePath = basePath + httpHandler.basePath))

    override fun match(request: Request): HttpHandler? = invoke(request).let { if (it.status != NOT_FOUND) { _: Request -> it } else null }

    override fun invoke(req: Request): Response = httpHandler(req)

    companion object {
        data class Handler(val basePath: String,
                           val resourceLoader: ResourceLoader,
                           val extraPairs: Map<String, ContentType>,
                           val filter: Filter = Filter { next -> { next(it) } }
        ) : HttpHandler {
            private val extMap = MimetypesFileTypeMap(ContentType::class.java.getResourceAsStream("/META-INF/mime.types"))

            init {
                extMap.addMimeTypes(extraPairs
                    .map { (first, second) -> second.value + "\t\t\t" + first }.joinToString("\n")
                )
            }

            private val handler = filter.then {
                req ->
                val path = convertPath(req.uri.path)
                resourceLoader.load(path)?.let {
                    url ->
                    val lookupType = ContentType(extMap.getContentType(path))
                    if (req.method == GET && lookupType != OCTET_STREAM) {
                        Response(OK)
                            .header("Content-Type", lookupType.value)
                            .body(Body(ByteBuffer.wrap(url.openStream().readBytes())))
                    } else Response(NOT_FOUND)
                } ?: Response(NOT_FOUND)

            }

            override fun invoke(req: Request): Response = handler(req)

            private fun convertPath(path: String): String {
                val newPath = if (basePath == "/" || basePath == "") path else path.replace(basePath, "")
                val resolved = if (newPath.isBlank()) "/index.html" else newPath
                return resolved.replaceFirst("/", "")
            }
        }

    }
}

internal class GroupRoutingHttpHandler(private val httpHandler: GroupRoutingHttpHandler.Companion.Handler) : RoutingHttpHandler {
    override fun withFilter(filter: Filter): RoutingHttpHandler = GroupRoutingHttpHandler(httpHandler.copy(filter = httpHandler.filter.then(filter)))

    override fun withBasePath(basePath: String): RoutingHttpHandler = GroupRoutingHttpHandler(
        httpHandler.copy(basePath = UriTemplate.from(basePath + httpHandler.basePath?.toString().orEmpty()),
            routes = httpHandler.routes.map { it.copy(template = UriTemplate.from("$basePath/${it.template}")) }
        )
    )

    override fun invoke(request: Request): Response = httpHandler(request)

    override fun match(request: Request): HttpHandler? = httpHandler.match(request)

    companion object {
        internal data class Handler(internal val basePath: UriTemplate? = null, internal val routes: List<Route>, val filter: Filter = Filter { next -> { next(it) } }) : HttpHandler {
            private val routers = routes.map(Route::asRouter)
            private val noMatch: HttpHandler? = null
            private val handler: HttpHandler = filter.then { match(it)?.invoke(it) ?: Response(NOT_FOUND.description("Route not found")) }

            fun match(request: Request): HttpHandler? =
                if (basePath?.matches(request.uri.path) ?: true)
                    routers.fold(noMatch, { memo, router -> memo ?: router.match(request) })
                else null

            override fun invoke(request: Request): Response = handler(request)
        }
    }
}

private fun Route.asRouter(): Router = object : Router {
    override fun match(request: Request): HttpHandler? =
        if (template.matches(request.uri.path) && method == request.method) {
            { req: Request -> handler(req.withUriTemplate(template)) }
        } else null
}


internal fun Router.then(that: Router): Router {
    val originalMatch = this::match
    return object : Router {
        override fun match(request: Request): HttpHandler? = originalMatch(request) ?: that.match(request)
    }
}

internal fun Router.toHttpHandler(): HttpHandler = {
    match(it)?.invoke(it) ?: Response(Status.NOT_FOUND)
}

private fun Request.withUriTemplate(uriTemplate: UriTemplate): Request = header("x-uri-template", uriTemplate.toString())

internal fun Request.uriTemplate(): UriTemplate = headers.findSingle("x-uri-template")?.let { UriTemplate.from(it) } ?: throw IllegalStateException("x-uri-template header not present in the request")