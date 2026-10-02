package spola.json;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import spola.test.Suite;

/** The compact and the indented writer, and the escaping of strings. */
final class WriterTests {

    private WriterTests() {
    }

    static final JsonObject TREE = JsonObject.builder()
            .put("name", "Widget")
            .put("price", JsonNumber.of(new java.math.BigDecimal("9.90")))
            .put("tags", JsonArray.of(JsonString.of("a"), JsonString.of("b")))
            .put("stock", JsonObject.of("warehouse", JsonNumber.of(12), "shop", JsonNull.INSTANCE))
            .put("empty", JsonObject.of())
            .put("none", JsonArray.of())
            .put("nested", JsonArray.of(JsonArray.of(JsonNumber.of(1)), JsonObject.of("x", JsonBoolean.TRUE)))
            .put("active", false)
            .build();

    static final String COMPACT = "{\"name\":\"Widget\",\"price\":9.90,\"tags\":[\"a\",\"b\"],"
            + "\"stock\":{\"warehouse\":12,\"shop\":null},\"empty\":{},\"none\":[],"
            + "\"nested\":[[1],{\"x\":true}],\"active\":false}";

    static final String PRETTY = """
            {
              "name": "Widget",
              "price": 9.90,
              "tags": [
                "a",
                "b"
              ],
              "stock": {
                "warehouse": 12,
                "shop": null
              },
              "empty": {},
              "none": [],
              "nested": [
                [
                  1
                ],
                {
                  "x": true
                }
              ],
              "active": false
            }""";

