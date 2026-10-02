package spola.util;

import java.util.Arrays;

/**
 * Formats a {@link Log} message: SLF4J-style {@code {}} placeholders, rendering of the
 * arguments and detection of a trailing {@link Throwable}. The rules are documented on
 * {@link Log}; this class only implements them. Stateless and thread-safe.
 */
final class MessageFormatter {

    private MessageFormatter() {
    }

    /** A formatted message and the throwable to log with it ({@code null} for none). */
    record Formatted(String message, Throwable thrown) {
    }

    /**
     * Formats {@code pattern} with {@code args}.
     *
     * @param pattern the pattern; {@code null} is formatted as the text {@code null}
     * @param args the arguments; {@code null} means none
     * @return the message and the unconsumed trailing throwable, if any
     */
    static Formatted format(String pattern, Object[] args) {
        var text = pattern == null ? "null" : pattern;
        int count = args == null ? 0 : args.length;
        var out = new StringBuilder(text.length() + 16 * count);
        int next = 0;
        int from = 0;
        while (from < text.length()) {
            int brace = text.indexOf("{}", from);
            if (brace < 0) {
                out.append(text, from, text.length());
                break;
            }
            boolean escaped = brace > from && text.charAt(brace - 1) == '\\';
            boolean escapedBackslash = escaped && brace - 1 > from && text.charAt(brace - 2) == '\\';
            if (escaped && !escapedBackslash) {
                // \{} is a literal {}: drop the backslash, consume no argument.
                out.append(text, from, brace - 1).append("{}");
                from = brace + 2;
                continue;
            }
            // \\{} is one literal backslash followed by a placeholder.
            out.append(text, from, escapedBackslash ? brace - 1 : brace);
            if (next < count) {
                out.append(render(args[next++]));
            } else {
                out.append("{}");
            }
            from = brace + 2;
        }
        Throwable thrown = null;
        if (next < count && args[count - 1] instanceof Throwable last) {
            thrown = last;
        }
        return new Formatted(out.toString(), thrown);
    }

    /**
     * Renders one argument: {@code null} as {@code null}, arrays with
     * {@link Arrays#deepToString}/{@link Arrays#toString}, {@link Log.Deferred} by calling its
     * supplier, anything else with {@code toString()}. Never throws for a failing
     * {@code toString()} or supplier; a marker with the exception's class name is returned
     * instead.
     *
     * @param value the argument
     * @return its text
     */
    static String render(Object value) {
        if (value instanceof Log.Deferred deferred) {
            try {
                // Also inside the try: a supplier returning a Deferred that returns itself would
                // otherwise recurse until a StackOverflowError escapes from the logging call.
                return render(deferred.supplier().get());
            } catch (Throwable failure) {
                return marker("supplier", failure);
            }
        }
        try {
            return switch (value) {
                case null -> "null";
                case Object[] array -> Arrays.deepToString(array);
                case boolean[] array -> Arrays.toString(array);
                case byte[] array -> Arrays.toString(array);
                case char[] array -> Arrays.toString(array);
                case short[] array -> Arrays.toString(array);
                case int[] array -> Arrays.toString(array);
                case long[] array -> Arrays.toString(array);
                case float[] array -> Arrays.toString(array);
                case double[] array -> Arrays.toString(array);
                default -> String.valueOf(value.toString());
            };
        } catch (Throwable failure) {
            return marker("toString()", failure);
        }
    }

    /**
     * The text written instead of a value whose {@code toString()} (or supplier) threw. Only
     * the class name is shown: the exception's message could contain the value itself. A fatal
     * failure is rethrown instead ({@link Failures#rethrowIfFatal}).
     */
    static String marker(String what, Throwable failure) {
        Failures.rethrowIfFatal(failure);
        return "[" + what + " threw " + failure.getClass().getName() + "]";
    }
}
