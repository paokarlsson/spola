package spola.json;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertInOrder;
import static spola.test.Assertions.assertNotEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import spola.test.Suite;

/** Duplicate names, member order, and the byte order mark. */
final class ObjectTests {

    private ObjectTests() {
    }

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static byte[] withBom(String text) {
        var body = text.getBytes(StandardCharsets.UTF_8);
        var bytes = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, bytes, 0, BOM.length);
        System.arraycopy(body, 0, bytes, BOM.length, body.length);
        return bytes;
    }

    static Suite tests() {
        return suite("objects",
                test("duplicate names are rejected, naming the name and the position", () -> {
                    var e = Inputs.rejectAll(Inputs.entries("{\"a\":1,\"b\":2,\"a\":3}"), JsonLimits.DEFAULT);
                    assertTrue(e.getMessage().contains("duplicate member name \"a\""), e.getMessage());
                    assertEquals(1L, e.line());
                    assertEquals(14L, e.column());
                    // equal values do not make a duplicate acceptable
                    Inputs.rejectAll(Inputs.entries("{\"a\":1,\"a\":1}"), JsonLimits.DEFAULT);
                    // duplicates in nested objects too, but the same name in two objects is fine
                    Inputs.rejectAll(Inputs.entries("{\"x\":{\"a\":1,\"a\":2}}"), JsonLimits.DEFAULT);
                    Inputs.parseAll("{\"x\":{\"a\":1},\"y\":{\"a\":1},\"a\":{\"a\":1}}");
                }),
                test("names spelled differently that decode to the same string are duplicates", () -> {
                    var spellings = List.of("\"a\"", "\"\\u0061\"");
                    for (var first : spellings) {
                        for (var second : spellings) {
                            var text = "{" + first + ":1," + second + ":2}";
                            var e = Inputs.rejectAll(Inputs.entries(text), JsonLimits.DEFAULT);
                            assertTrue(e.getMessage().contains("duplicate member name \"a\""), e.getMessage());
                        }
                    }
                    Inputs.rejectAll(Inputs.entries("{\"\\/\":1,\"/\":2}"), JsonLimits.DEFAULT);
                    Inputs.rejectAll(Inputs.entries("{\"\\uD834\\uDD1E\":1,\"\uD834\uDD1E\":2}"), JsonLimits.DEFAULT);
                    Inputs.rejectAll(Inputs.entries("{\"\\u00e9\":1,\"\u00e9\":2}"), JsonLimits.DEFAULT);
                    Inputs.rejectAll(Inputs.entries("{\"\\u00e9\":1,\"\\u00E9\":2}"), JsonLimits.DEFAULT);
                    // names are compared exactly: no case folding and no Unicode normalization
                    Inputs.parseAll("{\"a\":1,\"A\":2}");
                    Inputs.parseAll("{\"\u00e9\":1,\"e\u0301\":2}");
                }),
                test("the builder and the factories reject duplicate names too", () -> {
                    assertThrows(IllegalArgumentException.class,
                            () -> JsonObject.builder().put("a", 1).put("a", 2));
                    assertThrows(IllegalArgumentException.class,
                            () -> JsonObject.of("a", JsonNull.INSTANCE, "a", JsonNull.INSTANCE));
                }),
                test("member order is kept when parsing and writing", () -> {
                    var text = "{\"z\":1,\"a\":2,\"m\":3,\"b\":{\"y\":1,\"x\":2}}";
                    var value = (JsonObject) Inputs.parseAll(text);
                    assertInOrder(List.of("z", "a", "m", "b"), List.copyOf(value.members().keySet()));
                    assertInOrder(List.of("y", "x"), List.copyOf(value.object("b").members().keySet()));
                    assertEquals(text, Json.write(value));
                    assertEquals("{\n  \"z\": 1,\n  \"a\": 2,\n  \"m\": 3,\n  \"b\": {\n    \"y\": 1,\n    \"x\": 2\n  }\n}",
                            Json.writePretty(value));
                    var built = JsonObject.builder().put("z", 1).put("a", 2).put("m", 3).build();
                    assertEquals("{\"z\":1,\"a\":2,\"m\":3}", Json.write(built));
                }),
                test("order is not part of equality, as JSON objects are unordered", () -> {
                    var first = Json.parse("{\"a\":1,\"b\":[1,2]}");
                    var second = Json.parse("{\"b\":[1,2],\"a\":1}");
                    assertEquals(first, second);
                    assertEquals(first.hashCode(), second.hashCode());
                    // but array order is
                    assertNotEquals(Json.parse("[1,2]"), Json.parse("[2,1]"));
                    assertNotEquals(Json.parse("{\"a\":1}"), Json.parse("{\"a\":1,\"b\":2}"));
                    assertNotEquals(Json.parse("{\"a\":1}"), Json.parse("{\"b\":1}"));
                }),
                test("a byte order mark at the start is skipped in String, Reader, byte[] and InputStream input", () -> {
                    var expected = Json.parse("{\"a\":[1]}");
                    assertEquals(expected, Json.parse("\uFEFF{\"a\":[1]}"));
                    assertEquals(expected, Json.parse(new StringReader("\uFEFF{\"a\":[1]}")));
                    assertEquals(expected, Json.parse(withBom("{\"a\":[1]}")));
                    assertEquals(expected, Json.parse(new ByteArrayInputStream(withBom("{\"a\":[1]}"))));
                    // also before whitespace and before a scalar
                    assertEquals(JsonNumber.of(1), Json.parse(withBom(" 1 ")));
                    assertEquals(JsonNumber.of(1), Json.parse("\uFEFF\n1"));
                }),
                test("a byte order mark anywhere else, outside a string, is an error", () -> {
                    for (var text : List.of("{\"a\":1}\uFEFF", "{\uFEFF\"a\":1}", "[1,\uFEFF2]", " \uFEFF1",
                            "\uFEFF\uFEFF1", "{\"a\"\uFEFF:1}")) {
                        var e = Inputs.rejectAll(Inputs.entries(text), JsonLimits.DEFAULT);
                        assertTrue(e.getMessage().contains("U+FEFF"), e.getMessage());
                    }
                    // a byte order mark alone is an empty document
                    Inputs.rejectAll(Inputs.entries("\uFEFF"), JsonLimits.DEFAULT);
                    // inside a string it is an ordinary character
                    assertEquals(JsonString.of("\uFEFFx"), Inputs.parseAll("\"\uFEFFx\""));
                }));
    }
}
