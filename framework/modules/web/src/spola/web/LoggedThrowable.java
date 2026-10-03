package spola.web;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;

/**
 * A copy of a throwable that is safe to log: class names and messages are escaped with
 * {@link Text#escape} (an exception message may hold request data, decoded control characters
 * included), the stack trace is kept, and the cause chain is copied the same way (at most
 * {@link #MAX_CAUSES} deep, cycles cut, suppressed exceptions left out). Its {@code toString()}
 * is the escaped {@code class: message}.
 */
final class LoggedThrowable extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    static final int MAX_CAUSES = 16;

    private LoggedThrowable(String text, Throwable cause, StackTraceElement[] trace) {
        super(text, cause, false, true);
        setStackTrace(trace);
    }

    static Throwable of(Throwable original) {
        var chain = new ArrayList<Throwable>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (var t = original; t != null && chain.size() < MAX_CAUSES && seen.add(t); t = t.getCause()) {
            chain.add(t);
        }
        Throwable copy = null;
        for (int i = chain.size() - 1; i >= 0; i--) {
            var t = chain.get(i);
            copy = new LoggedThrowable(describe(t), copy, t.getStackTrace());
        }
        return copy;
    }

    /** The escaped {@code class: message}, or the class alone. */
    static String describe(Throwable t) {
        String message;
        try {
            message = t.getMessage();
        } catch (RuntimeException e) {
            message = "(message unavailable)";
        }
        var name = Text.escape(t.getClass().getName(), Text.MAX_LOGGED);
        return message == null ? name : name + ": " + Text.escape(message, Text.MAX_LOGGED);
    }

    @Override
    public String toString() {
        return getMessage();
    }
}
