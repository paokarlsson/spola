package spola.json;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Assertions.fail;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.charset.StandardCharsets;
import spola.test.Suite;

/**
 * The strict UTF-8 decoder against RFC 3629 §4 and, as a reference, the JDK's own decoder with
 * {@code CodingErrorAction.REPORT}: a byte sequence inside a JSON string is accepted exactly
 * when the JDK decodes it (and the result has no character JSON requires to be escaped), and
 * then to the same text.
 */
final class Utf8Tests {

    private Utf8Tests() {
    }

    /** Bytes to try after the first one: the boundaries of every range in RFC 3629 §4. */
    private static final int[] EDGES = {0x00, 0x22, 0x41, 0x5C, 0x7F, 0x80, 0x8F, 0x90, 0x9F, 0xA0, 0xBF, 0xC0, 0xC2,
            0xE0, 0xF0, 0xF4, 0xF5, 0xFF};

    /** Checks one byte sequence placed between quotes against the JDK. */
    private static void check(byte... sequence) {
        var document = new byte[sequence.length + 2];
        document[0] = '"';
        System.arraycopy(sequence, 0, document, 1, sequence.length);
        document[document.length - 1] = '"';
        var reference = Inputs.jdkDecode(sequence);
        boolean jsonText = reference.isPresent() && reference.get().chars().noneMatch(c -> c < 0x20 || c == '"' || c == '\\');
        if (jsonText) {
            JsonValue parsed;
            try {
                parsed = Json.parse(document);
            } catch (JsonParseException e) {
                fail("rejected " + hex(sequence) + ", which the JDK decodes: " + e.getMessage());
                return;
            }
            assertEquals(JsonString.of(reference.get()), parsed, hex(sequence));
        } else {
            assertThrows(JsonParseException.class, () -> Json.parse(document));
        }
    }

    private static String hex(byte[] bytes) {
        var out = new StringBuilder();
        for (byte b : bytes) {
            out.append(String.format("%02X ", b & 0xFF));
        }
        return out.toString().strip();
    }

    static Suite tests() {
        return suite("UTF-8",
                test("every sequence of two bytes agrees with the JDK decoder", () -> {
                    for (int first = 0x80; first <= 0xFF; first++) {
                        for (int second = 0; second <= 0xFF; second++) {
                            check((byte) first, (byte) second);
                        }
                    }
                }),
                test("every three-byte start with every second byte and the edge third bytes agrees with the JDK", () -> {
                    for (int first = 0xE0; first <= 0xEF; first++) {
                        for (int second = 0; second <= 0xFF; second++) {
                            for (int third : EDGES) {
                                check((byte) first, (byte) second, (byte) third);
                            }
                        }
                    }
                }),
                test("every four-byte start with every second byte and the edge later bytes agrees with the JDK", () -> {
                    for (int first = 0xF0; first <= 0xFF; first++) {
                        for (int second = 0; second <= 0xFF; second++) {
                            for (int third : new int[] {0x80, 0xBF, 0x41}) {
                                for (int fourth : new int[] {0x80, 0xBF, 0x41}) {
                                    check((byte) first, (byte) second, (byte) third, (byte) fourth);
                                }
                            }
                        }
                    }
                }),
                test("every code point U+0020..U+10FFFF (except surrogates, quote and backslash) round-trips", () -> {
                    var text = new StringBuilder();
                    for (int cp = 0x20; cp <= 0x10FFFF; cp++) {
                        if (cp != '"' && cp != '\\' && (cp < 0xD800 || cp > 0xDFFF)) {
                            text.appendCodePoint(cp);
                        }
                    }
                    var expected = text.toString();
                    var document = ("\"" + expected + "\"").getBytes(StandardCharsets.UTF_8);
                    var limits = JsonLimits.DEFAULT.withMaxStringLength(4_000_000).withMaxInputSize(8_000_000);
                    assertEquals(JsonString.of(expected), Json.parse(document, limits));
                    var written = new java.io.ByteArrayOutputStream();
                    try {
                        Json.writeUtf8(JsonString.of(expected), written);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                    assertEquals(JsonString.of(expected), Json.parse(written.toByteArray(), limits));
                }),
                test("the boundary code points of every length decode", () -> {
                    int[] codePoints = {0x00, 0x7F, 0x80, 0x7FF, 0x800, 0xD7FF, 0xE000, 0xFFFD, 0xFFFF, 0x10000, 0x10FFFF};
                    for (int cp : codePoints) {
                        var text = "\"" + (cp < 0x20 ? "" : Character.toString(cp)) + "\"";
                        var value = Json.parse(text.getBytes(StandardCharsets.UTF_8));
                        assertTrue(value instanceof JsonString, Integer.toHexString(cp));
                    }
                }));
    }
}
