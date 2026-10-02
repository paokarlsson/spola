package spola.json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * A JSON object (RFC 8259 §4): members with unique names, in the order they were parsed or
 * added. The order is kept when the object is written; it is not part of equality (two objects
 * with the same members in another order are equal, as JSON objects are unordered).
 *
 * <p>Build one with {@link #of()}, {@link #of(String, JsonValue)} (up to five members) or
 * {@link #builder()}. A name may appear only once: the parser rejects duplicates and so do the
 * factories.
 *
 * <h2>Readers</h2>
 * The readers ({@link #string(String)}, {@link #number(String)}, {@link #bool(String)},
 * {@link #object(String)}, {@link #array(String)}, {@link #get(String)}) return the member as
 * the expected type and otherwise throw {@link JsonAccessException} with the member's path,
 * the expected type and what was found. The object and array they return remember the path
 * they were reached by, so a chain of readers reports the whole path:
 *
 * <pre>{@code
 * JsonObject order = Json.parse(text) instanceof JsonObject o ? o : ...;
 * String sku = order.array("items").object(2).string("sku");
 * // fails with "$.items[2].sku: expected string but found number"
 * }</pre>
 *
 * <p>The path is not part of the value: it is ignored by {@code equals}, {@code hashCode} and
 * the writer, and an object obtained any other way (parsed, built, or taken from
 * {@link #members()}) has the path {@code $}.
 */
public final class JsonObject implements JsonValue {

    private static final JsonObject EMPTY = new JsonObject(new LinkedHashMap<>());

    private final SequencedMap<String, JsonValue> members;
    private final int hash;
    private final String path;

    /** Copies {@code members}, in order, into a map of exactly their size. */
    private JsonObject(SequencedMap<String, JsonValue> members) {
        this(Collections.unmodifiableSequencedMap(copy(members)), hashOf(members), Paths.ROOT);
    }

    private static LinkedHashMap<String, JsonValue> copy(SequencedMap<String, JsonValue> members) {
        var copy = LinkedHashMap.<String, JsonValue>newLinkedHashMap(members.size());
        copy.putAll(members);
        return copy;
    }

    private JsonObject(SequencedMap<String, JsonValue> members, int hash, String path) {
        this.members = members;
        this.hash = hash;
        this.path = path;
    }

    /** An object built from parsed members; shares one instance for every empty object. */
    static JsonObject parsed(LinkedHashMap<String, JsonValue> members) {
        return members.isEmpty() ? EMPTY : new JsonObject(members);
    }

    private static int hashOf(SequencedMap<String, JsonValue> members) {
        int hash = 0;
        for (var member : members.entrySet()) {
            // the hash of Map: the children's hash codes are cached, so this does not recurse
            hash += member.getKey().hashCode() ^ member.getValue().hashCode();
        }
        return hash;
    }

    /**
     * The empty object.
     *
     * @return {@code {}}
     */
    public static JsonObject of() {
        return EMPTY;
    }

    /**
     * An object with one member.
     *
     * @param name  the name
     * @param value the value
     * @return the object
     */
    public static JsonObject of(String name, JsonValue value) {
        return builder().put(name, value).build();
    }

    /**
     * An object with two members, in this order.
     *
     * @param name1  the first name
     * @param value1 the first value
     * @param name2  the second name
     * @param value2 the second value
     * @return the object
     * @throws IllegalArgumentException if two names are equal
     */
    public static JsonObject of(String name1, JsonValue value1, String name2, JsonValue value2) {
        return builder().put(name1, value1).put(name2, value2).build();
    }

    /**
     * An object with three members, in this order.
     *
     * @param name1  the first name
     * @param value1 the first value
     * @param name2  the second name
     * @param value2 the second value
     * @param name3  the third name
     * @param value3 the third value
     * @return the object
     * @throws IllegalArgumentException if two names are equal
     */
    public static JsonObject of(String name1, JsonValue value1, String name2, JsonValue value2,
                                String name3, JsonValue value3) {
        return builder().put(name1, value1).put(name2, value2).put(name3, value3).build();
    }

    /**
     * An object with four members, in this order.
     *
     * @param name1  the first name
     * @param value1 the first value
     * @param name2  the second name
     * @param value2 the second value
     * @param name3  the third name
     * @param value3 the third value
     * @param name4  the fourth name
     * @param value4 the fourth value
     * @return the object
     * @throws IllegalArgumentException if two names are equal
     */
    public static JsonObject of(String name1, JsonValue value1, String name2, JsonValue value2,
                                String name3, JsonValue value3, String name4, JsonValue value4) {
        return builder().put(name1, value1).put(name2, value2).put(name3, value3).put(name4, value4).build();
    }

    /**
     * An object with five members, in this order. For more, use {@link #builder()}.
     *
     * @param name1  the first name
     * @param value1 the first value
     * @param name2  the second name
     * @param value2 the second value
     * @param name3  the third name
     * @param value3 the third value
     * @param name4  the fourth name
     * @param value4 the fourth value
     * @param name5  the fifth name
     * @param value5 the fifth value
     * @return the object
     * @throws IllegalArgumentException if two names are equal
     */
    public static JsonObject of(String name1, JsonValue value1, String name2, JsonValue value2,
                                String name3, JsonValue value3, String name4, JsonValue value4,
                                String name5, JsonValue value5) {
        return builder().put(name1, value1).put(name2, value2).put(name3, value3).put(name4, value4)
                .put(name5, value5).build();
    }

    /**
     * A builder that adds members in order.
     *
     * @return an empty builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The members in order, as an unmodifiable map. The values are the stored values: an object
     * or array taken from here has the path {@code $}.
     *
     * @return the members
     */
    public SequencedMap<String, JsonValue> members() {
        return members;
    }

    /**
     * The number of members.
     *
     * @return the size
     */
    public int size() {
        return members.size();
    }

    /**
     * Whether the object has no members.
     *
     * @return {@code true} for {@code {}}
     */
    public boolean isEmpty() {
        return members.isEmpty();
    }

    /**
     * Whether the object has a member with the name (whatever its value, {@code null} too).
     *
     * @param name the name
     * @return whether the member exists
     */
    public boolean contains(String name) {
        return members.containsKey(name);
    }

    /**
     * The path this object was reached by with the readers, such as {@code $.address}; {@code $}
     * for an object that was parsed, built or obtained any other way. Not part of the value.
     *
     * @return the path
     */
    public String path() {
        return path;
    }

    /**
     * The member's value, of any type ({@code null} included).
     *
     * @param name the name
     * @return the value; an object or array carries its path
     * @throws JsonAccessException if there is no such member
     */
    public JsonValue get(String name) {
        return withPath(required(name, "value"), Paths.member(path, name));
    }

    /**
     * The member's value unless it is missing or {@code null}: for optional members, where an
     * absent member and an explicit {@code null} mean the same.
     *
     * @param name the name
     * @return the value, or empty if the member is missing or {@code null}; an object or array
     *         carries its path
     */
    public Optional<JsonValue> optional(String name) {
        var value = members.get(name);
        if (value == null || value == JsonNull.INSTANCE) {
            return Optional.empty();
        }
        return Optional.of(withPath(value, Paths.member(path, name)));
    }

    /**
     * The member as a string.
     *
     * @param name the name
     * @return the string's value
     * @throws JsonAccessException if the member is missing or not a string
     */
    public String string(String name) {
        return switch (required(name, "string")) {
            case JsonString s -> s.value();
            case JsonValue other -> throw JsonAccessException.wrongType(Paths.member(path, name), "string", other);
        };
    }

    /**
     * The member as a number.
     *
     * @param name the name
     * @return the number
     * @throws JsonAccessException if the member is missing or not a number
     */
    public JsonNumber number(String name) {
        return switch (required(name, "number")) {
            case JsonNumber n -> n;
            case JsonValue other -> throw JsonAccessException.wrongType(Paths.member(path, name), "number", other);
        };
    }

    /**
     * The member as a boolean.
     *
     * @param name the name
     * @return the boolean
     * @throws JsonAccessException if the member is missing or not {@code true}/{@code false}
     */
    public boolean bool(String name) {
        return switch (required(name, "boolean")) {
            case JsonBoolean b -> b.value();
            case JsonValue other -> throw JsonAccessException.wrongType(Paths.member(path, name), "boolean", other);
        };
    }

    /**
     * The member as an object.
     *
     * @param name the name
     * @return the object, with its path ({@code $.name})
     * @throws JsonAccessException if the member is missing or not an object
     */
    public JsonObject object(String name) {
        var memberPath = Paths.member(path, name);
        return switch (required(name, "object")) {
            case JsonObject o -> o.at(memberPath);
            case JsonValue other -> throw JsonAccessException.wrongType(memberPath, "object", other);
        };
    }

    /**
     * The member as an array.
     *
     * @param name the name
     * @return the array, with its path ({@code $.name})
     * @throws JsonAccessException if the member is missing or not an array
     */
    public JsonArray array(String name) {
        var memberPath = Paths.member(path, name);
        return switch (required(name, "array")) {
            case JsonArray a -> a.at(memberPath);
            case JsonValue other -> throw JsonAccessException.wrongType(memberPath, "array", other);
        };
    }

    private JsonValue required(String name, String expected) {
        var value = members.get(Objects.requireNonNull(name, "name"));
        if (value == null) {
            throw JsonAccessException.missing(Paths.member(path, name), expected);
        }
        return value;
    }

    /** The same object reached by another path; shares the members. */
    JsonObject at(String newPath) {
        return newPath.equals(path) ? this : new JsonObject(members, hash, newPath);
    }

    /** {@code value} with {@code path} if it is an object or array; scalars have no path. */
    static JsonValue withPath(JsonValue value, String path) {
        return switch (value) {
            case JsonObject o -> o.at(path);
            case JsonArray a -> a.at(path);
            default -> value;
        };
    }

    /**
     * Equality of content: the same names with equal values, in any order. The path is ignored.
     *
     * @param other another object
     * @return whether {@code other} is an equal JSON object
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof JsonObject object && Equality.equal(this, object);
    }

    /**
     * A hash code consistent with {@link #equals(Object)}, computed once when the object is
     * created.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return hash;
    }

    /**
     * The object as compact JSON.
     *
     * @return {@link Json#write(JsonValue)} of this object
     */
    @Override
    public String toString() {
        return Json.write(this);
    }

    /**
     * Adds members in order and builds an immutable {@link JsonObject}. Not thread-safe; the
     * objects it builds are.
     */
    public static final class Builder {

        private final LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Adds a member.
         *
         * @param name  the name
         * @param value the value
         * @return this builder
         * @throws IllegalArgumentException if the builder already has a member with this name, or
         *                                  the name contains an unpaired surrogate
         * @throws NullPointerException     if {@code name} or {@code value} is {@code null}
         */
        public Builder put(String name, JsonValue value) {
            JsonString.checkText(name, "member name");
            Objects.requireNonNull(value, "value");
            if (members.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException("duplicate member name " + Text.excerpt(name));
            }
            return this;
        }

        /**
         * Adds a string member.
         *
         * @param name  the name
         * @param value the string
         * @return this builder
         * @throws IllegalArgumentException as {@link #put(String, JsonValue)}, or if the value
         *                                  contains an unpaired surrogate
         */
        public Builder put(String name, String value) {
            return put(name, JsonString.of(value));
        }

        /**
         * Adds a number member.
         *
         * @param name  the name
         * @param value the number
         * @return this builder
         * @throws IllegalArgumentException as {@link #put(String, JsonValue)}
         */
        public Builder put(String name, long value) {
            return put(name, JsonNumber.of(value));
        }

        /**
         * Adds a boolean member.
         *
         * @param name  the name
         * @param value the boolean
         * @return this builder
         * @throws IllegalArgumentException as {@link #put(String, JsonValue)}
         */
        public Builder put(String name, boolean value) {
            return put(name, JsonBoolean.of(value));
        }

        /**
         * Builds the object. The builder can be used further; later changes do not affect the
         * object.
         *
         * @return the object with the members added so far, in order
         */
        public JsonObject build() {
            return members.isEmpty() ? EMPTY : new JsonObject(members);
        }
    }
}
