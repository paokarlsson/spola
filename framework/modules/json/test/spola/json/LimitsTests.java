package spola.json;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import spola.test.Suite;

/**
 * Every limit of {@link JsonLimits}: exactly at the limit is accepted, one over is rejected with
 * a {@link JsonParseException} that names the limit, through every entry point.
 */
final class LimitsTests {

    private LimitsTests() {
    }

    /** Accepted at {@code limits}, through every entry point. */
    private static JsonValue accepted(String text, JsonLimits limits) {
        return Inputs.parseAll(Inputs.entries(text), limits);
    }

    /** Rejected at {@code limits} through every entry point, with a message naming {@code limit}. */
    private static JsonParseException rejected(String text, JsonLimits limits, String limit) {
        var first = Inputs.rejectAll(Inputs.entries(text), limits);
        for (var entry : Inputs.entries(text)) {
            var e = Inputs.rejectAll(java.util.List.of(entry), limits);
            assertTrue(e.getMessage().contains(limit), entry.name() + ": " + e.getMessage());
        }
        return first;
    }

    private static String nested(int depth) {
        return "[".repeat(depth) + "]".repeat(depth);
    }

    /** Runs {@code body} on a new virtual thread and returns what it threw (or null). */
    private static Throwable onVirtualThread(Runnable body) throws InterruptedException {
        var thrown = new AtomicReference<Throwable>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        thread.join();
        return thrown.get();
    }

    /** A stream of spaces that never ends, counting the bytes handed out. */
    private static final class EndlessSpaces extends InputStream {
        final AtomicLong handedOut = new AtomicLong();

        @Override
        public int read() {
            handedOut.incrementAndGet();
            return ' ';
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            java.util.Arrays.fill(buffer, offset, offset + length, (byte) ' ');
            handedOut.addAndGet(length);
            return length;
        }
    }

