package spola.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Header fields: immutable, in the order they were given, names compared without regard to
 * ASCII case, and a name may occur more than once. Request headers come from the parser;
 * response headers are built with {@link #of} and {@link #with}.
 *
 * <p>Every name is a {@code token} (RFC 9110 §5.6.2) and every value holds only HTAB, SP,
 * visible ASCII and obs-text (U+0080–U+00FF) without leading or trailing whitespace (RFC 9110
 * §5.5). CR, LF, NUL and the other control characters are refused, so a header can never split
 * a response.
 */
public final class Headers {

    /**
     * One header field line.
     *
     * @param name  the field name as given (case kept)
     * @param value the field value, without leading and trailing whitespace
     */
    public record Field(String name, String value) {

        /**
         * Checks the name and the value.
         *
         * @throws NullPointerException     if the name or the value is {@code null}
         * @throws IllegalArgumentException if the name is not a token or the value holds a
         *                                  character that is not allowed
         */
        public Field {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            if (!Syntax.isToken(name)) {
                throw new IllegalArgumentException("header name is not a token (RFC 9110 5.6.2): \""
                        + Syntax.escape(name) + "\"");
            }
            for (int i = 0; i < value.length(); i++) {
                if (!Syntax.isFieldValueChar(value.charAt(i))) {
                    throw new IllegalArgumentException("header " + name + ": value holds a character that is not"
                            + " allowed (RFC 9110 5.5): \"" + Syntax.escape(value) + "\"");
                }
            }
            if (!value.isEmpty() && (Syntax.isWhitespace(value.charAt(0))
                    || Syntax.isWhitespace(value.charAt(value.length() - 1)))) {
                throw new IllegalArgumentException("header " + name
                        + ": value has leading or trailing whitespace (RFC 9110 5.5)");
            }
        }
    }

    /** No headers. */
    public static final Headers EMPTY = new Headers(List.of());

    private final List<Field> fields;

    private Headers(List<Field> fields) {
        this.fields = fields;
    }

    /**
     * Headers from names and values in turn: {@code Headers.of("Content-Type", "text/plain",
     * "Cache-Control", "no-store")}.
     *
     * @param namesAndValues name, value, name, value, ...
     * @return the headers
     * @throws IllegalArgumentException if the count is odd or a name or value is not allowed
     * @throws NullPointerException     if an element is {@code null}
     */
    public static Headers of(String... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("names and values must come in pairs, got "
                    + namesAndValues.length + " strings");
        }
        var list = new ArrayList<Field>(namesAndValues.length / 2);
        for (int i = 0; i < namesAndValues.length; i += 2) {
            list.add(new Field(namesAndValues[i], namesAndValues[i + 1]));
        }
        return new Headers(List.copyOf(list));
    }

    /**
     * Headers holding exactly these fields, in this order.
     *
     * @param fields the fields
     * @return the headers
     */
    public static Headers of(List<Field> fields) {
        return new Headers(List.copyOf(fields));
    }

    /**
     * A copy with one more field at the end (an existing field with the same name stays).
     *
     * @param name  the field name, a token
     * @param value the field value
     * @return the copy
     * @throws IllegalArgumentException if the name or the value is not allowed
     */
    public Headers with(String name, String value) {
        var list = new ArrayList<Field>(fields.size() + 1);
        list.addAll(fields);
        list.add(new Field(name, value));
        return new Headers(List.copyOf(list));
    }

    /**
     * The value of the first field named {@code name}.
     *
     * @param name the field name, any case
     * @return the value, or empty if there is no such field
     */
    public Optional<String> first(String name) {
        for (var field : fields) {
            if (field.name().equalsIgnoreCase(name)) {
                return Optional.of(field.value());
            }
        }
        return Optional.empty();
    }

    /**
     * The values of every field named {@code name}, in order. A field whose value is a
     * comma-separated list is one element; it is not split.
     *
     * @param name the field name, any case
     * @return the values, empty if there is no such field
     */
    public List<String> all(String name) {
        var values = new ArrayList<String>();
        for (var field : fields) {
            if (field.name().equalsIgnoreCase(name)) {
                values.add(field.value());
            }
        }
        return List.copyOf(values);
    }

    /**
     * Whether a field named {@code name} is present.
     *
     * @param name the field name, any case
     * @return {@code true} if there is at least one such field
     */
    public boolean contains(String name) {
        return first(name).isPresent();
    }

    /**
     * Every field, in order.
     *
     * @return the fields (immutable)
     */
    public List<Field> fields() {
        return fields;
    }

    /**
     * The number of field lines.
     *
     * @return the count
     */
    public int size() {
        return fields.size();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Headers headers && headers.fields.equals(fields);
    }

    @Override
    public int hashCode() {
        return fields.hashCode();
    }

    /** The fields as {@code name: value} pairs, escaped for logs. */
    @Override
    public String toString() {
        var out = new StringBuilder("Headers[");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(fields.get(i).name()).append(": ").append(Syntax.escape(fields.get(i).value()));
        }
        return out.append(']').toString();
    }
}
