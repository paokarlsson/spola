package spola.client;

import java.io.Serial;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.List;
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
 * <p>{@link #bodyExcerpt()} is the first at most {@value #MAX_EXCERPT} characters of the body,
 * without control characters, line separators or bidi controls, so it cannot forge or break a
 * log line. For a status outside 2xx the client reads at most the first
 * {@value RestClient#MAX_ERROR_BODY} bytes of the body and then closes the response without
 * waiting for the rest.
 *
 * <p><b>The server's text and the request's values.</b> {@link #bodyExcerpt()} is what the server
 * sent, so it holds whatever the server quotes of the request: some servers send back the body
 * (a validation error with the input) or the headers (an echo endpoint). {@link #getMessage()}
 * shows the start of the excerpt with every such value hidden: each whole occurrence of a header
 * value that the caller gave, of the query or of the request body (as sent) is
 * {@code <hidden>}, and so is a value cut off where the client stopped reading. A part of a value
 * that the server sends back on its own (one member of the body, one parameter of the query) or
 * changes first (re-encoded JSON) is not recognised and shows. The search is for the text as it
 * is, so a short value (a header {@code X-Page: 2}) also hides the same text where the server
 * wrote it on its own; {@link #bodyExcerpt()} still has it. {@link #getCause()}, when there is
 * one, is {@code spola.json}'s exception, whose message can quote up to 40 characters of the
 * response.
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

    private RestClientResponseException(String method, URI uri, int status, HttpHeaders headers, String bodyExcerpt,
            String shownExcerpt, String what, Throwable cause) {
        super(method, uri, "status " + status + ": " + what + (shownExcerpt.isEmpty() ? "" : ": " + shownExcerpt),
                cause);
        this.status = status;
        this.headers = Objects.requireNonNull(headers, "headers");
        this.bodyExcerpt = bodyExcerpt;
    }

    /**
     * The exception for a response to {@code request} that cannot be used. The message shows the
     * excerpt with the request's values hidden; {@link #bodyExcerpt()} has it as it is.
     *
     * @param request the request
     * @param status  the status
     * @param headers the response's headers
     * @param body    the body as far as it was read, at least {@code length} bytes
     * @param length  how many bytes of {@code body} were read
     * @param more    whether the body went on past {@code length} (reading stopped at a limit)
     * @param what    what went wrong, for the message
     * @param cause   the cause, or {@code null}
     */
    static RestClientResponseException of(RestRequest request, int status, HttpHeaders headers, byte[] body,
            int length, boolean more, String what, Throwable cause) {
        return new RestClientResponseException(request.method(), request.uri(), status, headers,
                Text.excerpt(body, length, more, List.of()), Text.excerpt(body, length, more, request.secrets()),
                what, cause);
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
     * The start of the response body, safe to put in a log line (no line breaks or controls),
     * but the server's text as it is.
     *
     * <ul>
     *   <li>The body is decoded as UTF-8; invalid bytes, and an incomplete sequence at the end,
     *       become U+FFFD.</li>
     *   <li>It is cut after the first {@value #MAX_EXCERPT} characters ({@code char}), never
     *       inside a surrogate pair, and then ends with {@code ...}.</li>
     *   <li>After the cut these characters are written as {@code \}{@code uXXXX}: controls
     *       (U+0000–U+001F, U+007F–U+009F, so also CR, LF and tab), U+2028, U+2029, the bidi
     *       controls U+061C, U+200E, U+200F, U+202A–U+202E and U+2066–U+2069, and lone
     *       surrogates. Letters outside ASCII stay as they are.</li>
     *   <li>When the client stopped reading the body at a limit ({@code maxResponseSize}, or the
     *       first {@value RestClient#MAX_ERROR_BODY} bytes of a body outside 2xx), the excerpt
     *       ends with {@code ...} even if it is shorter than {@value #MAX_EXCERPT} characters, and
     *       a character that the stop split is left out rather than shown as U+FFFD.</li>
     *   <li>An empty body, or one that was not read (its {@code Content-Length} was over the
     *       limit), gives an empty string.</li>
     * </ul>
     *
     * <p>The body comes from the server: it may quote what the request sent, header values and
     * body included. Unlike {@link #getMessage()}, the excerpt hides nothing.
     *
     * @return the excerpt, never {@code null}
     */
    public String bodyExcerpt() {
        return bodyExcerpt;
    }
}
