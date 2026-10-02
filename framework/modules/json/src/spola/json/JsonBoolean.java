package spola.json;

/**
 * A JSON {@code true} or {@code false} (RFC 8259 §3).
 *
 * @param value the boolean
 */
public record JsonBoolean(boolean value) implements JsonValue {

    /** {@code true}. */
    public static final JsonBoolean TRUE = new JsonBoolean(true);

    /** {@code false}. */
    public static final JsonBoolean FALSE = new JsonBoolean(false);

    /**
     * The JSON boolean for {@code value}.
     *
     * @param value the boolean
     * @return {@link #TRUE} or {@link #FALSE}
     */
    public static JsonBoolean of(boolean value) {
        return value ? TRUE : FALSE;
    }

    /**
     * The literal.
     *
     * @return {@code true} or {@code false}
     */
    @Override
    public String toString() {
        return Boolean.toString(value);
    }
}
