package spola.web;

import java.util.Objects;

/**
 * A typed key for a request attribute, compared by identity: two keys with the same name are
 * different keys. A filter puts a value with {@link WebRequest#withAttribute} and a handler
 * reads it with {@link WebRequest#attribute}, without string keys or casts:
 *
 * <pre>{@code
 * static final AttributeKey<User> USER = AttributeKey.of("user", User.class);
 * }</pre>
 *
 * @param <T> the value type
 */
public final class AttributeKey<T> {

    private final String name;
    private final Class<T> type;

    private AttributeKey(String name, Class<T> type) {
        this.name = name;
        this.type = type;
    }

    /**
     * A new key.
     *
     * @param name a name for messages and {@link #toString()}
     * @param type the value type; values are checked against it
     * @param <T>  the value type
     * @return the key, different from every other key
     * @throws IllegalArgumentException if {@code type} is a primitive type (use the wrapper)
     */
    public static <T> AttributeKey<T> of(String name, Class<T> type) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (type.isPrimitive()) {
            throw new IllegalArgumentException("attribute type " + type.getName() + " is primitive; use its wrapper");
        }
        return new AttributeKey<>(name, type);
    }

    /**
     * The name given to {@link #of}.
     *
     * @return the name
     */
    public String name() {
        return name;
    }

    /**
     * The value type.
     *
     * @return the type
     */
    public Class<T> type() {
        return type;
    }

    /** {@code AttributeKey[name: type]}. */
    @Override
    public String toString() {
        return "AttributeKey[" + name + ": " + type.getName() + "]";
    }
}
