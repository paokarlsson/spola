package spola.json;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import spola.test.Suite;

/** Positions in {@link JsonParseException}, and messages that are short and safe to log. */
final class ErrorMessageTests {

    private ErrorMessageTests() {
    }

    /** Every character of the message is printable ASCII: no control, no line break, no bidi control. */
    static void assertSafe(String message) {
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            assertTrue(c >= 0x20 && c < 0x7F, "character " + (int) c + " at " + i + " in " + Inputs.show(message));
        }
    }

    private static final String MB = "a".repeat(1 << 20);

    /** Large inputs (1 MiB of text in a string, a name, a number or garbage) that fail. */
    static final List<String> LARGE = List.of(
            "\"" + MB,
            "{\"" + MB + "\":1,\"" + MB + "\":2}",
            "[" + MB + "]",
            "1" + MB,
            "[\"" + MB + "\"" + MB + "]",
            "{\"" + MB + "\"" + MB + "}");

    static Suite tests() {
        return suite("error messages",
                test("an error on line 3 has the right line, column and offset", () -> {
                    var text = "{\n  \"a\": 1,\n  \"b\" 2\n}";
                    for (var entry : Inputs.entries(text)) {
                        var e = Inputs.rejectAll(List.of(entry), JsonLimits.DEFAULT);
                        assertEquals(3L, e.line(), entry.name());
                        assertEquals(7L, e.column(), entry.name());
                        assertEquals(18L, e.offset(), entry.name());
                        assertEquals(entry.name().equals("byte[]") || entry.name().equals("InputStream"),
                                e.isByteOffset(), entry.name());
                        assertTrue(e.getMessage().startsWith("line 3, column 7 ("), e.getMessage());
                        assertTrue(e.description().contains("expected ':'"), e.getMessage());
                    }
                }),
                test("CR, LF and CRLF each end one line", () -> {
                    // CRLF is one break; LF followed by CR is two
                    var e = assertThrows(JsonParseException.class, () -> Json.parse("[\r\n1,\r2,\n3,\n\r x]"));
                    assertEquals(6L, e.line());
                    assertEquals(2L, e.column());
                }),
                test("columns count code points, and a byte order mark is not a column", () -> {
                    // U+1D11E is two chars and four bytes, but one column
                    var text = "\uFEFF[\"\uD834\uDD1E\", x]";
                    var fromText = assertThrows(JsonParseException.class, () -> Json.parse(text));
                    assertEquals(1L, fromText.line());
                    assertEquals(7L, fromText.column());
                    assertEquals(8L, fromText.offset());
                    var fromBytes = assertThrows(JsonParseException.class,
                            () -> Json.parse(text.getBytes(StandardCharsets.UTF_8)));
                    assertEquals(7L, fromBytes.column());
                    assertEquals(3L + 1 + 1 + 4 + 1 + 1 + 1, fromBytes.offset());
                    assertTrue(fromBytes.isByteOffset());
                }),
                test("the offset of invalid UTF-8 is its byte offset", () -> {
                    var e = assertThrows(JsonParseException.class,
                            () -> Json.parse(new byte[] {'[', '"', 'a', '"', ',', '"', (byte) 0xC0, (byte) 0xAF, '"', ']'}));
                    assertEquals(6L, e.offset());
                    assertEquals(7L, e.column());
                    assertTrue(e.getMessage().contains("0xC0"), e.getMessage());
                }),
                test("control characters from the input never appear raw in the message", () -> {
                    var inputs = List.of(
                            "\"a\u0001b\"",
                            "[\u0007]",
                            "\u001b[31m",
                            "{\"a\\u0000\\n\\u001b\u2028\u202E\":1,\"a\\u0000\\n\\u001b\u2028\u202E\":2}",
                            "\"\\\u0001\"",
                            "\u0085",
                            "[1\u2028]",
                            "\"\uD800\"");
                    for (var input : inputs) {
                        var e = assertThrows(JsonParseException.class, () -> Json.parse(input));
                        assertSafe(e.getMessage());
                        assertFalse(e.getMessage().contains("\u0001"), e.getMessage());
                    }
                    var duplicate = assertThrows(JsonParseException.class, () -> Json.parse(inputs.get(3)));
                    assertTrue(duplicate.getMessage().contains("\"a\\u0000\\u000a\\u001b\\u2028\\u202e\""),
                            duplicate.getMessage());
                }),
                parameterized("the message for a 1 MiB input is shorter than 500 characters", LARGE,
                        text -> Inputs.show(text.substring(0, 12)) + "... (" + text.length() + " chars)", text -> {
                            for (var entry : Inputs.entries(text)) {
                                var e = Inputs.rejectAll(List.of(entry), JsonLimits.DEFAULT);
                                assertTrue(e.getMessage().length() < 500, entry.name() + ": " + e.getMessage().length());
                                assertSafe(e.getMessage());
                            }
                        }),
                test("a duplicate name is named, cut short when long, at the position of the second", () -> {
                    var e = assertThrows(JsonParseException.class, () -> Json.parse("{\"id\":1,\n \"id\":2}"));
                    assertEquals(2L, e.line());
                    assertEquals(2L, e.column());
                    assertTrue(e.getMessage().contains("duplicate member name \"id\""), e.getMessage());
                    var name = "n".repeat(10_000);
                    var longName = assertThrows(JsonParseException.class,
                            () -> Json.parse("{\"" + name + "\":1,\"" + name + "\":2}"));
                    assertTrue(longName.getMessage().contains("(10000 characters)"), longName.getMessage());
                }));
    }
}
