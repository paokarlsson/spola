package spola.json;

import java.io.Serial;

/**
 * A reader on the tree ({@link JsonObject#string(String)}, {@link JsonArray#object(int)}, ...)
 * found a missing member, an index out of bounds or a value of another type. The message names
 * the path, the expected type and what was found:
 *
 * <pre>
 * $.items[2].sku: expected string but found number
 * $.address.city: expected string but the member is missing
 * </pre>
 *
 * <p>The path starts at {@code $}, the value the application parsed or built; see
 * {@link JsonObject#path()}. Characters outside printable ASCII in member names are escaped, so
 * the message is safe to log.
 */
public final class JsonAccessException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** {@link #actual()} when an object has no member with the name. */
    public static final String MISSING = "missing";

    /** {@link #actual()} when an array has no element at the index. */
    public static final String OUT_OF_BOUNDS = "out of bounds";

    private final String path;
    private final String expected;
    private final String actual;

    JsonAccessException(String path, String expected, String actual, String detail) {
        super(path + ": expected " + expected + " but " + detail);
        this.path = path;
        this.expected = expected;
        this.actual = actual;
    }

    static JsonAccessException wrongType(String path, String expected, JsonValue actual) {
        var type = Text.typeName(actual);
        return new JsonAccessException(path, expected, type, "found " + type);
    }

    static JsonAccessException missing(String path, String expected) {
        return new JsonAccessException(path, expected, MISSING, "the member is missing");
    }

    static JsonAccessException outOfBounds(String path, String expected, int size) {
        return new JsonAccessException(path, expected, OUT_OF_BOUNDS,
                "the index is out of bounds (the array has " + size + " element" + (size == 1 ? ")" : "s)"));
    }

    /**
     * Where the reader looked, such as {@code $.items[2].sku}.
     *
     * @return the path
     */
    public String path() {
        return path;
    }

    /**
     * The type the reader expected: {@code object}, {@code array}, {@code string},
     * {@code number}, {@code boolean} or {@code value} (any type).
     *
     * @return the expected type
     */
    public String expected() {
        return expected;
    }

    /**
     * What the reader found: a type name as in {@link #expected()}, {@link #MISSING} or
     * {@link #OUT_OF_BOUNDS}.
     *
     * @return what was found
     */
    public String actual() {
        return actual;
    }
}
