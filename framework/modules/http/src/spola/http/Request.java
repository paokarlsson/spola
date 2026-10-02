package spola.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * A request as the parser accepted it. Nothing is decoded: the path and the query are the raw
 * text of the request-target (percent-decoding and query parameters belong to routing).
 *
 * <p>The body is read lazily from the connection, on the handler's thread, while the handler
 * runs (or while the {@link BodyWriter} of a streaming response runs). It is bounded by the
 * request's own length and by {@link HttpLimits#maxBodySize()}; a failure is a
 * {@link RequestBodyException}. If the client sent {@code Expect: 100-continue}, the server
 * sends {@code 100 Continue} when the body is first read, and not at all if it never is. What
 * the handler leaves unread is skipped by the server before the next request on the connection.
 */
public final class Request {

    private final RequestParser.Head head;
    private final BodyStream body;

    Request(RequestParser.Head head, BodyStream body) {
        this.head = head;
        this.body = body;
    }

    /**
     * The method, case-sensitive as sent ({@code GET}, {@code POST}, or any other token).
     *
     * @return the method
     */
    public String method() {
        return head.method();
    }

    /**
     * The request-target exactly as it appeared in the request-line: {@code /a/b?x=1} (origin-form),
     * {@code http://example.com/a} (absolute-form) or {@code *} (asterisk-form, {@code OPTIONS} only).
     *
     * @return the request-target
     */
    public String target() {
        return head.target();
    }

    /**
     * The raw path of the target, not percent-decoded: {@code /a/b} for {@code /a/b?x=1}, {@code /}
     * for an absolute-form target without a path, {@code *} for the asterisk-form.
     *
     * @return the path
     */
    public String path() {
        return head.path();
    }

    /**
     * The raw query of the target after the first {@code ?}, not decoded.
     *
     * @return the query (possibly empty), or empty if the target has no {@code ?}
     */
    public Optional<String> query() {
        return Optional.ofNullable(head.query());
    }

    /**
     * The host the request is for: the authority of an absolute-form target (which takes
     * precedence over {@code Host}, RFC 9112 §3.2.2), otherwise the {@code Host} value, otherwise
     * (HTTP/1.0 without {@code Host}) the empty string. Checked as {@code uri-host [":" port]}.
     *
     * @return the host and optional port
     */
    public String host() {
        return head.host();
    }

    /**
     * The HTTP version of the request.
     *
     * @return the version
     */
    public HttpVersion version() {
        return head.version();
    }

    /**
     * The header fields, in order, with names compared without regard to case.
     *
     * @return the headers
     */
    public Headers headers() {
        return head.headers();
    }

    /**
     * The body as a stream (the same stream on every call). Closing it has no effect on the
     * connection.
     *
     * @return the body; empty if the request has none
     */
    public InputStream body() {
        return body;
    }

    /**
     * Reads the rest of the body.
     *
     * @return the bytes
     * @throws RequestBodyException if the body is too large, malformed, or stops arriving
     * @throws IOException          if the connection fails
     */
    public byte[] bytes() throws IOException {
        return body.readAllBytes();
    }

    /** Method and target, for logs (the target is visible ASCII by construction). */
    @Override
    public String toString() {
        return head.method() + " " + head.target() + " " + head.version().text();
    }
}
