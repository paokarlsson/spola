package spola.client;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.URI;
import java.util.List;
import spola.json.JsonObject;
import spola.json.JsonString;
import spola.test.Suite;

/**
 * Criterion 11: markers in x-api-key, in Authorization, in a request header, in the query and in
 * the body never show in toString, messages, printed stack traces (with causes) or the
 * IllegalArgumentExceptions of the setup; the query's marker is in uri().
 */
final class SecretTests {

    private SecretTests() {
    }

    static final String KEY_MARKER = "QKEYMARKERQ";
    static final String AUTH_MARKER = "QAUTHMARKERQ";
    static final String TRACE_MARKER = "QTRACEMARKERQ";
    static final String QUERY_MARKER = "QQUERYMARKERQ";
    static final String BODY_MARKER = "QBODYMARKERQ";

    static final List<String> MARKERS = List.of(KEY_MARKER, AUTH_MARKER, TRACE_MARKER, QUERY_MARKER, BODY_MARKER);

    static final Failures.Setup SECRET = new Failures.Setup(
            List.of("x-api-key", "sk-" + KEY_MARKER, "Authorization", "Bearer " + AUTH_MARKER),
            List.of("X-Trace", TRACE_MARKER), "/v1/items?token=" + QUERY_MARKER + "&page=2",
            JsonObject.of("prompt", new JsonString(BODY_MARKER)));

    static void assertNoMarker(String where, String text) {
        for (var marker : MARKERS) {
            assertFalse(text.contains(marker), where + " shows " + marker + ": " + text);
        }
    }

