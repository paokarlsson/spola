package spola.test;

import java.io.Serial;
import java.time.Duration;

/**
 * The failure of a test or fixture that overran its timeout. Its stack trace is that of the
 * test's thread at the moment the timeout expired, so it shows where the test was stuck.
 */
final class TimedOut extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    TimedOut(Duration timeout, boolean stopped) {
        super("timed out after " + timeout.toMillis() + " ms" + (stopped
                ? " (interrupted and stopped)"
                : " (did not stop when interrupted; its thread is left running as a daemon"
                + " and its cleanup fixtures run only if it ever finishes)"));
    }
}