    static Suite tests() {
        return suite("limits",
                test("the defaults", () -> {
                    assertEquals(new JsonLimits(512, 1_048_576, 1_000, 100_000, 4_194_304L), JsonLimits.DEFAULT);
                    assertThrows(IllegalArgumentException.class, () -> JsonLimits.DEFAULT.withMaxDepth(0));
                    assertThrows(IllegalArgumentException.class, () -> JsonLimits.DEFAULT.withMaxInputSize(-1));
                }),
                test("maxDepth: at the limit accepted, one over rejected, for arrays and objects", () -> {
                    var limits = JsonLimits.DEFAULT.withMaxDepth(3);
                    accepted("[[[]]]", limits);
                    accepted("{\"a\":{\"b\":[1]}}", limits);
                    accepted("[[[1,\"x\",true]]]", limits);
                    rejected("[[[[]]]]", limits, "maxDepth (3)");
                    rejected("{\"a\":{\"b\":{\"c\":{}}}}", limits, "maxDepth (3)");
                    rejected("[{\"a\":[{}]}]", limits, "maxDepth (3)");
                }),
                test("maxDepth: the default 512 accepts 512 levels and rejects 513", () -> {
                    accepted(nested(512), JsonLimits.DEFAULT);
                    var e = rejected(nested(513), JsonLimits.DEFAULT, "maxDepth (512)");
                    assertEquals(513L, e.column());
                }),
                test("100 000 nested [ give JsonParseException, not StackOverflowError, on a platform thread", () -> {
                    var deep = "[".repeat(100_000);
                    Inputs.rejectAll(Inputs.entries(deep), JsonLimits.DEFAULT);
                    // without the depth limit the parser still does not recurse: the input simply ends
                    var unlimited = JsonLimits.DEFAULT.withMaxDepth(Integer.MAX_VALUE);
                    var e = Inputs.rejectAll(Inputs.entries(deep), unlimited);
                    assertTrue(e.getMessage().contains("end of input"), e.getMessage());
                }),
                test("100 000 nested [ give JsonParseException, not StackOverflowError, on a virtual thread", () -> {
                    var deep = "[".repeat(100_000);
                    var thrown = onVirtualThread(() -> Inputs.rejectAll(Inputs.entries(deep), JsonLimits.DEFAULT));
                    assertEquals(null, thrown);
                    var unlimited = JsonLimits.DEFAULT.withMaxDepth(Integer.MAX_VALUE);
                    thrown = onVirtualThread(() -> Inputs.rejectAll(Inputs.entries(deep), unlimited));
                    assertEquals(null, thrown);
                }),
                test("100 000 nested levels parse, compare, hash and write without recursion when allowed", () -> {
                    var limits = JsonLimits.DEFAULT.withMaxDepth(100_000);
                    var text = nested(100_000);
                    var thrown = onVirtualThread(() -> {
                        var value = Json.parse(text, limits);
                        var again = Json.parse(text.getBytes(StandardCharsets.UTF_8), limits);
                        assertEquals(value, again);
                        assertEquals(value.hashCode(), again.hashCode());
                        assertEquals(text, Json.write(value));
                        // (indented output grows with the square of the depth, so only compact here)
                        assertEquals(value, Json.parse(Json.write(value), limits));
                    });
                    assertEquals(null, thrown);
                }),
                test("maxStringLength: decoded UTF-16 length of strings and names", () -> {
                    var limits = JsonLimits.DEFAULT.withMaxStringLength(5);
                    accepted("\"abcde\"", limits);
                    accepted("\"\\u0061\\u0062cde\"", limits);
                    accepted("{\"abcde\":\"vwxyz\"}", limits);
                    // a character outside the BMP is two UTF-16 code units
                    accepted("\"abc\uD834\uDD1E\"", limits);
                    rejected("\"abcdef\"", limits, "maxStringLength (5)");
                    rejected("\"\\u0061\\u0062cdef\"", limits, "maxStringLength (5)");
                    rejected("{\"abcdef\":1}", limits, "maxStringLength (5)");
                    rejected("\"abcd\uD834\uDD1E\"", limits, "maxStringLength (5)");
                }),
                test("maxStringLength: the default accepts 1 MiB and rejects one more", () -> {
                    var limit = JsonLimits.DEFAULT.maxStringLength();
                    var at = "\"" + "x".repeat(limit) + "\"";
                    var over = "\"" + "x".repeat(limit + 1) + "\"";
                    assertEquals(limit, ((JsonString) Json.parse(at)).value().length());
                    var e = assertThrows(JsonParseException.class, () -> Json.parse(over));
                    assertTrue(e.getMessage().contains("maxStringLength (1048576)"), e.getMessage());
                }),
                test("maxNumberLength: characters of the number's text", () -> {
                    var limits = JsonLimits.DEFAULT.withMaxNumberLength(5);
                    accepted("12345", limits);
                    accepted("-1.25", limits);
                    accepted("[1e-10,99999]", limits);
                    rejected("123456", limits, "maxNumberLength (5)");
                    rejected("-1.255", limits, "maxNumberLength (5)");
                    rejected("[1e-100]", limits, "maxNumberLength (5)");
                    var limit = JsonLimits.DEFAULT.maxNumberLength();
                    accepted("1".repeat(limit), JsonLimits.DEFAULT);
                    rejected("1".repeat(limit + 1), JsonLimits.DEFAULT, "maxNumberLength (1000)");
                    rejected("1e" + "9".repeat(limit), JsonLimits.DEFAULT, "maxNumberLength (1000)");
                }),
                test("maxEntries: members per object and elements per array", () -> {
                    var limits = JsonLimits.DEFAULT.withMaxEntries(3);
                    accepted("[1,2,3]", limits);
                    accepted("{\"a\":1,\"b\":2,\"c\":3}", limits);
                    accepted("[[1,2,3],[4,5,6],[7,8,9]]", limits);
                    rejected("[1,2,3,4]", limits, "maxEntries (3)");
                    rejected("{\"a\":1,\"b\":2,\"c\":3,\"d\":4}", limits, "maxEntries (3)");
                    rejected("[[1,2,3,4]]", limits, "maxEntries (3)");
                    var limit = JsonLimits.DEFAULT.maxEntries();
                    accepted("[" + "0,".repeat(limit - 1) + "0]", JsonLimits.DEFAULT);
                    rejected("[" + "0,".repeat(limit) + "0]", JsonLimits.DEFAULT, "maxEntries (100000)");
                }),
                test("maxInputSize: units of the input, a byte order mark included", () -> {
                    var limits = JsonLimits.DEFAULT.withMaxInputSize(5);
                    accepted("12345", limits);
                    accepted(" [1] ", limits);
                    rejected("123456", limits, "maxInputSize (5");
                    rejected("[1]   ", limits, "maxInputSize (5");
                    // "\u00e9" in quotes: 3 chars, 4 bytes
                    var text = "\"\u00e9\"";
                    var bytes = text.getBytes(StandardCharsets.UTF_8);
                    assertEquals(4, bytes.length);
                    Inputs.parseAll(Inputs.entries(text), JsonLimits.DEFAULT.withMaxInputSize(4));
                    var e = Inputs.rejectAll(Inputs.entries(bytes), JsonLimits.DEFAULT.withMaxInputSize(3));
                    assertTrue(e.getMessage().contains("maxInputSize (3 bytes)"), e.getMessage());
                    assertEquals(3L, e.offset());
                    Json.parse(text, JsonLimits.DEFAULT.withMaxInputSize(3));
                    var chars = assertThrows(JsonParseException.class,
                            () -> Json.parse(text, JsonLimits.DEFAULT.withMaxInputSize(2)));
                    assertTrue(chars.getMessage().contains("maxInputSize (2 chars)"), chars.getMessage());
                    // a byte order mark counts: 3 bytes or 1 char
                    var bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '1'};
                    Json.parse(bom, JsonLimits.DEFAULT.withMaxInputSize(4));
                    assertThrows(JsonParseException.class, () -> Json.parse(bom, JsonLimits.DEFAULT.withMaxInputSize(3)));
                    Json.parse("\uFEFF1", JsonLimits.DEFAULT.withMaxInputSize(2));
                    assertThrows(JsonParseException.class, () -> Json.parse("\uFEFF1", JsonLimits.DEFAULT.withMaxInputSize(1)));
                }),
                test("maxInputSize: the default accepts 4 MiB and rejects one byte more", () -> {
                    int limit = (int) JsonLimits.DEFAULT.maxInputSize();
                    var at = " ".repeat(limit - 1) + "1";
                    Inputs.parseAll(Inputs.entries(at), JsonLimits.DEFAULT);
                    var over = " ".repeat(limit) + "1";
                    var e = Inputs.rejectAll(Inputs.entries(over), JsonLimits.DEFAULT);
                    assertTrue(e.getMessage().contains("maxInputSize (4194304"), e.getMessage());
                }),
                test("an InputStream that never ends fails at the size limit, read no further than one byte past it", () -> {
                    var stream = new EndlessSpaces();
                    var e = assertThrows(JsonParseException.class,
                            () -> Json.parse(stream, JsonLimits.DEFAULT.withMaxInputSize(10_000)));
                    assertTrue(e.getMessage().contains("maxInputSize (10000 bytes)"), e.getMessage());
                    assertEquals(10_001L, stream.handedOut.get());
                    var defaults = new EndlessSpaces();
                    var d = assertThrows(JsonParseException.class, () -> Json.parse(defaults));
                    assertTrue(d.getMessage().contains("maxInputSize (4194304 bytes)"), d.getMessage());
                    assertEquals(4_194_305L, defaults.handedOut.get());
                    // a valid value followed by endless whitespace too
                    var afterValue = new java.io.SequenceInputStream(
                            new java.io.ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), new EndlessSpaces());
                    assertThrows(JsonParseException.class,
                            () -> Json.parse(afterValue, JsonLimits.DEFAULT.withMaxInputSize(1_000)));
                }),
                test("a Reader that never ends fails at the size limit, read no further than one char past it", () -> {
                    var count = new AtomicLong();
                    var endless = new Reader() {
                        @Override
                        public int read(char[] buffer, int offset, int length) {
                            java.util.Arrays.fill(buffer, offset, offset + length, ' ');
                            count.addAndGet(length);
                            return length;
                        }

                        @Override
                        public void close() {
                        }
                    };
                    var e = assertThrows(JsonParseException.class,
                            () -> Json.parse(endless, JsonLimits.DEFAULT.withMaxInputSize(1_000)));
                    assertTrue(e.getMessage().contains("maxInputSize (1000 chars)"), e.getMessage());
                    assertEquals(1_001L, count.get());
                }),
                test("a failing stream gives its IOException, not a parse error", () -> {
                    var failing = new InputStream() {
                        @Override
                        public int read() throws IOException {
                            throw new IOException("disk on fire");
                        }
                    };
                    var e = assertThrows(IOException.class, () -> Json.parse(failing));
                    assertEquals("disk on fire", e.getMessage());
                }));
    }
}
