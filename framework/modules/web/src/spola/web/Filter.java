package spola.web;

import spola.http.Response;

/**
 * Code around the handlers, the counterpart of Spring's {@code OncePerRequestFilter} and
 * {@code HandlerInterceptor}. A filter may answer on its own without calling {@code next},
 * pass another request on (one with an attribute added, see {@link WebRequest#withAttribute}),
 * and change the response that comes back:
 *
 * <pre>{@code
 * Filter auth = (request, next) -> {
 *     var user = users.find(request.headers().first("Authorization"));
 *     if (user.isEmpty()) {
 *         return Problem.of(401).toResponse();
 *     }
 *     return next.proceed(request.withAttribute(USER, user.get())).withHeader("Cache-Control", "no-store");
 * };
 * }</pre>
 *
 * <p>The router's filters run for every request, also when the router answers 400 (invalid
 * path), 404, 405 or an automatic {@code OPTIONS}; a group's filters run only for the group's
 * routes, inside the router's filters. Filters run in the order they were registered, the first
 * one outermost. What the next step throws has already been mapped to a response when
 * {@code next.proceed} returns, so a filter sees error responses like any other (and can, for
 * instance, add a header to them); what a filter throws goes to the same exception mappers.
 */
@FunctionalInterface
public interface Filter {

    /**
     * Filters one request.
     *
     * @param request the request
     * @param next    the rest of the chain: the next filter, or the handler
     * @return the response, not {@code null}
     * @throws Exception anything; mapped to a response by the router
     */
    Response filter(WebRequest request, Chain next) throws Exception;

    /** The rest of a filter chain. */
    @FunctionalInterface
    interface Chain {

        /**
         * Runs the rest of the chain with {@code request}. Exceptions from the rest of the chain
         * come back as the response their mapper made; only a
         * {@link spola.http.RequestBodyException} (which the server answers) and an
         * {@link Error} come through as exceptions.
         *
         * @param request the request to pass on
         * @return the response
         * @throws Exception a request body failure or an error, as described above
         */
        Response proceed(WebRequest request) throws Exception;
    }
}