    static Suite tests() {
        return suite("secrets",
                test("RestClient.toString and RestRequest.toString show names, not values, and no query", () -> {
                    try (var client = SECRET.builder(URI.create("https://api.example.com/api")).build()) {
                        var request = SECRET.request(client);
                        assertNoMarker("RestClient.toString", client.toString());
                        assertNoMarker("RestRequest.toString", request.toString());
                        assertEquals("RestClient[baseUri https://api.example.com/api, defaultHeaders [x-api-key,"
                                + " Authorization], timeout 30000 ms, connectTimeout 10000 ms, maxResponseSize 4194304]",
                                client.toString());
                        assertEquals("RestRequest[POST https://api.example.com/api/v1/items, headers [x-api-key,"
                                + " Authorization, X-Trace, Content-Type, Accept], body 25 bytes]", request.toString());
                    }
                }),
                parameterized("no marker in getMessage, toString or the printed stack trace of any failure",
                        Failures.all(), kind -> {
                            var e = kind.make().make(SECRET);
                            assertNoMarker(kind + " getMessage", e.getMessage());
                            assertNoMarker(kind + " toString", e.toString());
                            assertNoMarker(kind + " stack trace", Support.printed(e));
                            assertNoMarker(kind + " getLocalizedMessage", e.getLocalizedMessage());
                            assertTrue(e.uri().toString().contains(QUERY_MARKER), "uri() has the query: " + e.uri());
                            assertTrue(e.uri().getRawQuery().contains(QUERY_MARKER));
                        }),
                test("the echo server: the JDK's message quotes the request line, so the cause is a stand-in", () -> {
                    var kind = Failures.all().stream().filter(k -> k.name().endsWith("echoes the request")).findFirst()
                            .orElseThrow();
                    var e = kind.make().make(SECRET);
                    var cause = e.getCause();
                    assertEquals(java.io.IOException.class, cause.getClass());
                    assertTrue(cause.getMessage().startsWith("java.net.ProtocolException"), cause.getMessage());
                    assertTrue(cause.getStackTrace().length > 0, "the stand-in keeps the stack trace");
                    // without a secret in it, the same failure keeps the JDK's exception
                    var plain = kind.make().make(new Failures.Setup(List.of("x-api-key", "plain-key"),
                            List.of("X-Trace", "trace-1"), "/v1/items", JsonObject.of()));
                    assertEquals(java.net.ProtocolException.class, plain.getCause().getClass());
                }),
                test("a server that sends the request body back as its status line: the cause is a stand-in", () -> {
                    var kind = Failures.all().stream().filter(k -> k.name().endsWith("echoes the request body"))
                            .findFirst().orElseThrow();
                    var e = kind.make().make(SECRET);
                    assertEquals(java.io.IOException.class, e.getCause().getClass());
                    assertTrue(e.getMessage().endsWith("(java.net.ProtocolException)"), e.getMessage());
                }),
                test("Causes.safe keeps a clean failure and replaces one that quotes a secret anywhere", () -> {
                    var clean = new java.io.IOException("connection reset");
                    assertTrue(Causes.safe(clean, List.of("secret")) == clean);
                    var quoting = new java.io.IOException("outer", new IllegalStateException("inner secret"));
                    var standIn = Causes.safe(quoting, List.of("secret"));
                    assertFalse(Support.printed(standIn).contains("secret"));
                    var suppressed = new java.io.IOException("outer");
                    suppressed.addSuppressed(new java.io.IOException("the secret"));
                    assertFalse(Support.printed(Causes.safe(suppressed, List.of("secret"))).contains("secret"));
                    assertTrue(Causes.safe(clean, List.of("")) == clean, "an empty value is no secret");
                }),
                test("IllegalArgumentException from build() does not quote header values", () -> {
                    var e = assertThrows(IllegalArgumentException.class, () -> RestClient.builder()
                            .baseUri(URI.create("https://h"))
                            .defaultHeader("x-api-key", "sk-" + KEY_MARKER + "\r\n")
                            .defaultHeader("Authorization", " Bearer " + AUTH_MARKER)
                            .defaultHeader("X-Trace", TRACE_MARKER + "ö")
                            .defaultHeader("X-Other", "\0" + BODY_MARKER)
                            .build());
                    assertNoMarker("build()", e.getMessage());
                    assertNoMarker("build() stack trace", Support.printed(e));
                    assertEquals(4L, e.getMessage().lines().count(), e.getMessage());
                }),
                test("IllegalArgumentException from build() does not quote userinfo or a query of the base URI", () -> {
                    var e = assertThrows(IllegalArgumentException.class, () -> RestClient.builder()
                            .baseUri(URI.create("https://user:" + KEY_MARKER + "@h/api?key=" + QUERY_MARKER + "#" + AUTH_MARKER))
                            .build());
                    assertNoMarker("build()", e.getMessage());
                }),
                test("IllegalArgumentException from header(...) does not quote the value", () -> {
                    try (var client = RestClient.builder().baseUri(URI.create("https://h")).build()) {
                        for (var value : List.of(KEY_MARKER + "\n", KEY_MARKER + "\r", KEY_MARKER + "\0x",
                                KEY_MARKER + "é", " " + KEY_MARKER, KEY_MARKER + "\t")) {
                            var e = assertThrows(IllegalArgumentException.class,
                                    () -> client.get("/x").header("x-api-key", value));
                            assertNoMarker("header(...)", e.getMessage());
                            assertTrue(e.getMessage().contains("x-api-key"), e.getMessage());
                        }
                        var named = assertThrows(IllegalArgumentException.class,
                                () -> client.get("/x").header("Authorization: Bearer " + AUTH_MARKER, "v"));
                        assertNoMarker("header(...) with a name and value in one", named.getMessage());
                        assertTrue(named.getMessage().contains("\"Authorization\" is followed by ':'"), named.getMessage());
                    }
                }),
                test("IllegalArgumentException from a path does not quote its query or fragment", () -> {
                    try (var client = RestClient.builder().baseUri(URI.create("https://h")).build()) {
                        for (var path : List.of("v1/x?key=" + QUERY_MARKER, "/v1/x?key=" + QUERY_MARKER + "#f",
                                "/v1/x#" + QUERY_MARKER, "/v1/x?key=" + QUERY_MARKER + " space",
                                "/v 1/x?key=" + QUERY_MARKER)) {
                            var e = assertThrows(IllegalArgumentException.class, () -> client.get(path));
                            assertNoMarker("path " + path, e.getMessage());
                        }
                    }
                }));
    }
}
