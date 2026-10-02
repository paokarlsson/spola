package spola.http;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * The buffered input side of one connection, with the two kinds of timeout the server needs:
 * a timeout per read call ({@link #timeoutPerRead}, for idle waits and the body) and an
 * absolute deadline ({@link #deadline}, for the header section, so that a client sending one
 * byte at a time cannot stretch it). Used by one thread at a time.
 */
final class ConnectionInput {

    private final Socket socket;
    private final InputStream in;
    private final byte[] buffer = new byte[8 * 1024];
    private byte[] line = new byte[256]; // grows up to the longest line allowed
    private int pos;
    private int end;
    private boolean deadlineMode;
    private long deadlineNanos;
    private int timeoutMillis = 1;
    private int appliedTimeout = -1;

    ConnectionInput(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
    }

    /** Every read from now on waits at most {@code millis} for data. */
    void timeoutPerRead(int millis) {
        deadlineMode = false;
        timeoutMillis = millis;
    }

    /** Every read from now on must finish before {@code System.nanoTime()} reaches {@code nanos}. */
    void deadline(long nanos) {
        deadlineMode = true;
        deadlineNanos = nanos;
    }

    /** Bytes already received and not yet consumed. */
    int buffered() {
        return end - pos;
    }

    private boolean fill() throws IOException {
        if (pos == end) {
            pos = 0;
            end = 0;
        } else if (end == buffer.length) {
            System.arraycopy(buffer, pos, buffer, 0, end - pos);
            end -= pos;
            pos = 0;
        }
        int timeout = timeoutMillis;
        if (deadlineMode) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                throw new SocketTimeoutException("header timeout");
            }
            timeout = (int) Math.min(Integer.MAX_VALUE, Math.max(1, (remaining + 999_999) / 1_000_000));
        }
        if (timeout != appliedTimeout) {
            socket.setSoTimeout(timeout);
            appliedTimeout = timeout;
        }
        int n = in.read(buffer, end, buffer.length - end);
        if (n < 0) {
            return false;
        }
        end += n;
        return true;
    }

    /** The next byte, or -1 at the end of the stream. */
    int read() throws IOException {
        if (pos == end && !fill()) {
            return -1;
        }
        return buffer[pos++] & 0xFF;
    }

    /** Waits for at least one byte without consuming it; false at the end of the stream. */
    boolean await() throws IOException {
        return pos < end || fill();
    }

    /** Up to {@code length} bytes into {@code target}, or -1 at the end of the stream. */
    int read(byte[] target, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (pos == end && !fill()) {
            return -1;
        }
        int n = Math.min(length, end - pos);
        System.arraycopy(buffer, pos, target, offset, n);
        pos += n;
        return n;
    }

    /** Skips up to {@code length} bytes; returns how many, or -1 at the end of the stream. */
    long skip(long length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (pos == end && !fill()) {
            return -1;
        }
        int n = (int) Math.min(length, end - pos);
        pos += n;
        return n;
    }

    /**
     * Reads one line that ends in CRLF (RFC 9112 §2.2) and returns it without the CRLF, one char
     * per byte (ISO-8859-1). A CR not followed by LF and an LF not preceded by CR are refused with
     * 400; a line longer than {@code max} bytes is refused with {@code tooLongStatus}.
     *
     * @throws EOFException if the stream ends inside the line
     */
    String readLine(int max, int tooLongStatus, String what) throws IOException, ProtocolException {
        int n = 0;
        while (true) {
            int b = read();
            if (b < 0) {
                throw new EOFException("connection closed inside the " + what);
            }
            if (b == '\r') {
                int next = read();
                if (next == '\n') {
                    return new String(line, 0, n, StandardCharsets.ISO_8859_1);
                }
                if (next < 0) {
                    throw new EOFException("connection closed inside the " + what);
                }
                throw new ProtocolException(400, "CR without LF in the " + what);
            }
            if (b == '\n') {
                throw new ProtocolException(400, "LF without CR in the " + what);
            }
            if (n >= max) {
                throw new ProtocolException(tooLongStatus, what + " longer than " + max + " bytes");
            }
            if (n == line.length) {
                line = java.util.Arrays.copyOf(line, (int) Math.min((long) max, 2L * line.length));
            }
            line[n++] = (byte) b;
        }
    }
}
