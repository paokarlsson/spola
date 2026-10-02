package spola.util;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.ErrorManager;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * The handler {@link Logging} puts on the root logger: formats each record with
 * {@link LineFormatter} in the logging thread and writes it to the configured stream at once
 * (no buffering). Lines from different threads never interleave. The stream belongs to the
 * caller and is never closed.
 */
final class LineHandler extends Handler {

    private final OutputStream out;

    LineHandler(OutputStream out, LineFormatter formatter) {
        this.out = out;
        setFormatter(formatter);
        setLevel(Level.ALL);
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || !isLoggable(record)) {
            return;
        }
        String text;
        try {
            text = getFormatter().format(record);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            // reportError takes an Exception, so a non-fatal Error (say a StackOverflowError from
            // a formatter) is wrapped only to fit that signature. LineFormatter already catches
            // what printStackTrace throws, so this is close to unreachable.
            reportError(null, e instanceof Exception ex ? ex : new RuntimeException(e), ErrorManager.FORMAT_FAILURE);
            return;
        }
        synchronized (this) {
            try {
                if (out instanceof PrintStream print) {
                    print.print(text);
                    print.flush();
                } else {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException | RuntimeException e) {
                reportError(null, e, ErrorManager.WRITE_FAILURE);
            }
        }
    }

    @Override
    public synchronized void flush() {
        try {
            out.flush();
        } catch (IOException e) {
            reportError(null, e, ErrorManager.FLUSH_FAILURE);
        }
    }

    /** Flushes only: the stream is not ours to close. */
    @Override
    public void close() {
        flush();
    }
}
