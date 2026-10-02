package spola.http;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;

/**
 * The request body as the handler sees it: exactly {@code Content-Length} bytes, or the decoded
 * chunked body (RFC 9112 §7.1), never more than {@link HttpLimits#maxBodySize()}. Reads wait at
 * most {@link HttpTimeouts#readTimeout()} for progress. The first read sends
 * {@code 100 Continue} when the client asked for it. Every failure is a
 * {@link RequestBodyException} that is remembered: later reads throw it again, and the server
 * uses its status. {@link #close()} does nothing to the connection.
 */
final class BodyStream extends InputStream {

    /**
     * The longest chunk-size line, extensions included, without its CRLF. Chunk extensions are
     * allowed (RFC 9112 §7.1.1) but nothing reads them, so a short limit costs nothing.
     */
    static final int MAX_CHUNK_LINE = 4_096;

    /** Called before the first read of the body; sends {@code 100 Continue} if needed. */
    interface FirstRead {
        void run() throws IOException;
    }

    private final ConnectionInput in;
    private final RequestParser.Framing framing;
    private final HttpLimits limits;
    private final int readTimeoutMillis;
    private final FirstRead firstRead;

    private long remaining;          // LENGTH: bytes left; CHUNKED: bytes left in this chunk
    private long total;              // CHUNKED: sum of the chunk sizes so far
    private boolean chunkDataPending; // CHUNKED: a chunk's data ended and its CRLF is not read yet
    private boolean started;
    private boolean finished;
    private boolean over;            // the exchange is over; the handler may no longer read
    private RequestBodyException failure;

    BodyStream(ConnectionInput in, RequestParser.Head head, HttpLimits limits, int readTimeoutMillis,
               FirstRead firstRead) {
        this.in = in;
        this.framing = head.framing();
        this.limits = limits;
        this.readTimeoutMillis = readTimeoutMillis;
        this.firstRead = firstRead;
        this.remaining = framing == RequestParser.Framing.LENGTH ? head.contentLength() : 0;
        this.finished = framing == RequestParser.Framing.NONE;
    }

    /** Whether the handler (or the server) has started reading. */
    boolean started() {
        return started;
    }

    /** Whether the whole body has been read. */
    boolean finished() {
        return finished;
    }

    /** The failure, or {@code null}. */
    RequestBodyException failure() {
        return failure;
    }

    /** Ends the handler's access: later reads throw. */
    void end() {
        over = true;
    }

