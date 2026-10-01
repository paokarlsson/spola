package spola.di;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Identifies a component in a {@link Container}: its type and an optional qualifier.
 *
 * <p>Several bindings of the same type are told apart by qualifiers. A qualifier is a plain
 * value with value-based {@code equals} and {@code hashCode}, typically an enum constant, a
 * record or a string, never an annotation:
 *
 * <pre>{@code
 * enum Region { EU, US }
 * Key<PaymentGateway> eu = Key.of(PaymentGateway.class, Region.EU);
 * }</pre>
 *
 * <p>Two keys are equal when type and qualifier are equal. {@code Key.of(T.class)} and
 * {@code Key.of(T.class, q)} are different keys; there is no fallback from one to the other.
 *
 * @param type      the component type; not a primitive type
 * @param qualifier the qualifier, or {@code null} for an unqualified key; not an array (arrays
 *                  compare by identity)
 * @param <T>       the component type
 */
public record Key<T>(Class<T> type, Object qualifier) {

    /**
     * Checks the components.
     *
     * @throws NullPointerException     if {@code type} is null
     * @throws IllegalArgumentException if {@code type} is primitive or {@code qualifier} is an
     *                                  array
     */
    public Key {
        Objects.requireNonNull(type, "type");
        if (type.isPrimitive()) {
            throw new IllegalArgumentException("a key cannot have a primitive type (" + type
                    + "); use its wrapper class");
        }
        if (qualifier != null && qualifier.getClass().isArray()) {
            throw new IllegalArgumentException("a qualifier cannot be an array (arrays compare by identity): "
                    + qualifier.getClass().getTypeName());
        }
    }

    /**
     * The unqualified key of a type.
     *
     * @param type the component type
     * @param <T>  the component type
     * @return the key
     */
    public static <T> Key<T> of(Class<T> type) {
        return new Key<>(type, null);
    }

    /**
     * A qualified key.
     *
     * @param type      the component type
     * @param qualifier the qualifier, not null
     * @param <T>       the component type
     * @return the key
     */
    public static <T> Key<T> of(Class<T> type, Object qualifier) {
        return new Key<>(type, Objects.requireNonNull(qualifier, "qualifier"));
    }

    /**
     * The type's canonical name (its binary name for local and anonymous classes), followed by
     * the qualifier in brackets when there is one; strings are quoted.
     *
     * @return for example {@code shop.PaymentGateway[EU]}
     */
    @Override
    public String toString() {
        var name = type.getCanonicalName() != null ? type.getCanonicalName() : type.getName();
        return switch (qualifier) {
            case null -> name;
            case String s -> name + "[\"" + s + "\"]";
            default -> name + "[" + qualifier + "]";
        };
    }

    /** {@code A -> B -> C}. */
    static String chain(List<? extends Key<?>> path) {
        return path.stream().map(Key::toString).collect(Collectors.joining(" -> "));
    }
}
