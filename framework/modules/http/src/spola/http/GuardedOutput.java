package spola.http;

import java.io.IOException;
import java.io.OutputStream;

/**
 * The socket's output with a write timeout. A blocking socket write has no timeout of its own,
 * so this stream records when the current write started; the server's guard thread closes the
 * socket of a write that has made no progress for longer than
 * {@link HttpTimeouts#writeTimeout()}, which makes the write throw. Writes are cut into slices of
 * at most {@link #SLICE} bytes and every slice restarts the clock, so a slow but steady reader
 * is not cut off: the timeout measures time without progress, not the whole response.
 */
final class GuardedOutput extends OutputStream {

    /** The most bytes handed to the socket in one write call. */
    static final int SLICE = 16 * 1024;

    private final OutputStream out;
    private volatile long writingSince; // System.nanoTime() of the current slice; 0 when not writing

    GuardedOutput(OutputStream out) {
        this.out = out;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
        while (length > 0) {
            int n = Math.min(SLICE, length);
            writingSince = System.nanoTime() | 1; // never 0
            try {
                out.write(bytes, offset, n);
            } finally {
                writingSince = 0;
            }
            offset += n;
            length -= n;
        }
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    /** Whether the current write has waited longer than {@code timeoutNanos} at {@code now}. */
    boolean stalled(long now, long timeoutNanos) {
        long since = writingSince;
        return since != 0 && now - since > timeoutNanos;
    }
}
