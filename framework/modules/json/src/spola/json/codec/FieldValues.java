package spola.json.codec;

import java.io.Serial;
import java.util.IdentityHashMap;
import java.util.Objects;

/**
 * The decoded values of an object's fields, given to the constructor of the general form
 * {@link JsonCodec#object(java.util.List, java.util.function.Function)}. Each field is the key
 * of its own value, so {@code values.get(NAME)} has the field's type:
 *
 * <pre>{@code
 * static final Field<Big, String> A = field("a", STRING, Big::a);
 * static final Field<Big, Integer> B = field("b", INT, Big::b);
 * static final ObjectCodec<Big> BIG = JsonCodec.object(List.of(A, B, ...),
 *         values -> new Big(values.get(A), values.get(B), ...));
 * }</pre>
 *
 * <p>Only valid during the constructor call it was given to; every field has decoded without a
 * problem by then.
 *
 * @param <R> the type being constructed
 */
public final class FieldValues<R> {

    private final IdentityHashMap<Field<R, ?>, Integer> index;
    private final Object[] values;

    FieldValues(IdentityHashMap<Field<R, ?>, Integer> index, Object[] values) {
        this.index = index;
        this.values = values;
    }

    /**
     * The decoded value of a field.
     *
     * @param <T>   the field's type
     * @param field one of the fields of the codec (the same instance)
     * @return the value: decoded, or the field's default ({@link Field#orElse(Object)}) or
     *         {@code Optional.empty()} when the member was missing
     * @throws IllegalArgumentException if {@code field} is not one of the codec's fields; this
     *                                  is a programming error and is not turned into a problem
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Field<R, T> field) {
        Integer at = index.get(Objects.requireNonNull(field, "field"));
        if (at == null) {
            throw new NotAField(field);
        }
        // the value at this index was decoded by this very field's codec, so it is a T
        return (T) values[at];
    }

    /** {@link #get} was given a field the codec does not have. */
    static final class NotAField extends IllegalArgumentException {

        @Serial
        private static final long serialVersionUID = 1L;

        NotAField(Field<?, ?> field) {
            super(field + " is not one of the fields of this object codec");
        }
    }
}
