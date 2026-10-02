package spola.http;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * A response: status, headers and body. Build it with the factories and add headers with
 * {@link #withHeader}:
 *
 * <pre>{@code
 * Response.text(200, "hello").withHeader("Cache-Control", "no-store");
 * Response.bytes(200, "application/json", json);
 * Response.stream(200, "text/plain; charset=utf-8", out -> out.write(data));
 * Response.status(204);
 * }</pre>
 *
 * <p>The server owns the framing and the connection: it adds {@code Date},
 * {@code Content-Length} or {@code Transfer-Encoding: chunked}, and {@code Connection: close}
 * when it closes the connection, so a response must not carry those four headers itself. It adds
 * no {@code Server} header. Header names and values are checked by {@link Headers.Field}, so no
 * header can carry CR, LF or NUL (no response splitting).
 *
 * @param status  the status code, 200–599 (1xx informational responses are the server's
 *                business: {@code 100 Continue} is sent for {@code Expect}, upgrades are not
 *                supported)
 * @param headers the headers
 * @param body    the body; must be {@link ResponseBody.Empty} for 204, 205 and 304
 */
public record Response(int status, Headers headers, ResponseBody body) {

    /** Header names the server sets itself (compared without regard to case). */
    static final List<String> RESERVED = List.of("Content-Length", "Transfer-Encoding", "Connection", "Date");

    /**
     * Checks the response.
     *
     * @throws NullPointerException     if a component is {@code null}
     * @throws IllegalArgumentException if the status is outside 200–599, a header is one the
     *                                  server sets ({@code Content-Length},
     *                                  {@code Transfer-Encoding}, {@code Connection},
     *                                  {@code Date}), or a 204, 205 or 304 response has a body
     */
    public Response {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
        if (status >= 100 && status <= 199) {
            throw new IllegalArgumentException("status " + status + ": informational (1xx) responses are not"
                    + " supported; 100 Continue is sent by the server and upgrades (101) are not available");
        }
        if (status < 200 || status > 599) {
            throw new IllegalArgumentException("status must be between 200 and 599, was " + status);
        }
        for (var field : headers.fields()) {
            for (var reserved : RESERVED) {
                if (field.name().equalsIgnoreCase(reserved)) {
                    throw new IllegalArgumentException("header " + field.name()
                            + " is set by the server and must not be in a response");
                }
            }
        }
        if (hasNoContent(status) && !(body instanceof ResponseBody.Empty)) {
            throw new IllegalArgumentException("status " + status + " has no content (RFC 9110 15.3.5, 15.3.6,"
                    + " 15.4.5); use Response.status(" + status + ")");
        }
    }

    /** 204, 205 and 304 never have content (RFC 9110 §15.3.5, §15.3.6, §15.4.5). */
    static boolean hasNoContent(int status) {
        return status == 204 || status == 205 || status == 304;
    }

    /**
     * A text response in UTF-8 with {@code Content-Type: text/plain; charset=utf-8}.
     *
     * @param status the status code
     * @param text   the body
     * @return the response
     */
    public static Response text(int status, String text) {
        return bytes(status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A response with a body known in advance and its {@code Content-Type}.
     *
     * @param status      the status code
     * @param contentType the {@code Content-Type} value
     * @param bytes       the body (copied)
     * @return the response
     */
    public static Response bytes(int status, String contentType, byte[] bytes) {
        return new Response(status, Headers.of("Content-Type", contentType), new ResponseBody.Bytes(bytes));
    }

    /**
     * A streaming response with its {@code Content-Type}; see {@link BodyWriter}.
     *
     * @param status      the status code
     * @param contentType the {@code Content-Type} value
     * @param writer      writes the body
     * @return the response
     */
    public static Response stream(int status, String contentType, BodyWriter writer) {
        return new Response(status, Headers.of("Content-Type", contentType), new ResponseBody.Streaming(writer));
    }

    /**
     * A response without a body and without headers.
     *
     * @param status the status code
     * @return the response
     */
    public static Response status(int status) {
        return new Response(status, Headers.EMPTY, ResponseBody.EMPTY);
    }

    /**
     * A copy with one more header.
     *
     * @param name  the field name
     * @param value the field value
     * @return the copy
     * @throws IllegalArgumentException if the name or value is not allowed or the header is one
     *                                  the server sets
     */
    public Response withHeader(String name, String value) {
        return new Response(status, headers.with(name, value), body);
    }
}
