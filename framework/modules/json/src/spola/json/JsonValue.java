package spola.json;

/**
 * A JSON value (RFC 8259 §3): one of six immutable types with value semantics. Because the
 * hierarchy is sealed, a {@code switch} with patterns covers every case without a
 * {@code default}:
 *
 * <pre>{@code
 * String describe(JsonValue value) {
 *     return switch (value) {
 *         case JsonObject o -> "object with " + o.size() + " members";
 *         case JsonArray a -> "array with " + a.size() + " elements";
 *         case JsonString s -> "string " + s.value();
 *         case JsonNumber n -> "number " + n.text();
 *         case JsonBoolean b -> "boolean " + b.value();
 *         case JsonNull n -> "null";
 *     };
 * }
 * }</pre>
 *
 * <p>Every value is deeply immutable and can be shared between threads without
 * synchronization. {@code equals} and {@code hashCode} compare content: two objects are equal
 * when they have the same members (in any order), two numbers when they are numerically equal
 * ({@code 1}, {@code 1.0} and {@code 10e-1} are equal). {@code toString} returns the compact
 * JSON text ({@link Json#write(JsonValue)}). None of these methods recurse, so trees of any
 * depth can be compared, hashed and written.
 */
public sealed interface JsonValue permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull {
}
