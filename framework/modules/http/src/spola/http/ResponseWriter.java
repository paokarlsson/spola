package spola.http;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Writes responses to one connection: the status line, {@code Date}, the application's headers,
 * the framing the server chooses ({@code Content-Length}, {@code Transfer-Encoding: chunked} or
 * none for a body delimited by closing the connection), {@code Connection: close} when the
 * connection ends, and the body. Knows whether the response has started ({@link #committed()}):
 * after that, an error can only abort the connection.
 */
final class ResponseWriter {

    /** IMF-fixdate (RFC 9110 §5.6.7): {@code Sun, 06 Nov 1994 08:49:37 GMT}. */
    static final DateTimeFormatter IMF_FIXDATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] LAST_CHUNK = "0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    /** The body writer threw; {@link #committed()} tells whether anything went out. */
    static final class BodyWriterFailed extends Exception {
        @Serial
        private static final long serialVersionUID = 1L;

        BodyWriterFailed(Throwable cause) {
            super("the body writer failed", cause);
        }
    }

    private final OutputStream wire;
    private boolean committed;

    ResponseWriter(OutputStream wire) {
        this.wire = wire;
    }

    /** Whether bytes of the current response have been handed to the connection. */
    boolean committed() {
        return committed;
    }

    /** Starts a new response on the connection. */
    void reset() {
        committed = false;
    }

    /** {@code HTTP/1.1 100 Continue} (RFC 9110 §15.2.1), flushed. */
    void sendContinue() throws IOException {
        wire.write(CONTINUE);
        wire.flush();
    }

    /**
     * Sends {@code response}. For {@code HEAD} only the head goes out (with the length a
     * {@code GET} would get for a byte body); a streaming writer is not called.
     *
     * @param headRequest the request method is {@code HEAD}
     * @param version     the request's version; a streaming body to HTTP/1.0 is delimited by
     *                    closing, and the caller must close
     * @param close       add {@code Connection: close}
     */
    void send(Response response, boolean headRequest, HttpVersion version, boolean close)
            throws IOException, BodyWriterFailed {
        int status = response.status();
        switch (response.body()) {
            case ResponseBody.Empty _ -> {
                // 204 and 304 never carry Content-Length here; 205 says 0 (RFC 9110 15.3.6)
                commit(head(response, status == 204 || status == 304 ? null : "Content-Length: 0", close));
                wire.flush();
            }
            case ResponseBody.Bytes bytes -> {
                commit(head(response, "Content-Length: " + bytes.length(), close));
                if (!headRequest) {
                    wire.write(bytes.unsafeArray());
                }
                wire.flush();
            }
            case ResponseBody.Streaming streaming -> {
                boolean chunked = version == HttpVersion.HTTP_1_1;
                var head = head(response, chunked ? "Transfer-Encoding: chunked" : null, close);
                if (headRequest) {
                    commit(head);
                    wire.flush();
                    return;
                }
                var body = new StreamingBody(head, chunked);
                try {
                    streaming.writer().writeTo(body);
                } catch (Exception | Error e) {
                    body.done = true;
                    throw new BodyWriterFailed(e);
                }
                body.finish();
            }
        }
    }

    private void commit(byte[] head) throws IOException {
        committed = true;
        wire.write(head);
    }

    /** The head of a response with the server's framing header (or none) and connection header. */
    static byte[] head(Response response, String framing, boolean close) {
        var out = new StringBuilder(256);
        out.append("HTTP/1.1 ").append(response.status()).append(' ').append(reason(response.status())).append("\r\n");
        out.append("Date: ").append(IMF_FIXDATE.format(ZonedDateTime.now(ZoneOffset.UTC))).append("\r\n");
        for (var field : response.headers().fields()) {
            out.append(field.name()).append(": ").append(field.value()).append("\r\n");
        }
        if (framing != null) {
            out.append(framing).append("\r\n");
        }
        if (close) {
            out.append("Connection: close\r\n");
        }
        out.append("\r\n");
        return out.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /** The reason phrase of RFC 9110 §15 for the codes the server or a handler commonly uses. */
    static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 203 -> "Non-Authoritative Information";
            case 204 -> "No Content";
            case 205 -> "Reset Content";
            case 206 -> "Partial Content";
            case 300 -> "Multiple Choices";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 303 -> "See Other";
            case 304 -> "Not Modified";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 406 -> "Not Acceptable";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 410 -> "Gone";
            case 411 -> "Length Required";
            case 412 -> "Precondition Failed";
            case 413 -> "Content Too Large";
            case 414 -> "URI Too Long";
            case 415 -> "Unsupported Media Type";
            case 416 -> "Range Not Satisfiable";
            case 417 -> "Expectation Failed";
            case 422 -> "Unprocessable Content";
            case 426 -> "Upgrade Required";
            case 428 -> "Precondition Required";
            case 429 -> "Too Many Requests";
            case 431 -> "Request Header Fields Too Large";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            case 505 -> "HTTP Version Not Supported";
            default -> "";
        };
    }

    /**
     * The stream a {@link BodyWriter} writes to. Buffers up to 8 KiB; every flush (and every full
     * buffer) goes out as one chunk (HTTP/1.1) or as raw bytes (HTTP/1.0). The head is written with
     * the first bytes, so a writer that fails before writing anything still gets a clean 500.
     */
    private final class StreamingBody extends OutputStream {
        private final byte[] head;
        private final boolean chunked;
        private final byte[] buffer = new byte[8_192];
        private int count;
        private boolean done;

        StreamingBody(byte[] head, boolean chunked) {
            this.head = head;
            this.chunked = chunked;
        }

        @Override
        public void write(int b) throws IOException {
            ensureOpen();
            if (count == buffer.length) {
                emit();
            }
            buffer[count++] = (byte) b;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            ensureOpen();
            if (length >= buffer.length) {
                emit();
                out(bytes, offset, length);
                return;
            }
            if (length > buffer.length - count) {
                emit();
            }
            System.arraycopy(bytes, offset, buffer, count, length);
            count += length;
        }

        @Override
        public void flush() throws IOException {
            ensureOpen();
            emit();
        }

        /** Does not end the body; the server ends it when the writer returns. */
        @Override
        public void close() {
        }

        private void ensureOpen() throws IOException {
            if (done) {
                throw new IOException("the response body has ended");
            }
        }

        private void emit() throws IOException {
            if (!committed) {
                commit(head);
            }
            if (count > 0) {
                out(buffer, 0, count);
                count = 0;
            }
            wire.flush();
        }

        private void out(byte[] bytes, int offset, int length) throws IOException {
            if (!committed) {
                commit(head);
            }
            if (chunked) {
                wire.write(Long.toHexString(length).getBytes(StandardCharsets.ISO_8859_1));
                wire.write(CRLF);
                wire.write(bytes, offset, length);
                wire.write(CRLF);
            } else {
                wire.write(bytes, offset, length);
            }
        }

        void finish() throws IOException {
            done = true;
            if (!committed) {
                commit(head);
            }
            if (count > 0) {
                out(buffer, 0, count);
                count = 0;
            }
            if (chunked) {
                wire.write(LAST_CHUNK);
            }
            wire.flush();
        }
    }
}
