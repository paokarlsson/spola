package spola.client;

import java.io.Serial;
import java.net.URI;
import java.util.Objects;

/**
 * A call through a {@link RestClient} failed: the counterpart of Spring's
 * {@code RestClientException}. It is sealed with exactly two cases, so a {@code switch} over a
 * caught exception is exhaustive without {@code default}:
 *
 * <pre>{@code
 * try {
 *     return client.get("/items/7").retrieve(ITEM);
 * } catch (RestClientException e) {
 *     String what = switch (e) {
 *         case RestClientResponseException r -> "status " + r.status() + ": " + r.safeExcerpt();
 *         case RestClientTransportException t -> t.timedOut() ? "timed out" : "no response";
 *     };
 *     ...
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@link RestClientResponseException}: a response came but cannot be used (a status
 *       outside 2xx, a body over the limit, an empty body or one that is not JSON or does not fit
 *       the codec). It has {@code status()}, {@code headers()}, {@code safeExcerpt()} (to log)
 *       and {@code bodyExcerpt()} (raw).</li>
 *   <li>{@link RestClientTransportException}: no usable response came (connection refused, TLS,
 *       a broken connection or response, the timeout, an interrupt). It has
 *       {@code timedOut()}.</li>
 * </ul>
 *
 * <p><b>Deviation from the need's sketch:</b> {@code status()} and {@code bodyExcerpt()} are on
 * {@link RestClientResponseException}, not here, since a timeout or a refused connection has no
 * status. It is Spring's split: {@code getStatusCode()} is on {@code RestClientResponseException}.
 *
 * <p>{@link #getMessage()} is one line of printable ASCII of at most {@value #MAX_MESSAGE}
 * characters: the method, the URI without query and fragment, and what went wrong (for a response
 * also the status and the start of the excerpt, with characters outside ASCII as
 * {@code \}{@code uXXXX}); {@code toString()} is the class name and the message.
 *
 * <p><b>The request's values.</b> The module never writes a header value, the query or the
 * request body into a message or a cause; of what it puts into a failure, only {@link #uri()}
 * has the query. What the server sends can quote them, though (a server that sends back what it
 * got), and that text reaches a failure in two ways, which the module handles:
 *
 * <ul>
 *   <li>a response's excerpt: the message and {@code safeExcerpt()} hide every whole
 *       occurrence of a value of the request, and the credentials of an {@code Authorization}
 *       header on their own, as {@link RestClientResponseException} describes; a part of a value
 *       that the server sends back on its own is not recognised and shows;</li>
 *   <li>the text of an exception from {@code java.net.http}: the message and the cause keep it
 *       only for failures whose text never comes from the server, as
 *       {@link RestClientTransportException} describes.</li>
 * </ul>
 *
 * <p>The constructors are not public: only the client makes these exceptions.
 */
public abstract sealed class RestClientException extends RuntimeException
        permits RestClientResponseException, RestClientTransportException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The longest {@link #getMessage()}, in characters. */
    public static final int MAX_MESSAGE = 1_000;

    /** The longest URI in a message; the rest of the room is for what went wrong. */
    static final int MAX_MESSAGE_URI = 400;

    private final String method;
    private final URI uri;

    RestClientException(String method, URI uri, String what, Throwable cause) {
        super(message(method, uri, what), cause);
        this.method = Objects.requireNonNull(method, "method");
        this.uri = Objects.requireNonNull(uri, "uri");
    }

    /** {@code METHOD URI: what}, one line of ASCII, at most {@link #MAX_MESSAGE} characters. */
    static String message(String method, URI uri, String what) {
        var head = method + " " + Text.limit(Uris.withoutQuery(uri), MAX_MESSAGE_URI) + ": ";
        return Text.limit(Text.ascii(head + what), MAX_MESSAGE);
    }

    /**
     * The request's method.
     *
     * @return the method, such as {@code POST}
     */
    public String method() {
        return method;
    }

    /**
     * The request's URI, with the query when the path had one (the message leaves the query out,
     * since it may hold a secret).
     *
     * @return the URI
     */
    public URI uri() {
        return uri;
    }
}
