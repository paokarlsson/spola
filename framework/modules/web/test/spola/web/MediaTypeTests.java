package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import spola.test.Suite;

/** Criterion 3: MediaType.parse, table-driven, valid and invalid. */
final class MediaTypeTests {

    private MediaTypeTests() {
    }

    /**
     * A valid media type.
     *
     * @param text      the input
     * @param canonical the expected {@code toString()}
     */
    record Valid(String text, String canonical) {
    }

    /**
     * An invalid media type.
     *
     * @param text   the input
     * @param reason a part of the expected message
     */
    record Invalid(String text, String reason) {
    }

    static final List<Valid> VALID = List.of(
            new Valid("application/json", "application/json"),
            new Valid("APPLICATION/JSON", "application/json"),
            new Valid("Text/HTML", "text/html"),
            new Valid("application/json;charset=utf-8", "application/json;charset=utf-8"),
            new Valid("application/json; charset=UTF-8", "application/json;charset=utf-8"),
            new Valid("application/json;CHARSET=Utf-8", "application/json;charset=utf-8"),
            new Valid("application/json;charset=\"utf-8\"", "application/json;charset=utf-8"),
            new Valid("application/json \t; \tcharset=utf-8", "application/json;charset=utf-8"),
            new Valid("text/plain;Format=Flowed", "text/plain;format=Flowed"),
            new Valid("text/plain;a=1;b=2;c=3", "text/plain;a=1;b=2;c=3"),
            new Valid("text/plain;title=\"a \\\"quoted\\\" \\\\ word\"", "text/plain;title=\"a \\\"quoted\\\" \\\\ word\""),
            new Valid("text/plain;title=\"a,b;c=d\"", "text/plain;title=\"a,b;c=d\""),
            new Valid("text/plain;title=\"\"", "text/plain;title=\"\""),
            new Valid("text/plain;title=\"\\x\"", "text/plain;title=x"),
            new Valid("application/vnd.example+json", "application/vnd.example+json"),
            new Valid("application/problem+json", "application/problem+json"),
            new Valid("application/vnd.api+json;version=2", "application/vnd.api+json;version=2"),
            new Valid("*/*", "*/*"),
            new Valid("text/*", "text/*"),
            new Valid("application/*;q=0.5", "application/*;q=0.5"),
            new Valid("multipart/form-data;boundary=\"----x y\"", "multipart/form-data;boundary=\"----x y\""),
            new Valid("x-custom/x.y-z!#$%&'*+^_`|~1", "x-custom/x.y-z!#$%&'*+^_`|~1"),
            new Valid("text/plain;a=\"tab\there\"", "text/plain;a=\"tab\there\""),
            new Valid("text/plain;" + params(64), "text/plain;" + params(64)),
            new Valid("text/plain;x=" + "a".repeat(1_024 - 13), "text/plain;x=" + "a".repeat(1_024 - 13)));

