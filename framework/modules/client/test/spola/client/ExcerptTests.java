package spola.client;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import spola.http.Response;
import spola.test.Suite;

/** Criterion 10, first half: bodyExcerpt() as a table, through a real server. */
final class ExcerptTests {

    private ExcerptTests() {
    }

    /**
     * One row: a body and its excerpt.
     *
     * @param name     what the row shows
     * @param body     the response body
     * @param expected the excerpt
     */
    record Row(String name, byte[] body, String expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Row row(String name, String body, String expected) {
        return new Row(name, body.getBytes(StandardCharsets.UTF_8), expected);
    }

    static byte[] bytes(Object... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            if (part instanceof String text) {
                out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
            } else {
                out.write((Integer) part);
            }
        }
        return out.toByteArray();
    }

    static final String SMILE = "😀"; // U+1F600, a surrogate pair

    /** Every character that an excerpt writes as \\uXXXX. */
    static List<Character> escaped() {
        var all = new ArrayList<Character>();
        for (char c = 0; c <= 0x1F; c++) {
            all.add(c);
        }
        for (char c = 0x7F; c <= 0x9F; c++) {
            all.add(c);
        }
        for (char c : new char[] {0x2028, 0x2029, 0x061C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066,
                0x2067, 0x2068, 0x2069}) {
            all.add(c);
        }
        return all;
    }

    static List<Row> rows() {
        var rows = new ArrayList<Row>(List.of(
                row("ASCII", "{\"error\":\"bad request\"}", "{\"error\":\"bad request\"}"),
                row("Swedish letters stay", "Åtgärden är ogiltig för användaren ö", "Åtgärden är ogiltig för användaren ö"),
                row("emoji stay", "fel " + SMILE + " igen", "fel " + SMILE + " igen"),
                row("empty body", "", ""),
                row("1 000 characters are not cut", "a".repeat(1_000), "a".repeat(1_000)),
                row("1 001 characters are cut after 1 000 with ...", "a".repeat(1_001), "a".repeat(1_000) + "..."),
                row("1 000 Swedish characters (2 000 bytes) are not cut", "ö".repeat(1_000), "ö".repeat(1_000)),
                row("a surrogate pair across the limit is left out whole", "a".repeat(999) + SMILE + "b",
                        "a".repeat(999) + "..."),
                row("a surrogate pair ending at the limit is kept", "a".repeat(998) + SMILE + "b",
                        "a".repeat(998) + SMILE + "..."),
                row("a body of exactly 1 001 chars ending in a pair", "a".repeat(999) + SMILE, "a".repeat(999) + "..."),
                row("escaping comes after the cut: 1 000 chars, then \\u escapes", "\n".repeat(1_001),
                        "\\u000a".repeat(1_000) + "..."),
                row("CR and LF", "line 1\r\nline 2", "line 1\\u000d\\u000aline 2"),
                row("a forged log line", "ok\n2026-10-14 INFO admin logged in", "ok\\u000a2026-10-14 INFO admin logged in"),
                row("bidi override", "user ‮gnp.exe", "user \\u202egnp.exe"),
                row("characters next to the list stay", " ‍‰⁥⁪~ ", " ‍‰⁥⁪~ "),
                new Row("invalid UTF-8 becomes U+FFFD", bytes("a", 0xFF, "b", 0xC0, 0xAF, "c"), "a�b��c"),
                new Row("an incomplete sequence at the end becomes U+FFFD", bytes("ab", 0xE2, 0x82), "ab�"),
                new Row("an incomplete 4-byte sequence at the end", bytes("ab", 0xF0, 0x9F, 0x98), "ab�"),
                new Row("a lone continuation byte", bytes(0x80, "x"), "�x")));
        for (char c : escaped()) {
            rows.add(row(String.format("U+%04X is escaped", (int) c), "a" + c + "b", String.format("a\\u%04xb", (int) c)));
        }
        return rows;
    }

