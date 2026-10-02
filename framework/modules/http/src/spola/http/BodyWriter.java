package spola.http;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes a streaming response body. The server calls it after the handler has returned, on the
 * connection's thread; for HTTP/1.1 every {@code flush()} (and every 8 KiB) becomes a chunk,
 * for HTTP/1.0 the bytes go out as they are and the connection is closed at the end. The writer
 * is not called for {@code HEAD}.
 *
 * <pre>{@code
 * Response.stream(200, "text/plain; charset=utf-8", out -> {
 *     for (int i = 0; i < 3; i++) {
 *         out.write(("line " + i + "\n").getBytes(StandardCharsets.UTF_8));
 *         out.flush();
 *     }
 * });
 * }</pre>
 *
 * <p>If the writer throws after the first bytes have gone out, the connection is closed without
 * ending the body, so the client sees an aborted response, never a complete one. If it throws
 * before that, the client gets 500. Closing {@code out} has no effect; the server ends the body
 * when the writer returns.
 */
@FunctionalInterface
public interface BodyWriter {

    /**
     * Writes the body.
     *
     * @param out the body; do not use it after this method returns
     * @throws IOException if writing fails, for example because the client went away or stopped
     *                     reading past the write timeout
     */
    void writeTo(OutputStream out) throws IOException;
}
