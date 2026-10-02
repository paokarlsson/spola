package spola.http;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;

/**
 * A copy of a throwable that is safe to log: the class name and message are escaped with
 * {@link Syntax#escape} (an exception message may hold request data, control characters
 * included), the stack trace is kept, and the cause chain is copied the same way (at most
 * {@link #MAX_CAUSES} deep, cycles cut). Its {@code toString()} is the escaped
 * {@code class: message}, so a printed stack trace shows the original class names.
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
        var name = Syntax.escape(t.getClass().getName());
        return message == null ? name : name + ": " + Syntax.escape(message);
    }

    @Override
    public String toString() {
        return getMessage();
    }
}