    static Suite tests() {
        var bodies = new ConcurrentHashMap<Integer, byte[]>();
        var next = new AtomicInteger();
        return suite("bodyExcerpt",
                parameterized("through a server answering 400 with the body", rows(), row -> {
                    int id = next.incrementAndGet();
                    bodies.put(id, row.body());
                    try (var server = Support.serve(request -> Response.bytes(400, "text/plain",
                            bodies.get(Integer.parseInt(request.path().substring(1)))));
                            var client = Support.client(server.port())) {
                        var e = assertThrows(RestClientResponseException.class, () -> client.get("/" + id).retrieveJson());
                        assertEquals(400, e.status());
                        assertEquals(row.expected(), e.bodyExcerpt());
                        assertEquals(row.expected(), Text.excerpt(row.body(), row.body().length, false, List.of()));
                        assertTrue(Support.printableAscii(e.getMessage()), e.getMessage());
                    }
                }),
                parameterized("a body whose reading stopped at maxResponseSize ends with ... and never with a split"
                        + " character", List.of(200, 500), status -> {
                            var body = ("\"ab" + "€".repeat(2_000) + "\"").getBytes(StandardCharsets.UTF_8);
                            try (var server = Support.serve(request -> spola.http.Response.stream(status, "text/plain",
                                    out -> out.write(body)));
                                    var client = RestClient.builder().baseUri(Support.base(server.port()))
                                            .maxResponseSize(1024).build()) {
                                var e = assertThrows(RestClientResponseException.class, () -> client.get("/x").retrieveJson());
                                assertEquals(status, e.status());
                                // 1 024 bytes: "ab and 340 whole euro signs (1 020 bytes), then 1 byte of the next
                                assertEquals("\"ab" + "€".repeat(340) + "...", e.bodyExcerpt());
                            }
                        }),
                test("Text.excerpt with more: ..., the split sequence left out, invalid bytes kept as U+FFFD", () -> {
                    assertEquals("ab...", Text.excerpt(bytes("ab"), 2, true, List.of()));
                    assertEquals("ab...", Text.excerpt(bytes("ab", 0xC3), 3, true, List.of()));
                    assertEquals("ab...", Text.excerpt(bytes("ab", 0xE2, 0x82), 4, true, List.of()));
                    assertEquals("ab...", Text.excerpt(bytes("ab", 0xF0, 0x9F, 0x98), 5, true, List.of()));
                    assertEquals("ab" + SMILE + "...", Text.excerpt(bytes("ab", 0xF0, 0x9F, 0x98, 0x80), 6, true, List.of()));
                    assertEquals("ab\uFFFD...", Text.excerpt(bytes("ab", 0xFF), 3, true, List.of()));
                    assertEquals("a\uFFFD\uFFFD\uFFFD\uFFFD...", Text.excerpt(bytes("a", 0x80, 0x80, 0x80, 0x80), 5, true,
                            List.of()));
                    assertEquals("...", Text.excerpt(bytes(0xC3), 1, true, List.of()));
                    // without more, an incomplete sequence at the end is the body's own: U+FFFD
                    assertEquals("ab\uFFFD", Text.excerpt(bytes("ab", 0xC3), 3, false, List.of()));
                    // only the first length bytes count
                    assertEquals("ab...", Text.excerpt(bytes("abcd"), 2, true, List.of()));
                }),
                test("Text.wholeCharacters drops only an incomplete sequence at the end", () -> {
                    assertEquals(0, Text.wholeCharacters(new byte[0], 0));
                    assertEquals(2, Text.wholeCharacters(bytes("ab"), 2));
                    assertEquals(2, Text.wholeCharacters(bytes("ab", 0xC3), 3));
                    assertEquals(4, Text.wholeCharacters(bytes("ab", 0xC3, 0xB6), 4));
                    assertEquals(2, Text.wholeCharacters(bytes("ab", 0xE2, 0x82), 4));
                    assertEquals(5, Text.wholeCharacters(bytes("ab", 0xE2, 0x82, 0xAC), 5));
                    assertEquals(2, Text.wholeCharacters(bytes("ab", 0xF0, 0x9F, 0x98), 5));
                    assertEquals(6, Text.wholeCharacters(bytes("ab", 0xF0, 0x9F, 0x98, 0x80), 6));
                    assertEquals(3, Text.wholeCharacters(bytes("ab", 0xFF), 3));
                    assertEquals(4, Text.wholeCharacters(bytes("a", 0x80, 0x80, 0x80), 4));
                    assertEquals(5, Text.wholeCharacters(bytes("a", 0x80, 0x80, 0x80, 0x80), 5));
                }),
                test("Text.hide: whole values, overlapping and touching ones as one, an end that starts a value when"
                        + " cut", () -> {
                    assertEquals("key <hidden> here", Text.hide("key sk-123 here", List.of("sk-123"), false));
                    assertEquals("<hidden> and <hidden>", Text.hide("abc and abc", List.of("abc"), false));
                    assertEquals("x<hidden>y", Text.hide("xabcdy", List.of("abc", "bcd"), false));
                    assertEquals("x<hidden>y", Text.hide("xabcdefy", List.of("abc", "def"), false));
                    assertEquals("<hidden> <hidden>", Text.hide("aa a", List.of("a"), false));
                    assertEquals("same", Text.hide("same", List.of(""), true));
                    assertEquals("ends with {\"pro", Text.hide("ends with {\"pro", List.of("{\"prompt\":1}"), false));
                    assertEquals("ends with <hidden>", Text.hide("ends with {\"pro", List.of("{\"prompt\":1}"), true));
                    assertEquals("no value here", Text.hide("no value here", List.of("secret"), true));
                    assertEquals("<hidden> <hidden>", Text.hide("hidden hid", List.of("hidden", "hid"), false),
                            "one pass: the placeholder is never searched");
                }),
                test("the message hides a value of the request that runs across the 1 000-character cut", () -> {
                    var body = "x".repeat(990) + "sk-secret-value-1234" + "y".repeat(100);
                    var bytes = body.getBytes(StandardCharsets.UTF_8);
                    var shown = Text.excerpt(bytes, bytes.length, false, List.of("sk-secret-value-1234"));
                    assertEquals("x".repeat(990) + "<hidden>" + "y".repeat(2) + "...", shown);
                    assertEquals("x".repeat(990) + "sk-secret-", Text.excerpt(bytes, bytes.length, false, List.of())
                            .substring(0, 1_000));
                }),
                test("lone surrogates are escaped; pairs are not", () -> {
                    assertEquals("a\\ud83db", Text.escape("a\uD83Db"));
                    assertEquals("a\\ude00b", Text.escape("a\uDE00b"));
                    assertEquals("\\ude00\\ud83d", Text.escape("\uDE00\uD83D"));
                    assertEquals("x" + SMILE, Text.escape("x" + SMILE));
                    assertEquals("\\ud83d", Text.escape("\uD83D"));
                }),
                test("every character outside the list and the surrogates is kept", () -> {
                    var list = escaped();
                    for (int c = 0; c <= 0xFFFF; c++) {
                        if (!Character.isSurrogate((char) c)) {
                            assertEquals(list.contains((char) c), Text.mustEscape((char) c), String.format("U+%04X", c));
                        }
                    }
                }),
                test("the message shows the start of the excerpt with characters outside ASCII as \\uXXXX", () -> {
                    try (var server = Support.serve(request -> Response.bytes(422, "application/json",
                            "{\"fel\":\"Åtgärden\när ogiltig " .concat(SMILE).concat("\"}").getBytes(StandardCharsets.UTF_8)));
                            var client = Support.client(server.port())) {
                        var e = assertThrows(RestClientResponseException.class, () -> client.get("/x").retrieveJson());
                        assertEquals("{\"fel\":\"Åtgärden\\u000aär ogiltig " + SMILE + "\"}", e.bodyExcerpt());
                        assertTrue(e.getMessage().endsWith(
                                ": {\"fel\":\"\\u00c5tg\\u00e4rden\\u000a\\u00e4r ogiltig \\ud83d\\ude00\"}"), e.getMessage());
                        assertFalse(e.getMessage().contains("\n"));
                    }
                }));
    }
}