    static Suite tests() {
        return suite("writer",
                test("compact output, character for character", () -> assertEquals(COMPACT, Json.write(TREE))),
                test("indented output, character for character", () -> assertEquals(PRETTY, Json.writePretty(TREE))),
                test("scalars at the top level", () -> {
                    assertEquals("\"x\"", Json.writePretty(JsonString.of("x")));
                    assertEquals("null", Json.write(JsonNull.INSTANCE));
                    assertEquals("true", Json.writePretty(JsonBoolean.TRUE));
                    assertEquals("-1.5e3", Json.write(Json.parse("-1.5e3")));
                    assertEquals("[]", Json.writePretty(JsonArray.of()));
                    assertEquals("{}", Json.writePretty(JsonObject.of()));
                }),
                test("toString is the compact JSON", () -> {
                    assertEquals(COMPACT, TREE.toString());
                    assertEquals("\"a\\nb\"", JsonString.of("a\nb").toString());
                    assertEquals("1.10", Json.parse("1.10").toString());
                    assertEquals("[null,false]", JsonArray.of(JsonNull.INSTANCE, JsonBoolean.FALSE).toString());
                }),
                test("strings: quote, backslash, controls, DEL, U+2028, U+2029 escaped; surrogate pairs kept", () -> {
                    var value = "q\"b\\s/\u0000\u0001\b\t\n\u000B\f\r\u001F\u007F\u0080\u2028\u2029\u00e9\uD83D\uDE00";
                    var expected = "\"q\\\"b\\\\s/\\u0000\\u0001\\b\\t\\n\\u000b\\f\\r\\u001f\\u007f\u0080\\u2028\\u2029"
                            + "\u00e9\uD83D\uDE00\"";
                    assertEquals(expected, Json.write(JsonString.of(value)));
                    assertEquals(expected, Json.writePretty(JsonString.of(value)));
                    assertEquals(JsonString.of(value), Json.parse(expected));
                }),
                test("every character U+0000..U+001F is escaped, with the short form where there is one", () -> {
                    for (char c = 0; c < 0x20; c++) {
                        var written = Json.write(JsonString.of(String.valueOf(c)));
                        var expected = switch (c) {
                            case '\b' -> "\"\\b\"";
                            case '\t' -> "\"\\t\"";
                            case '\n' -> "\"\\n\"";
                            case '\f' -> "\"\\f\"";
                            case '\r' -> "\"\\r\"";
                            default -> String.format("\"\\u%04x\"", (int) c);
                        };
                        assertEquals(expected, written);
                        assertEquals(JsonString.of(String.valueOf(c)), Json.parse(written));
                    }
                }),
                test("member names are escaped like strings", () -> {
                    var object = JsonObject.of("a\"\n\u2028", JsonNumber.of(1));
                    assertEquals("{\"a\\\"\\n\\u2028\":1}", Json.write(object));
                    assertEquals(object, Json.parse(Json.write(object)));
                }),
                test("JsonNumber.of(NaN) and infinities throw", () -> {
                    assertThrows(IllegalArgumentException.class, () -> JsonNumber.of(Double.NaN));
                    assertThrows(IllegalArgumentException.class, () -> JsonNumber.of(Double.POSITIVE_INFINITY));
                    assertThrows(IllegalArgumentException.class, () -> JsonNumber.of(Float.NEGATIVE_INFINITY));
                }),
                test("a string with an unpaired surrogate cannot be built, so every tree can be written", () -> {
                    assertThrows(IllegalArgumentException.class, () -> JsonString.of("a\uD800"));
                    assertThrows(IllegalArgumentException.class, () -> JsonString.of("\uDC00b"));
                    assertThrows(IllegalArgumentException.class, () -> JsonString.of("\uDE00\uD83D"));
                    assertThrows(IllegalArgumentException.class, () -> JsonObject.builder().put("\uD800", 1));
                    assertThrows(NullPointerException.class, () -> JsonString.of(null));
                    assertEquals("\uD83D\uDE00", JsonString.of("\uD83D\uDE00").value());
                }),
                test("Appendable, Writer and OutputStream give the same text; UTF-8 for the stream", () -> {
                    var builder = new StringBuilder("prefix:");
                    Json.write(TREE, builder);
                    assertEquals("prefix:" + COMPACT, builder.toString());
                    var writer = new StringWriter();
                    Json.writePretty(TREE, writer);
                    assertEquals(PRETTY, writer.toString());
                    var withText = JsonArray.of(TREE, JsonString.of("\u00e9\u20ac\uD83D\uDE00\u2028"));
                    var stream = new ByteArrayOutputStream();
                    Json.writeUtf8(withText, stream);
                    assertEquals(Json.write(withText), stream.toString(StandardCharsets.UTF_8));
                    assertTrue(java.util.Arrays.equals(Json.write(withText).getBytes(StandardCharsets.UTF_8),
                            stream.toByteArray()));
                    var pretty = new ByteArrayOutputStream();
                    Json.writePrettyUtf8(withText, pretty);
                    assertEquals(Json.writePretty(withText), pretty.toString(StandardCharsets.UTF_8));
                }),
                test("the output is deterministic", () -> {
                    var copy = Json.parse(Json.writePretty(TREE));
                    assertEquals(Json.writePretty(TREE), Json.writePretty(copy));
                    assertEquals(Json.write(TREE), Json.write(copy));
                }),
                test("trees of any depth built in code are written, compared and hashed without recursion", () -> {
                    JsonValue deep = JsonNumber.of(1);
                    JsonValue other = JsonNumber.of(1.0);
                    for (int i = 0; i < 200_000; i++) {
                        deep = i % 2 == 0 ? JsonArray.of(deep) : JsonObject.of("k", deep);
                        other = i % 2 == 0 ? JsonArray.of(other) : JsonObject.of("k", other);
                    }
                    assertEquals(deep, other);
                    assertEquals(deep.hashCode(), other.hashCode());
                    var text = Json.write(deep);
                    assertEquals(Json.write(other).length() - 2, text.length());
                    assertEquals(deep, Json.parse(text, JsonLimits.DEFAULT.withMaxDepth(200_000)));
                    // indented output grows with the square of the depth: checked on a smaller tree
                    JsonValue smaller = JsonNull.INSTANCE;
                    for (int i = 0; i < 1_000; i++) {
                        smaller = JsonArray.of(smaller);
                    }
                    var pretty = Json.writePretty(smaller);
                    assertEquals(smaller, Json.parse(pretty, JsonLimits.DEFAULT.withMaxDepth(1_000)));
                    assertTrue(pretty.endsWith("    ]\n  ]\n]"), pretty.substring(pretty.length() - 20));
                }),
                test("a wide tree is written in order", () -> {
                    var values = new ArrayList<JsonValue>();
                    for (int i = 0; i < 1000; i++) {
                        values.add(JsonNumber.of(i));
                    }
                    var text = Json.write(JsonArray.copyOf(values));
                    assertTrue(text.startsWith("[0,1,2,"), text);
                    assertTrue(text.endsWith(",998,999]"), text);
                    assertEquals(JsonArray.copyOf(values), Json.parse(text));
                    assertEquals(List.copyOf(values), ((JsonArray) Json.parse(text)).values());
                }));
    }
}