    static final List<Invalid> INVALID = List.of(
            new Invalid("", "empty"),
            new Invalid("application", "missing '/' and subtype"),
            new Invalid("application/", "missing subtype"),
            new Invalid("/json", "missing type"),
            new Invalid("*/json", "a wildcard type needs a wildcard subtype"),
            new Invalid("*", "missing '/' and subtype"),
            new Invalid("application /json", "expected '/'"),
            new Invalid("application/ json", "missing subtype"),
            new Invalid("application/json;", "empty parameter"),
            new Invalid("application/json;;charset=utf-8", "empty parameter"),
            new Invalid("application/json;charset", "without '='"),
            new Invalid("application/json;charset=", "without a value"),
            new Invalid("application/json;charset =utf-8", "without '='"),
            new Invalid("application/json;charset= utf-8", "without a value"),
            new Invalid("application/json;charset=utf-8;CHARSET=utf-8", "given twice"),
            new Invalid("text/plain;a=1;a=2", "given twice"),
            new Invalid("application/json\n", "control character"),
            new Invalid("application/json;a=\"\u0000\"", "control character"),
            new Invalid("application/json;a=\u001b", "control character"),
            new Invalid("application/json\u007f", "control character"),
            new Invalid("application/jsön", "non-ASCII"),
            new Invalid("application/json;a=\"\u00e9\"", "non-ASCII"),
            new Invalid("application/json;a=\"open", "unterminated quoted string"),
            new Invalid("application/json;a=\"open\\\"", "unterminated quoted string"),
            new Invalid("text/plain;" + params(65), "more than 64 parameters"),
            new Invalid("text/plain;x=" + "a".repeat(1_025 - 13), "longer than 1024 characters"),
            new Invalid(" application/json", "missing type"),
            new Invalid("application/json ", "whitespace at the end"),
            new Invalid("application/json, text/html", "expected ';'"),
            new Invalid("application/json;a=b c", "expected ';'"),
            new Invalid("application/json;a=b\"c\"", "expected ';'"),
            new Invalid("text/plain;a=\"x\"y", "expected ';'"),
            new Invalid("application/json/x", "expected ';'"),
            new Invalid("text/(comment)", "missing subtype"),
            new Invalid("a@b/c", "expected '/'"));

