/**
 * Spola's JSON: an immutable tree model, a strict RFC 8259 parser with limits and a writer.
 * It replaces the part of Jackson that Spring Boot uses to read and write JSON trees
 * ({@code ObjectMapper.readTree}, {@code JsonNode}, {@code ObjectNode}).
 *
 * <ul>
 *   <li>{@link spola.json.JsonValue}: a sealed hierarchy ({@link spola.json.JsonObject},
 *       {@link spola.json.JsonArray}, {@link spola.json.JsonString},
 *       {@link spola.json.JsonNumber}, {@link spola.json.JsonBoolean},
 *       {@link spola.json.JsonNull}) of immutable values with value semantics.</li>
 *   <li>{@link spola.json.Json}: {@code parse} from a {@code String}, {@code byte[]} (UTF-8),
 *       {@code InputStream} (UTF-8) or {@code Reader}, and {@code write}/{@code writePretty}
 *       to a {@code String}, an {@code Appendable} or an {@code OutputStream} (UTF-8).</li>
 *   <li>{@link spola.json.JsonLimits}: the parser's limits on depth, string length, number
 *       length, entries per container and input size, as a value.</li>
 *   <li>{@link spola.json.JsonParseException}: every rejected input, with line, column and
 *       offset; {@link spola.json.JsonAccessException}: a reader on the tree found a missing
 *       member or another type, with the path.</li>
 *   <li>{@link spola.json.codec.JsonCodec} (package {@code spola.json.codec}): explicit
 *       mapping between Java values (records, enums, collections, {@code java.time}, value
 *       types, sealed hierarchies) and the tree, with codecs built from method references and
 *       lambdas, and {@link spola.json.codec.JsonMappingException} listing every problem of a
 *       document with its path. The tree model does not depend on this package.</li>
 * </ul>
 *
 * <p>The module has no dependencies besides {@code java.base}, uses no reflection and keeps no
 * shared mutable state.
 */
module spola.json {
    exports spola.json;
    exports spola.json.codec;
}
