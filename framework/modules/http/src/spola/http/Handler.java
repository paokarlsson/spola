package spola.http;

/**
 * Answers a request. The server calls one handler concurrently from many connections (each on
 * its own virtual thread), so a handler must be thread-safe; blocking in it is fine.
 *
 * <pre>{@code
 * Handler handler = request -> Response.text(200, "hello " + request.path());
 * }</pre>
 *
 * <p>What a handler throws becomes a 500 response without the exception's message or stack
 * trace (the exception is logged, escaped), unless the request body failed (too large, broken
 * framing, timeout), which gives that status. Either way the connection is closed afterwards.
 */
@FunctionalInterface
public interface Handler {

    /**
     * Answers {@code request}.
     *
     * @param request the request; its body may be read on this thread until this method returns
     *                (or, for a streaming response, until its {@link BodyWriter} returns)
     * @return the response, not {@code null}
     * @throws Exception anything; the server answers 500 (or the body's error status)
     */
    Response handle(Request request) throws Exception;
}