    /** {@code p1=v;p2=v;...} with {@code n} parameters. */
    static String params(int n) {
        var out = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            out.append(i == 1 ? "" : ";").append("p").append(i).append("=v");
        }
        return out.toString();
    }

    static Suite tests() {
        return suite("MediaType",
                parameterized("valid media types parse to their canonical form", VALID, Valid::text, c -> {
                    var type = MediaType.parse(c.text());
                    assertEquals(c.canonical(), type.toString());
                    assertEquals(type, MediaType.parse(type.toString()), "the canonical form parses back");
                }),
                parameterized("invalid media types are rejected with a short, escaped message", INVALID, Invalid::text,
                        c -> {
                            var e = assertThrows(IllegalArgumentException.class, () -> MediaType.parse(c.text()));
                            assertTrue(e.getMessage().contains(c.reason()), e.getMessage());
                            assertTrue(e.getMessage().length() < 200, "short: " + e.getMessage().length());
                            for (char ch : e.getMessage().toCharArray()) {
                                assertTrue(ch >= 0x20 && ch < 0x7F, "printable ASCII: " + e.getMessage());
                            }
                        }),
                test("tables: at least 20 valid and 20 invalid cases", () -> {
                    assertTrue(VALID.size() >= 20);
                    assertTrue(INVALID.size() >= 20);
                }),
                test("type, subtype and parameter names are lowercase; values keep their case except charset", () -> {
                    var type = MediaType.parse("Text/Plain;Format=Flowed;CharSet=UTF-8;Title=\"Ab\"");
                    assertEquals("text", type.type());
                    assertEquals("plain", type.subtype());
                    assertEquals(List.of("format", "charset", "title"), List.copyOf(type.parameters().keySet()));
                    assertEquals("Flowed", type.parameters().get("format"));
                    assertEquals(Optional.of("utf-8"), type.charset());
                    assertEquals("Ab", type.parameters().get("title"));
                    assertEquals(MediaType.parse("text/plain;charset=utf-8"), MediaType.parse("TEXT/PLAIN;charset=\"UTF-8\""));
                    assertThrows(UnsupportedOperationException.class, () -> type.parameters().put("x", "y"));
                }),
                test("quoted-string: escapes are removed, and toString quotes what is not a token", () -> {
                    var type = MediaType.parse("text/plain;a=\"x\\\"y\\\\z\";b=\"tok\";c=\"two words\"");
                    assertEquals("x\"y\\z", type.parameters().get("a"));
                    assertEquals("tok", type.parameters().get("b"));
                    assertEquals("two words", type.parameters().get("c"));
                    assertEquals("text/plain;a=\"x\\\"y\\\\z\";b=tok;c=\"two words\"", type.toString());
                }),
                test("a quoted-pair can escape every visible character and a space", () -> {
                    assertEquals("a b", MediaType.parse("text/plain;t=\"a\\ b\"").parameters().get("t"));
                }),
                test("q has no special meaning in a media type", () ->
                        assertEquals("0.5", MediaType.parse("text/plain;q=0.5").parameters().get("q"))),
                test("isJson: application/json and application/*+json only", () -> {
                    assertTrue(MediaType.parse("application/json").isJson());
                    assertTrue(MediaType.parse("application/json;charset=utf-8").isJson());
                    assertTrue(MediaType.parse("application/problem+json").isJson());
                    assertTrue(MediaType.parse("application/vnd.example+json").isJson());
                    assertFalse(MediaType.parse("application/+json").isJson());
                    assertFalse(MediaType.parse("text/json").isJson());
                    assertFalse(MediaType.parse("application/json+xml").isJson());
                    assertFalse(MediaType.parse("application/jsonx").isJson());
                    assertFalse(MediaType.parse("application/*").isJson());
                    assertFalse(MediaType.ALL.isJson());
                    assertFalse(MediaType.parse("application/xml").isJson());
                }),
                test("includes: wildcards and parameters", () -> {
                    var json = MediaType.APPLICATION_JSON;
                    assertTrue(MediaType.ALL.includes(json));
                    assertTrue(MediaType.parse("application/*").includes(json));
                    assertFalse(MediaType.parse("text/*").includes(json));
                    assertTrue(json.includes(json));
                    assertTrue(json.includes(MediaType.parse("application/json;charset=utf-8")));
                    assertFalse(MediaType.parse("application/json;charset=utf-8").includes(json));
                    assertTrue(MediaType.parse("text/html;level=1").includes(MediaType.parse("text/html;level=1;a=b")));
                    assertFalse(MediaType.parse("text/html;level=1").includes(MediaType.parse("text/html;level=2")));
                    assertFalse(json.includes(MediaType.ALL));
                    assertFalse(json.includes(MediaType.parse("application/*")));
                    assertTrue(MediaType.parse("*/*;a=b").includes(MediaType.parse("x/y;a=b")));
                }),
                test("constants and of()", () -> {
                    assertEquals("application/json", MediaType.APPLICATION_JSON.toString());
                    assertEquals("application/problem+json", MediaType.APPLICATION_PROBLEM_JSON.toString());
                    assertEquals("text/plain", MediaType.TEXT_PLAIN.toString());
                    assertEquals("*/*", MediaType.ALL.toString());
                    assertEquals(MediaType.APPLICATION_JSON, MediaType.of("Application", "JSON"));
                    assertThrows(IllegalArgumentException.class, () -> MediaType.of("*", "json"));
                    assertThrows(IllegalArgumentException.class, () -> MediaType.of("a b", "c"));
                    assertThrows(IllegalArgumentException.class, () -> MediaType.of("a", ""));
                    assertThrows(IllegalArgumentException.class,
                            () -> new MediaType("a", "b", Map.of("charset", "x", "CHARSET", "y")));
                    assertThrows(IllegalArgumentException.class, () -> new MediaType("a", "b", Map.of("n", "\n")));
                    assertThrows(IllegalArgumentException.class, () -> new MediaType("a", "b", Map.of("n m", "v")));
                    assertTrue(MediaType.ALL.isWildcard());
                    assertTrue(MediaType.parse("text/*").isWildcard());
                    assertFalse(MediaType.TEXT_PLAIN.isWildcard());
                }),
                test("the message quotes at most 40 characters of the input, escaped", () -> {
                    var e = assertThrows(IllegalArgumentException.class,
                            () -> MediaType.parse("text/plain;a=\"\u001b[31m" + "x".repeat(100)));
                    assertTrue(e.getMessage().contains("\\x1b[31m"), e.getMessage());
                    assertFalse(e.getMessage().contains("x".repeat(41)), e.getMessage());
                }));
    }
}
