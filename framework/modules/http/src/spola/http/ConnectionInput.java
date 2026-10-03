package spola.http;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The buffered input side of one connection, with the two kinds of timeout the server needs:
 * a timeout per read call ({@link #timeoutPerRead}, for idle waits and the body) and an
 * absolute deadline ({@link #deadline}, for the header section, so that a client sending one
 * byte at a time cannot stretch it). Used by one thread at a time.
 *
 * <p>Both are applied as the socket's {@code soTimeout}, which bounds each wait for TCP data.
 * Over TLS that is not enough: one read of the {@code SSLSocket} blocks until a whole TLS record
 * has arrived and may wait for TCP data many times, each time with the full {@code soTimeout},
 * so a client that sends a record one byte at a time would never be cut off. Every read
 * therefore also publishes when it must have returned ({@link #overdue}); for TLS connections the
 * server's guard thread closes the TCP socket underneath a read that is overdue
 * ({@link #cutOff}), and the read then fails with a {@link SocketTimeoutException} just as a
 * plain socket's read would have.
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
    private volatile long readDeadline; // System.nanoTime() a running read must have returned by; 0 when none runs
    private volatile boolean cutOff;     // the guard closed the socket under an overdue read

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

    /** Called by the guard thread: whether a read is running past its deadline at {@code now}. */
    boolean overdue(long now) {
        long deadline = readDeadline;
        return deadline != 0 && now - deadline > 0;
    }

    /**
     * Called by the guard thread just before it closes the socket under an overdue read: the
     * read's failure is then reported as a timeout.
     */
    void cutOff() {
        cutOff = true;
    }

    /** Whether the guard has closed the socket under an overdue read. */
    boolean wasCutOff() {
        return cutOff;
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
        long now = System.nanoTime();
        int timeout = timeoutMillis;
        long mustReturnBy = now + timeoutMillis * 1_000_000L;
        if (deadlineMode) {
            long remaining = deadlineNanos - now;
            if (remaining <= 0) {
                throw new SocketTimeoutException("header timeout");
            }
            timeout = (int) Math.min(Integer.MAX_VALUE, Math.max(1, (remaining + 999_999) / 1_000_000));
            mustReturnBy = deadlineNanos;
        }
        if (timeout != appliedTimeout) {
            socket.setSoTimeout(timeout);
            appliedTimeout = timeout;
        }
        int n;
        readDeadline = mustReturnBy | 1; // never 0
        try {
            n = in.read(buffer, end, buffer.length - end);
        } catch (IOException e) {
            if (cutOff) {
                var timedOut = new SocketTimeoutException("read deadline passed");
                timedOut.initCause(e);
                throw timedOut;
            }
            throw e;
        } finally {
            readDeadline = 0;
        }
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
                line = Arrays.copyOf(line, (int) Math.min((long) max, 2L * line.length));
            }
            line[n++] = (byte) b;
        }
    }
}
