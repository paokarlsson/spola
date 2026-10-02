package spola.json;

/**
 * The parser's limits, as a value. Input beyond a limit is rejected with a
 * {@link JsonParseException} that names the limit (such as {@code maxDepth}); input exactly at a
 * limit is accepted. Start from {@link #DEFAULT} and change what you need:
 *
 * <pre>{@code
 * var limits = JsonLimits.DEFAULT.withMaxInputSize(64 * 1024).withMaxDepth(32);
 * JsonValue value = Json.parse(body, limits);
 * }</pre>
 *
 * @param maxDepth        the deepest nesting of arrays and objects; {@code []} has depth 1,
 *                        {@code [[]]} depth 2, a scalar depth 0. Default 512.
 * @param maxStringLength the longest string or member name after decoding escapes, in UTF-16
 *                        code units ({@link String#length()}). Default 1 048 576.
 * @param maxNumberLength the longest number, in characters of its text. Default 1 000.
 * @param maxEntries      the most members in one object or elements in one array. Default
 *                        100 000.
 * @param maxInputSize    the largest input, in bytes for {@code byte[]} and {@code InputStream}
 *                        and in UTF-16 code units for {@code String} and {@code Reader}, a byte
 *                        order mark included. A stream is never read further than one unit past
 *                        this limit. Default 4 194 304 (4 MiB).
 */
public record JsonLimits(int maxDepth, int maxStringLength, int maxNumberLength, int maxEntries,
                         long maxInputSize) {

    /** The defaults; ARCHITECTURE.md explains each value. */
    public static final JsonLimits DEFAULT = new JsonLimits(512, 1 << 20, 1_000, 100_000, 4L << 20);

    /**
     * Checks the limits.
     *
     * @throws IllegalArgumentException if a limit is less than 1
     */
    public JsonLimits {
        positive("maxDepth", maxDepth);
        positive("maxStringLength", maxStringLength);
        positive("maxNumberLength", maxNumberLength);
        positive("maxEntries", maxEntries);
        positive("maxInputSize", maxInputSize);
    }

    private static void positive(String name, long value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be at least 1, was " + value);
        }
    }

    /**
     * A copy with another depth limit.
     *
     * @param maxDepth the deepest nesting of arrays and objects
     * @return the copy
     */
    public JsonLimits withMaxDepth(int maxDepth) {
        return new JsonLimits(maxDepth, maxStringLength, maxNumberLength, maxEntries, maxInputSize);
    }

    /**
     * A copy with another string length limit.
     *
     * @param maxStringLength the longest string or member name, in UTF-16 code units
     * @return the copy
     */
    public JsonLimits withMaxStringLength(int maxStringLength) {
        return new JsonLimits(maxDepth, maxStringLength, maxNumberLength, maxEntries, maxInputSize);
    }

    /**
     * A copy with another number length limit.
     *
     * @param maxNumberLength the longest number, in characters
     * @return the copy
     */
    public JsonLimits withMaxNumberLength(int maxNumberLength) {
        return new JsonLimits(maxDepth, maxStringLength, maxNumberLength, maxEntries, maxInputSize);
    }

    /**
     * A copy with another limit on members per object and elements per array.
     *
     * @param maxEntries the most members or elements in one container
     * @return the copy
     */
    public JsonLimits withMaxEntries(int maxEntries) {
        return new JsonLimits(maxDepth, maxStringLength, maxNumberLength, maxEntries, maxInputSize);
    }

    /**
     * A copy with another input size limit.
     *
     * @param maxInputSize the largest input, in bytes or UTF-16 code units
     * @return the copy
     */
    public JsonLimits withMaxInputSize(long maxInputSize) {
        return new JsonLimits(maxDepth, maxStringLength, maxNumberLength, maxEntries, maxInputSize);
    }
}
