package org.http4k.core;

import kotlin.jvm.functions.Function1;

import static org.http4k.core.Status.ACCEPTED;


public interface UsageFromJava_core {
    Uri hello = Uri.of("hello");
    Request request = Request.Companion.create(Method.GET, "").body(Body.EMPTY);
    Response response = Response.Companion.create(ACCEPTED).body(Body.create("hello"));
    Function1<Request, Response> httpHandler = req -> response;
    Filter filter = next -> req -> next.invoke(request.header("foo", "bar"));

    //TODO FIXME
//    Function1<Request, Response> decorated = then(filter, then(filter, httpHandler));
//    Response response2 = decorated.invoke(request);
//
//    Function1<Request, Response> app = routes(
//        bind("/nested", routes(
//            bind("/second", Method.GET).to(req -> response2)
//        )),
//        bind("/first", Method.GET).to(req -> response)
//    );
}
