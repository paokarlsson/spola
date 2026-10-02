package spola.util;

import java.util.Objects;

/**
 * Builds a {@code toString()} in the same form as a record's, for records and classes that
 * hold secrets. Replaces Lombok's {@code @ToString} with {@code @ToString.Exclude}:
 *
 * {@snippet :
 * record User(String name, String password) {
 *     @Override
 *     public String toString() {
 *         return ToString.of("User").add("name", name).secret("password", password).toString();
 *     }
 * }
 * // new User("ada", "hunter2").toString() is "User[name=ada, password=***]"
 * }
 *
 * <h2>Form</h2>
 * {@code Name[field=value, other=value]}, exactly like a record: the fields in the order they
 * are added, separated by {@code ", "}, each value written with {@link String#valueOf(Object)}
 * (so {@code null} is {@code null} and arrays are not expanded, as in a record). No fields
 * gives {@code Name[]}.
 *
 * <h2>Secrets</h2>
 * {@link #secret(String, Object)} writes {@value #MASK} for a value that is not
 * {@code null}: never the value, never its length, and its {@code toString()} is not called.
 * A {@code null} secret is written as {@code null}, so a missing password can be told apart
 * from a set one when debugging (this reveals only whether it is set).
 *
 * <p>A {@code ToString} is a small mutable builder meant to be created, filled and turned into
 * a string in one expression; it is not thread-safe.
 */
public final class ToString {

    /** What a non-null secret value is written as. */
    public static final String MASK = "***";

    private final StringBuilder text;
    private boolean first = true;

    private ToString(String name) {
        this.text = new StringBuilder(name).append('[');
    }

    /**
     * Starts a {@code toString} for a type.
     *
     * @param name the type name, as a record writes it (its simple name)
     * @return the builder
     */
    public static ToString of(String name) {
        return new ToString(Objects.requireNonNull(name, "name"));
    }

    /**
     * Adds a field that is written as it is.
     *
     * @param name the field name
     * @param value the value; written with {@link String#valueOf(Object)}
     * @return this builder
     */
    public ToString add(String name, Object value) {
        field(name).append(value);
        return this;
    }

    /**
     * Adds a field whose value is hidden: {@value #MASK} if it is not {@code null}, otherwise
     * {@code null}.
     *
     * @param name the field name
     * @param value the secret value; its {@code toString()} is never called
     * @return this builder
     */
    public ToString secret(String name, Object value) {
        field(name).append(value == null ? "null" : MASK);
        return this;
    }

    private StringBuilder field(String name) {
        Objects.requireNonNull(name, "name");
        if (!first) {
            text.append(", ");
        }
        first = false;
        return text.append(name).append('=');
    }

    /**
     * The finished text.
     *
     * @return {@code Name[field=value, ...]}
     */
    @Override
    public String toString() {
        return text + "]";
    }
}
