package spola.client;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import spola.json.JsonObject;
import spola.test.Suite;

/** Criterion 12: every problem in build(), and the same checks in header(...) and in paths. */
final class SetupTests {

    private SetupTests() {
    }

    static final URI BASE = URI.create("https://api.example.com");

    /** build() with {@code setup} applied to a builder with a valid base; returns the message. */
    static String problems(UnaryOperator<RestClient.Builder> setup) {
        var e = assertThrows(IllegalArgumentException.class, () -> setup.apply(RestClient.builder().baseUri(BASE)).build());
        return e.getMessage();
    }

    static void oneProblem(UnaryOperator<RestClient.Builder> setup, String expected) {
        var message = problems(setup);
        assertEquals(1L, message.lines().count(), message);
        assertTrue(message.contains(expected), "expected " + expected + " in: " + message);
    }

    /** {@code name} in mixed case: every other letter upper-case, starting with lower. */
    static String mixed(String name) {
        var out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            var c = String.valueOf(name.charAt(i));
            out.append(i % 2 == 0 ? c.toLowerCase(Locale.ROOT) : c.toUpperCase(Locale.ROOT));
        }
        return out.toString();
    }

    /** Values that are not allowed, with what the message says. */
    static List<List<String>> badValues() {
        return List.of(
                List.of("CR", "a\rb", "U+000D (CR) at index 1"),
                List.of("LF", "a\nb", "U+000A (LF) at index 1"),
                List.of("NUL", "a\0b", "U+0000 (NUL) at index 1"),
                List.of("DEL", "a\u007Fb", "U+007F at index 1"),
                List.of("outside ASCII", "nyckel-å", "a character outside ASCII at index 7"),
                List.of("obs-text Latin-1", "a b", "a character outside ASCII at index 1"),
                List.of("a space first", " v", "starts with a space or tab"),
                List.of("a space last", "v ", "ends with a space or tab"),
                List.of("a tab first", "\tv", "starts with a space or tab"),
                List.of("a tab last", "v\t", "ends with a space or tab"));
    }

    static List<List<String>> badNames() {
        return List.of(
                List.of("empty", "", "a header name is empty"),
                List.of("a space", "x api key", "\"x\" is followed by U+0020 (space)"),
                List.of("a colon", "x-api-key:", "\"x-api-key\" is followed by ':'"),
                List.of("CR", "x\r", "\"x\" is followed by U+000D (CR)"),
                List.of("outside ASCII", "nyckel-å", "\"nyckel-\" is followed by a character outside ASCII"),
                List.of("a separator first", "(x)", "it starts with '('"),
                List.of("a slash", "a/b", "\"a\" is followed by '/'"));
    }

    static Suite tests() {
        return suite("setup",
                test("a missing baseUri", () -> {
                    var e = assertThrows(IllegalArgumentException.class, () -> RestClient.builder().build());
                    assertEquals("baseUri is missing", e.getMessage());
                }),
                parameterized("an invalid baseUri", List.of(
                        List.of("ftp://files.example.com", "baseUri has the scheme ftp: only http and https are supported"),
                        List.of("FTP://files.example.com", "baseUri has the scheme FTP"),
                        List.of("/api", "baseUri is relative"),
                        List.of("api.example.com", "baseUri is relative"),
                        List.of("https:///api", "baseUri has no host"),
                        List.of("mailto:ops@example.com", "baseUri has the scheme mailto"),
                        List.of("https://user:pw@api.example.com", "baseUri has userinfo"),
                        List.of("https://api.example.com/?key=1", "baseUri has a query"),
                        List.of("https://api.example.com/api#top", "baseUri has a fragment"),
                        List.of("http://h:0", "baseUri has the port 0"),
                        List.of("http://h:70000", "baseUri has the port 70000"),
                        List.of("https://h/%C3%A5pi/å", "baseUri has a path with a character outside ASCII")),
                        row -> row.getFirst(), row -> {
                            var e = assertThrows(IllegalArgumentException.class,
                                    () -> RestClient.builder().baseUri(new URI(row.get(0))).build());
                            assertTrue(e.getMessage().contains(row.get(1)), e.getMessage());
                        }),
                parameterized("an invalid header name", badNames(), row -> row.getFirst(), row -> {
                    oneProblem(b -> b.defaultHeader(row.get(1), "v"), "defaultHeader: a header name is "
                            + (row.get(1).isEmpty() ? "empty" : "not a token"));
                    oneProblem(b -> b.defaultHeader(row.get(1), "v"), row.get(2));
                }),
                parameterized("an invalid header value", badValues(), row -> row.getFirst(), row -> {
                    oneProblem(b -> b.defaultHeader("x-api-key", row.get(1)),
                            "defaultHeader: the value of header x-api-key " + (row.get(2).startsWith("starts")
                                    || row.get(2).startsWith("ends") ? "" : "has ") + row.get(2));
                }),
                test("an empty value, inner spaces and tabs, and every visible ASCII character are allowed", () -> {
                    var visible = new StringBuilder();
                    for (char c = 0x21; c <= 0x7E; c++) {
                        visible.append(c);
                    }
                    try (var client = RestClient.builder().baseUri(BASE).defaultHeader("x-empty", "")
                            .defaultHeader("x-inner", "a b\tc").defaultHeader("x-visible", visible.toString()).build()) {
                        assertTrue(client.toString().contains("x-visible"));
                    }
                }),
                parameterized("every refused name, in mixed case", HeaderRules.FORBIDDEN, name -> {
                    var mixed = mixed(name);
                    oneProblem(b -> b.defaultHeader(mixed, "v"), "defaultHeader: header " + mixed + " is not allowed");
                    oneProblem(b -> b.defaultHeader(name.toUpperCase(Locale.ROOT), "v"), "is not allowed");
                    var e = assertThrows(IllegalArgumentException.class, () -> RestClient.builder().baseUri(BASE).build()
                            .get("/x").header(mixed, "v"));
                    assertTrue(e.getMessage().contains("header " + mixed + " is not allowed"), e.getMessage());
                }),
                test("the same name twice in another case", () -> {
                    oneProblem(b -> b.defaultHeader("x-api-key", "a").defaultHeader("X-API-Key", "b"),
                            "defaultHeader: header X-API-Key is given twice (first as x-api-key)");
                    oneProblem(b -> b.defaultHeader("Accept", "a").defaultHeader("accept", "a"), "given twice");
                }),
                parameterized("a timeout of zero or below", List.of(Duration.ZERO, Duration.ofMillis(-1),
                        Duration.ofSeconds(-30)), duration -> {
                            oneProblem(b -> b.timeout(duration), "timeout must be positive, was " + duration);
                            oneProblem(b -> b.connectTimeout(duration), "connectTimeout must be positive, was " + duration);
                        }),
                test("maxResponseSize(0) and below", () -> {
                    oneProblem(b -> b.maxResponseSize(0), "maxResponseSize must be at least 1 byte, was 0");
                    oneProblem(b -> b.maxResponseSize(-5), "maxResponseSize must be at least 1 byte, was -5");
                    try (var client = RestClient.builder().baseUri(BASE).maxResponseSize(1).build()) {
                        assertTrue(client.toString().contains("maxResponseSize 1]"), client.toString());
                    }
                }),
                test("several problems give one line per problem, in order", () -> {
                    var e = assertThrows(IllegalArgumentException.class, () -> RestClient.builder()
                            .baseUri(URI.create("ftp://user@h?x=1"))
                            .defaultHeader("Host", "h")
                            .defaultHeader("x-key", "a\nb")
                            .defaultHeader("bad name", "v")
                            .defaultHeader("x-key", "c")
                            .defaultHeader("X-KEY", "d")
                            .timeout(Duration.ZERO)
                            .connectTimeout(Duration.ofSeconds(-1))
                            .maxResponseSize(0)
                            .build());
                    var lines = e.getMessage().lines().toList();
                    assertEquals(List.of(
                            "baseUri has the scheme ftp: only http and https are supported",
                            "baseUri has userinfo: credentials belong in a header such as Authorization",
                            "baseUri has a query: give it with the path of each request",
                            "defaultHeader: header Host is not allowed: the client sets it",
                            "defaultHeader: the value of header x-key has U+000A (LF) at index 1 (allowed: visible ASCII,"
                                    + " space and tab)",
                            "defaultHeader: a header name is not a token (RFC 9110 5.6.2): \"bad\" is followed by U+0020"
                                    + " (space)",
                            "defaultHeader: header X-KEY is given twice (first as x-key)",
                            "timeout must be positive, was PT0S",
                            "connectTimeout must be positive, was PT-1S",
                            "maxResponseSize must be at least 1 byte, was 0"), lines);
                }),
                test("header(...) refuses the same names and values at the call", () -> {
                    try (var client = RestClient.builder().baseUri(BASE).build()) {
                        var request = client.post("/x");
                        for (var row : badNames()) {
                            var e = assertThrows(IllegalArgumentException.class, () -> request.header(row.get(1), "v"));
                            assertTrue(e.getMessage().contains(row.get(2)), e.getMessage());
                        }
                        for (var row : badValues()) {
                            var e = assertThrows(IllegalArgumentException.class, () -> request.header("x-key", row.get(1)));
                            assertTrue(e.getMessage().contains(row.get(2)), e.getMessage());
                            assertTrue(e.getMessage().startsWith("the value of header x-key "), e.getMessage());
                        }
                        assertEquals("header content-type is not allowed: json(...) sets it",
                                assertThrows(IllegalArgumentException.class,
                                        () -> request.header("content-type", "text/plain")).getMessage());
                    }
                }),
                test("a path that does not start with /, has a fragment or has invalid characters is refused at the"
                        + " call", () -> {
                    try (var client = RestClient.builder().baseUri(BASE).build()) {
                        var cases = new ArrayList<List<String>>(List.of(
                                List.of("v1/messages", "the path \"v1/messages\" must start with /"),
                                List.of("", "the path \"\" must start with /"),
                                List.of("?q=1", "must start with /"),
                                List.of("/v1/x#top", "the path \"/v1/x\" has a fragment"),
                                List.of("/v1/x?q=1#top", "has a fragment"),
                                List.of("/v1/my items", "has U+0020 (space) at index 6"),
                                List.of("/v1/{id}", "has '{' at index 4"),
                                List.of("/v1/å", "has a character outside ASCII at index 4"),
                                List.of("/v1/%zz", "has '%' at index 4"),
                                List.of("/v1/%4", "has '%' at index 4"),
                                List.of("/v1/a\\b", "has '\\' at index 5"),
                                List.of("/v1/x\r\nHost: evil", "has U+000D (CR) at index 5"),
                                List.of("/v1/x?q=a b", "the query of the path \"/v1/x\" has a character that is not allowed"),
                                List.of("/v1/x?q=%", "the query of the path"),
                                List.of("/v1/x?q=[1]", "the query of the path")));
                        for (var row : cases) {
                            for (var call : List.<spola.test.Body>of(() -> client.get(row.get(0)),
                                    () -> client.post(row.get(0)), () -> client.put(row.get(0)),
                                    () -> client.patch(row.get(0)), () -> client.delete(row.get(0)))) {
                                var e = assertThrows(IllegalArgumentException.class, call);
                                assertTrue(e.getMessage().contains(row.get(1)), row.get(0) + ": " + e.getMessage());
                            }
                        }
                    }
                }),
                test("valid paths: sub-delims, :, @, percent-encoding, an empty query and ? in the query", () -> {
                    try (var client = RestClient.builder().baseUri(BASE).build()) {
                        for (var path : List.of("/", "/v1/x", "/a:b@c!$&'()*+,;=-._~", "/caf%C3%A9", "/x?", "/x?a=1&b=/?c",
                                "//other.example/x", "/x/../y")) {
                            assertTrue(client.get(path).toString().contains("GET "), path);
                        }
                    }
                }),
                test("null gives NullPointerException", () -> {
                    var builder = RestClient.builder();
                    assertThrows(NullPointerException.class, () -> builder.baseUri(null));
                    assertThrows(NullPointerException.class, () -> builder.defaultHeader(null, "v"));
                    assertThrows(NullPointerException.class, () -> builder.defaultHeader("n", null));
                    assertThrows(NullPointerException.class, () -> builder.timeout(null));
                    assertThrows(NullPointerException.class, () -> builder.connectTimeout(null));
                    try (var client = RestClient.builder().baseUri(BASE).build()) {
                        assertThrows(NullPointerException.class, () -> client.get(null));
                        assertThrows(NullPointerException.class, () -> client.post(null));
                        assertThrows(NullPointerException.class, () -> client.put(null));
                        assertThrows(NullPointerException.class, () -> client.patch(null));
                        assertThrows(NullPointerException.class, () -> client.delete(null));
                        var request = client.post("/x");
                        assertThrows(NullPointerException.class, () -> request.header(null, "v"));
                        assertThrows(NullPointerException.class, () -> request.header("n", null));
                        assertThrows(NullPointerException.class, () -> request.json(null));
                        assertThrows(NullPointerException.class, () -> request.json(null, ResponseTests.POINT));
                        assertThrows(NullPointerException.class,
                                () -> request.json(new ResponseTests.Point(1, 2), null));
                        assertThrows(NullPointerException.class, () -> request.retrieve(null));
                    }
                }),
                test("a huge timeout counts as Long.MAX_VALUE nanoseconds and works", () -> {
                    try (var server = Support.serve(request -> spola.http.Response.text(200, "{}"));
                            var client = RestClient.builder().baseUri(Support.base(server.port()))
                                    .timeout(Duration.ofSeconds(Long.MAX_VALUE))
                                    .connectTimeout(Duration.ofSeconds(Long.MAX_VALUE)).build()) {
                        assertEquals(JsonObject.of(), client.get("/x").retrieveJson());
                    }
                }));
    }
}
