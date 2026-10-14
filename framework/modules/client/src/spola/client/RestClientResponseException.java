package spola.client;

import java.io.Serial;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.Map;
import java.util.Objects;

/**
 * A response came but cannot be used: the counterpart of Spring's
 * {@code RestClientResponseException} (and of its {@code HttpClientErrorException},
 * {@code HttpServerErrorException} and {@code UnknownContentTypeException}). It is thrown when
 *
 * <ul>
 *   <li>the status is outside 2xx, redirects (3xx) included, since they are never followed;</li>
 *   <li>the body is larger than {@link RestClient.Builder#maxResponseSize(int)}, by its
 *       {@code Content-Length} (the body is then not read) or as it arrives (reading stops at the
 *       limit);</li>
 *   <li>{@code retrieve} or {@code retrieveJson} get an empty body or one that is not JSON
 *       ({@link #getCause()} is the {@link spola.json.JsonParseException}), or JSON that does not
 *       fit the codec ({@link #getCause()} is the {@link spola.json.codec.JsonMappingException}
 *       with every problem and its path).</li>
 * </ul>
 *
 * <p>{@link #bodyExcerpt()} is safe to log: the first at most {@value #MAX_EXCERPT} characters
 * of the body, without control characters, line separators or bidi controls. For a status
 * outside 2xx the client reads at most the first {@value RestClient#MAX_ERROR_BODY} bytes of the
 * body and then closes the response without waiting for the rest.
 */
public final class RestClientResponseException extends RestClientException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The longest excerpt, in characters ({@code char}) of the body before escaping, without {@code ...}. */
    public static final int MAX_EXCERPT = Text.MAX_EXCERPT;

    private static final HttpHeaders NO_HEADERS = HttpHeaders.of(Map.of(), (name, value) -> true);

    private final int status;
    private final transient HttpHeaders headers; // not serializable; empty after deserialization
    private final String bodyExcerpt;

    RestClientResponseException(String method, URI uri, int status, HttpHeaders headers, String bodyExcerpt,
            String what, Throwable cause) {
        super(method, uri, "status " + status + ": " + what
                + (bodyExcerpt.isEmpty() ? "" : ": " + bodyExcerpt), cause);
        this.status = status;
        this.headers = Objects.requireNonNull(headers, "headers");
        this.bodyExcerpt = bodyExcerpt;
    }

    /**
     * The response's status code.
     *
     * @return the status, such as 400; 2xx when the body was the problem
     */
    public int status() {
        return status;
    }

    /**
     * The response's headers, as {@code java.net.http} received them. They are not in the
     * message or {@code toString()}.
     *
     * @return the headers
     */
    public HttpHeaders headers() {
        return headers == null ? NO_HEADERS : headers;
    }

    /**
     * The start of the response body, safe to log.
     *
     * <ul>
     *   <li>The body is decoded as UTF-8; invalid bytes, and an incomplete sequence at the end,
     *       become U+FFFD.</li>
     *   <li>It is cut after the first {@value #MAX_EXCERPT} characters ({@code char}), never
     *       inside a surrogate pair, and then ends with {@code ...}.</li>
     *   <li>After the cut these characters are written as {@code \}{@code uXXXX}: controls
     *       (U+0000–U+001F, U+007F–U+009F, so also CR, LF and tab), U+2028, U+2029, the bidi
     *       controls U+200E, U+200F, U+202A–U+202E and U+2066–U+2069, and lone surrogates.
     *       Letters outside ASCII stay as they are.</li>
     *   <li>An empty body, or one that was not read (its {@code Content-Length} was over the
     *       limit), gives an empty string.</li>
     * </ul>
     *
     * <p>The body comes from the server: it may quote what the request sent.
     *
     * @return the excerpt, never {@code null}
     */
    public String bodyExcerpt() {
        return bodyExcerpt;
    }
}
