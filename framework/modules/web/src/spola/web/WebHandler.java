package spola.web;

import spola.http.Response;

/**
 * Answers a request that matched a route. One handler is called concurrently from many
 * connections, so it must be thread-safe; blocking in it is fine (each request has its own
 * virtual thread).
 *
 * <pre>{@code
 * WebHandler show = request -> Response.text(200, "order " + request.pathParam("id", Param.LONG));
 * }</pre>
 *
 * <p>What it throws goes to the router's exception mappers (see
 * {@link RouterBuilder#onException}).
 */
@FunctionalInterface
public interface WebHandler {

    /**
     * Answers {@code request}.
     *
     * @param request the request, with the matched route's path variables
     * @return the response, not {@code null}
     * @throws Exception anything; mapped to a response by the router
     */
    Response handle(WebRequest request) throws Exception;
}
