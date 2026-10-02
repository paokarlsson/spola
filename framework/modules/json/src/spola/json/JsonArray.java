package spola.json;

import java.util.List;

/**
 * A JSON array (RFC 8259 §5): elements in order.
 *
 * <p>Build one with {@link #of(JsonValue...)} or {@link #copyOf(List)}. The readers
 * ({@link #get(int)}, {@link #string(int)}, {@link #number(int)}, {@link #bool(int)},
 * {@link #object(int)}, {@link #array(int)}) return the element as the expected type and
 * otherwise throw {@link JsonAccessException} with the element's path ({@code $.items[2]});
 * see {@link JsonObject} for how paths work.
 */
public final class JsonArray implements JsonValue {

    private static final JsonArray EMPTY = new JsonArray(List.of());

    private final List<JsonValue> values;
    private final int hash;
    private final String path;

    /** Copies {@code values} into an immutable list of exactly their size. */
    private JsonArray(List<? extends JsonValue> values) {
        this(List.copyOf(values), Paths.ROOT);
    }

    private JsonArray(List<JsonValue> values, String path) {
        this(values, hashOf(values), path);
    }

    private JsonArray(List<JsonValue> values, int hash, String path) {
        this.values = values;
        this.hash = hash;
        this.path = path;
    }

    /** An array built from parsed elements; shares one instance for every empty array. */
    static JsonArray parsed(List<JsonValue> values) {
        return values.isEmpty() ? EMPTY : new JsonArray(values);
    }

    private static int hashOf(List<JsonValue> values) {
        int hash = 1;
        for (var value : values) {
            // the hash of List: the children's hash codes are cached, so this does not recurse
            hash = 31 * hash + value.hashCode();
        }
        return hash;
    }

    /**
     * An array of the values, in order.
     *
     * @param values the elements
     * @return the array
     * @throws NullPointerException if an element is {@code null}
     */
    public static JsonArray of(JsonValue... values) {
        return copyOf(List.of(values));
    }

    /**
     * An array of the values, in order.
     *
     * @param values the elements, copied
     * @return the array
     * @throws NullPointerException if an element is {@code null}
     */
    public static JsonArray copyOf(List<? extends JsonValue> values) {
        return values.isEmpty() ? EMPTY : new JsonArray(values);
    }

    /**
     * The elements in order, as an unmodifiable list. An object or array taken from here has
     * the path {@code $}.
     *
     * @return the elements
     */
    public List<JsonValue> values() {
        return values;
    }

    /**
     * The number of elements.
     *
     * @return the size
     */
    public int size() {
        return values.size();
    }

    /**
     * Whether the array has no elements.
     *
     * @return {@code true} for {@code []}
     */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * The path this array was reached by with the readers, such as {@code $.items}; {@code $}
     * for an array that was parsed, built or obtained any other way. Not part of the value.
     *
     * @return the path
     */
    public String path() {
        return path;
    }

    /**
     * The element, of any type.
     *
     * @param index the index, from 0
     * @return the element; an object or array carries its path
     * @throws JsonAccessException if the index is out of bounds
     */
    public JsonValue get(int index) {
        return JsonObject.withPath(required(index, "value"), Paths.element(path, index));
    }

    /**
     * The element as a string.
     *
     * @param index the index, from 0
     * @return the string's value
     * @throws JsonAccessException if the index is out of bounds or the element is not a string
     */
    public String string(int index) {
        return switch (required(index, "string")) {
            case JsonString s -> s.value();
            case JsonValue other -> throw JsonAccessException.wrongType(Paths.element(path, index), "string", other);
        };
    }

    /**
     * The element as a number.
     *
     * @param index the index, from 0
     * @return the number
     * @throws JsonAccessException if the index is out of bounds or the element is not a number
     */
    public JsonNumber number(int index) {
        return switch (required(index, "number")) {
            case JsonNumber n -> n;
            case JsonValue other -> throw JsonAccessException.wrongType(Paths.element(path, index), "number", other);
        };
    }

    /**
     * The element as a boolean.
     *
     * @param index the index, from 0
     * @return the boolean
     * @throws JsonAccessException if the index is out of bounds or the element is not
     *                             {@code true}/{@code false}
     */
    public boolean bool(int index) {
        return switch (required(index, "boolean")) {
            case JsonBoolean b -> b.value();
            case JsonValue other -> throw JsonAccessException.wrongType(Paths.element(path, index), "boolean", other);
        };
    }

    /**
     * The element as an object.
     *
     * @param index the index, from 0
     * @return the object, with its path ({@code $.items[2]})
     * @throws JsonAccessException if the index is out of bounds or the element is not an object
     */
    public JsonObject object(int index) {
        var elementPath = Paths.element(path, index);
        return switch (required(index, "object")) {
            case JsonObject o -> o.at(elementPath);
            case JsonValue other -> throw JsonAccessException.wrongType(elementPath, "object", other);
        };
    }

    /**
     * The element as an array.
     *
     * @param index the index, from 0
     * @return the array, with its path ({@code $.items[2]})
     * @throws JsonAccessException if the index is out of bounds or the element is not an array
     */
    public JsonArray array(int index) {
        var elementPath = Paths.element(path, index);
        return switch (required(index, "array")) {
            case JsonArray a -> a.at(elementPath);
            case JsonValue other -> throw JsonAccessException.wrongType(elementPath, "array", other);
        };
    }

    private JsonValue required(int index, String expected) {
        if (index < 0 || index >= values.size()) {
            throw JsonAccessException.outOfBounds(Paths.element(path, index), expected, values.size());
        }
        return values.get(index);
    }

    /** The same array reached by another path; shares the elements. */
    JsonArray at(String newPath) {
        return newPath.equals(path) ? this : new JsonArray(values, hash, newPath);
    }

    /**
     * Equality of content: equal elements in the same order. The path is ignored.
     *
     * @param other another object
     * @return whether {@code other} is an equal JSON array
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof JsonArray array && Equality.equal(this, array);
    }

    /**
     * A hash code consistent with {@link #equals(Object)}, computed once when the array is
     * created.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return hash;
    }

    /**
     * The array as compact JSON.
     *
     * @return {@link Json#write(JsonValue)} of this array
     */
    @Override
    public String toString() {
        return Json.write(this);
    }
}
