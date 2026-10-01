package spola.test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** How values are shown in failure messages and test names. */
final class Values {

    private Values() {
    }

    /**
     * A readable form of {@code value}: strings in double quotes and characters in single
     * quotes (with escapes), arrays and collections with their elements (described the same
     * way), everything else with {@link String#valueOf(Object)}. A collection that contains
     * itself, directly or through other collections, shows {@code (cycle)} where it repeats.
     */
    static String describe(Object value) {
        return describe(value, false, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * {@link #describe} with the class of every non-null element (and of {@code value} itself
     * if it is not a collection), for values that read the same although they differ.
     */
    static String describeTyped(Object value) {
        return describe(value, true, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static String describe(Object value, boolean typed, Set<Object> enclosing) {
        var plain = switch (value) {
            case null -> "null";
            case CharSequence text -> '"' + escape(text.toString(), '"') + '"';
            case Character c -> "'" + escape(c.toString(), '\'') + "'";
            case Collection<?> collection -> {
                if (!enclosing.add(collection)) {
                    yield "(cycle)";
                }
                var shown = collection.stream()
                        .map(element -> describe(element, typed, enclosing))
                        .collect(Collectors.joining(", ", "[", "]"));
                enclosing.remove(collection);
                yield shown;
            }
            case Object array when array.getClass().isArray() -> {
                var all = Arrays.deepToString(new Object[] {array});
                yield all.substring(1, all.length() - 1);
            }
            default -> String.valueOf(value);
        };
        return typed && value != null && !(value instanceof Collection<?>) ? withClass(plain, value) : plain;
    }

    /**
     * {@link #describe} of both values; when they read the same although the values differ
     * (for example {@code 1} and {@code 1L}, or {@code [1]} and {@code [1L]}), each value and
     * element is followed by its class.
     */
    static String[] describeBoth(Object expected, Object actual) {
        var e = describe(expected);
        var a = describe(actual);
        if (e.equals(a) && !Objects.deepEquals(expected, actual)) {
            e = describeTyped(expected);
            a = describeTyped(actual);
        }
        return new String[] {e, a};
    }

    private static String withClass(String description, Object value) {
        return description + " (" + value.getClass().getName() + ")";
    }

    /** Control characters as escapes ({@code \n}, {@code \t}, {@code \u0000}, ...). */
    static String escapeControls(String text) {
        return escape(text, '\0');
    }

    private static String escape(String text, char quote) {
        var out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\\' -> out.append(quote == '\0' ? "\\" : "\\\\");
                default -> {
                    if (quote != '\0' && c == quote) {
                        out.append('\\').append(c);
                    } else if (Character.isISOControl(c)) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