    @Override
    public int read() throws IOException {
        var one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, target.length);
        if (over) {
            throw new IOException("the request is over; the body can only be read while the handler runs");
        }
        return readBody(target, offset, length);
    }

    private int readBody(byte[] target, int offset, int length) throws IOException {
        if (failure != null) {
            throw failure;
        }
        if (length == 0) {
            return 0;
        }
        if (finished) {
            return -1;
        }
        if (!started) {
            started = true;
            firstRead.run();
        }
        in.timeoutPerRead(readTimeoutMillis);
        try {
            if (framing == RequestParser.Framing.CHUNKED && remaining == 0) {
                nextChunk();
                if (finished) {
                    return -1;
                }
            }
            int n = in.read(target, offset, (int) Math.min(length, remaining));
            if (n < 0) {
                throw fail(new RequestBodyException(400, "the connection closed inside the request body"));
            }
            remaining -= n;
            if (remaining == 0) {
                if (framing == RequestParser.Framing.LENGTH) {
                    finished = true;
                } else {
                    chunkDataPending = true;
                }
            }
            return n;
        } catch (RequestBodyException e) {
            throw e;
        } catch (SocketTimeoutException e) {
            throw fail(new RequestBodyException(408, "no progress reading the request body within the read timeout",
                    e));
        } catch (EOFException e) {
            throw fail(new RequestBodyException(400, "the connection closed inside the request body", e));
        } catch (ProtocolException e) {
            throw fail(new RequestBodyException(e.status(), e.getMessage()));
        } catch (IOException e) {
            throw fail(new RequestBodyException(400, "reading the request body failed", e));
        }
    }

    private RequestBodyException fail(RequestBodyException e) {
        failure = e;
        return e;
    }

    /** Reads the CRLF after a chunk's data, the next chunk-size line and, at the end, the trailers. */
    private void nextChunk() throws IOException, ProtocolException {
        if (chunkDataPending) {
            int cr = in.read();
            int lf = cr < 0 ? -1 : in.read();
            if (cr < 0 || lf < 0) {
                throw new EOFException();
            }
            if (cr != '\r' || lf != '\n') {
                throw new ProtocolException(400, "chunk data not followed by CRLF (RFC 9112 7.1)");
            }
            chunkDataPending = false;
        }
        var line = in.readLine(MAX_CHUNK_LINE, 400, "chunk-size line");
        long size = chunkSize(line);
        if (size == 0) {
            RequestParser.headerSection(in, limits, "trailer section"); // read and dropped (RFC 9112 7.1.2)
            finished = true;
            return;
        }
        if (size > limits.maxBodySize() - total) {
            throw new ProtocolException(413, "chunked body over maxBodySize (RFC 9110 15.5.14)");
        }
        total += size;
        remaining = size;
    }

    /**
     * {@code chunk-size [ chunk-ext ]} (RFC 9112 §7.1, §7.1.1): hex digits without sign, prefix
     * or whitespace, within {@code long}; then nothing, or extensions
     * {@code *( BWS ";" BWS name [ BWS "=" BWS ( token / quoted-string ) ] )}.
     */
    static long chunkSize(String line) throws ProtocolException {
        int i = 0;
        long size = 0;
        while (i < line.length() && Syntax.isHexDigit(line.charAt(i))) {
            if (size > (Long.MAX_VALUE >> 4)) {
                throw new ProtocolException(400, "chunk size does not fit in 63 bits (RFC 9112 7.1)");
            }
            size = (size << 4) | Character.digit(line.charAt(i), 16);
            i++;
        }
        if (i == 0) {
            throw new ProtocolException(400, "chunk size is not hexadecimal digits (RFC 9112 7.1)");
        }
        if (i < line.length() && !extensions(line, i)) {
            throw new ProtocolException(400, "malformed chunk size or chunk extension (RFC 9112 7.1.1)");
        }
        return size;
    }

    private static boolean extensions(String line, int i) {
        int n = line.length();
        while (i < n) {
            i = skipWhitespace(line, i);
            if (i >= n || line.charAt(i) != ';') {
                return false;
            }
            i = skipWhitespace(line, i + 1);
            int start = i;
            while (i < n && Syntax.isTchar(line.charAt(i))) {
                i++;
            }
            if (i == start) {
                return false;
            }
            int afterName = i;
            i = skipWhitespace(line, i);
            if (i < n && line.charAt(i) == '=') {
                i = skipWhitespace(line, i + 1);
                if (i < n && line.charAt(i) == '"') {
                    i = quotedStringEnd(line, i);
                    if (i < 0) {
                        return false;
                    }
                } else {
                    int valueStart = i;
                    while (i < n && Syntax.isTchar(line.charAt(i))) {
                        i++;
                    }
                    if (i == valueStart) {
                        return false;
                    }
                }
            } else {
                i = afterName;
            }
        }
        return true;
    }

    private static int skipWhitespace(String line, int i) {
        while (i < line.length() && Syntax.isWhitespace(line.charAt(i))) {
            i++;
        }
        return i;
    }

    /** The index after the quoted-string starting at {@code i} (RFC 9110 §5.6.4), or -1. */
    private static int quotedStringEnd(String line, int i) {
        for (i = i + 1; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                return i + 1;
            }
            if (c == '\\') {
                i++;
                if (i >= line.length() || !Syntax.isFieldValueChar(line.charAt(i))) {
                    return -1;
                }
            } else if (!Syntax.isFieldValueChar(c)) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Reads and drops what the handler left, within the limits and the read timeout. Returns
     * whether the body ended cleanly (the connection can carry another request).
     */
    boolean skipRest() {
        if (failure != null) {
            return false;
        }
        var sink = new byte[8_192];
        started = true; // never send 100 Continue for a body the server only skips
        try {
            while (readBody(sink, 0, sink.length) >= 0) {
                // dropped
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Does nothing: the body belongs to the connection, which the server closes. */
    @Override
    public void close() {
    }
}
