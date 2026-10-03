package spola.web;

import spola.http.Response;

/**
 * Turns an exception into a response, the counterpart of an {@code @ExceptionHandler} method in
 * a {@code @ControllerAdvice}. Register it with {@link RouterBuilder#onException}; the router
 * picks the mapper whose class is closest to the exception's class.
 *
 * <pre>{@code
 * router.onException(OrderNotFound.class, (e, request) ->
 *         Problem.of(404, "No order " + e.id()).withInstance(request.path()).toResponse());
 * }</pre>
 *
 * @param <E> the exception type
 */
@FunctionalInterface
public interface ExceptionMapper<E extends Throwable> {

    /**
     * Maps {@code exception}.
     *
     * @param exception the exception
     * @param request   the request at the step that failed
     * @return the response, not {@code null}
     * @throws Exception anything; the router then answers 500 without calling a mapper again
     */
    Response map(E exception, WebRequest request) throws Exception;
}
