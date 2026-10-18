package spola.client;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.net.ssl.SSLHandshakeException;
import spola.json.Json;
import spola.json.JsonObject;
import spola.json.JsonString;
import spola.test.Suite;

/**
 * Criterion 11: markers in x-api-key, in Authorization, in a request header, in the query and in
 * the body (with Swedish letters, as the need's prompts have) never show in toString, messages,
 * printed stack traces (with causes) or the IllegalArgumentExceptions of the setup; the query's
 * marker is in uri(). Servers that send the request back are among the failures: the message
 * hides what they quote, and bodyExcerpt() keeps it.
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
            JsonObject.of("prompt", new JsonString("Hej, hur mår du? " + BODY_MARKER)));

    /** The failures where a server sends back a value of the request that shows in bodyExcerpt(). */
    static final List<String> RESPONSE_ECHOES = List.of("a 422 that sends the request body back",
            "a 200 that sends the request headers back, with a codec that does not fit",
            "a 404 problem that sends the path and query back");

    static Failures.Kind kind(String name) {
        return Failures.all().stream().filter(k -> k.name().equals(name)).findFirst().orElseThrow();
    }

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
                        int size = Json.write(SECRET.body()).getBytes(StandardCharsets.UTF_8).length;
                        assertEquals("RestRequest[POST https://api.example.com/api/v1/items, headers [x-api-key,"
                                + " Authorization, X-Trace, Content-Type, Accept], body " + size + " bytes]",
                                request.toString());
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
                parameterized("a response that sends the request back: the message hides it, bodyExcerpt() keeps it",
                        RESPONSE_ECHOES, name -> {
                            var e = (RestClientResponseException) kind(name).make().make(SECRET);
                            assertTrue(e.getMessage().contains(Text.HIDDEN), e.getMessage());
                            var expected = switch (name) {
                                case "a 422 that sends the request body back" -> List.of(BODY_MARKER);
                                case "a 404 problem that sends the path and query back" -> List.of(QUERY_MARKER);
                                default -> List.of(KEY_MARKER, AUTH_MARKER, TRACE_MARKER);
                            };
                            for (var marker : expected) {
                                assertTrue(e.bodyExcerpt().contains(marker), marker + " in " + e.bodyExcerpt());
                            }
                        }),
                test("a 422 with the body's Swedish text: the message hides the body and keeps the rest", () -> {
                    var e = (RestClientResponseException) kind("a 422 that sends the request body back").make()
                            .make(SECRET);
                    assertTrue(e.getMessage().endsWith(": status 422: a client error: {\"detail\":[{\"type\":\"missing\","
                            + "\"loc\":[\"body\",\"max_tokens\"],\"msg\":\"Field required\",\"input\":<hidden>}]}"),
                            e.getMessage());
                    assertTrue(e.bodyExcerpt().contains("\"input\":{\"prompt\":\"Hej, hur mår du? " + BODY_MARKER + "\"}"),
                            e.bodyExcerpt());
                }),
                parameterized("a long body sent back and cut at the 8 KiB the client reads: the message hides its start",
                        List.of(400, 200), status -> {
                            var body = JsonObject.of("prompt", new JsonString(BODY_MARKER + " " + "Jag mår dåligt. ".repeat(2_000)));
                            var setup = new Failures.Setup(SECRET.defaults(), SECRET.requestHeader(), SECRET.path(), body);
                            try (var server = new Support.RawServer(Failures.sendsBack(request -> Failures.jsonResponse(
                                    status, "{\"input\":" + Failures.requestBody(request) + "}")));
                                    var client = setup.builder(server.base()).build()) {
                                var e = (RestClientResponseException) Failures.thrown(setup.request(client), false);
                                assertEquals(status, e.status());
                                assertNoMarker("getMessage", e.getMessage());
                                assertNoMarker("stack trace", Support.printed(e));
                                assertTrue(e.getMessage().endsWith(": {\"input\":<hidden>..."), e.getMessage());
                                assertTrue(e.bodyExcerpt().startsWith("{\"input\":{\"prompt\":\"" + BODY_MARKER), e.bodyExcerpt());
                            }
                        }),
                test("a server that sends the request back as the response: the cause has no text, only classes", () -> {
                    for (var setup : List.of(SECRET, Failures.Setup.PLAIN)) {
                        var e = kind("a server that echoes the request").make().make(setup);
                        var cause = e.getCause();
                        assertEquals(IOException.class, cause.getClass());
                        assertTrue(cause.getMessage().startsWith(ProtocolException.class.getName() + " ("), cause.getMessage());
                        assertTrue(cause.getStackTrace().length > 0, "the stand-in keeps the stack trace");
                        assertTrue(e.getMessage().endsWith(": the response is not valid HTTP/1.1 ("
                                + ProtocolException.class.getName() + ")"), e.getMessage());
                    }
                }),
                test("a server that sends the body back as its status line, with Swedish letters: only the class", () -> {
                    var e = kind("a server that echoes the request body").make().make(SECRET);
                    assertEquals(IOException.class, e.getCause().getClass());
                    assertTrue(e.getMessage().endsWith("(" + ProtocolException.class.getName() + ")"), e.getMessage());
                }),
                test("a server that sends part of the request line and closes: the chain of classes, no text", () -> {
                    var e = kind("a server that sends part of the request line and closes").make().make(SECRET);
                    assertTrue(e.getMessage().endsWith("(java.io.IOException, caused by java.io.EOFException)"),
                            e.getMessage());
                    var printed = Support.printed(e);
                    assertTrue(printed.contains("Caused by: java.io.IOException: java.io.EOFException ("), printed);
                    assertFalse(printed.contains("POST /v1/items"), "the request line: " + printed);
                }),
                test("Causes: only socket, TLS and timeout failures keep their text, and only without a value", () -> {
                    var secrets = List.of("secret");
                    assertTrue(Causes.textIsSafe(new ConnectException("Connection refused"), secrets));
                    assertTrue(Causes.textIsSafe(new SSLHandshakeException("PKIX path building failed"), secrets));
                    assertTrue(Causes.textIsSafe(new HttpConnectTimeoutException("HTTP connect timed out"), secrets));
                    assertFalse(Causes.textIsSafe(new ConnectException("the secret"), secrets));
                    var suppressed = new ConnectException("outer");
                    suppressed.addSuppressed(new IOException("the secret"));
                    assertFalse(Causes.textIsSafe(suppressed, secrets));
                    assertFalse(Causes.textIsSafe(new SSLHandshakeException("outer", new IOException("the secret")),
                            secrets));
                    assertFalse(Causes.textIsSafe(new ProtocolException("Invalid status line: \"x\""), secrets));
                    assertFalse(Causes.textIsSafe(new IOException("parsing HTTP/1.1 status line"), secrets));
                    assertFalse(Causes.textIsSafe(new NumberFormatException("For input string: \"x\""), secrets));
                    assertTrue(Causes.textIsSafe(new ConnectException("Connection refused"), List.of("")),
                            "an empty value is no secret");
                }),
                test("Causes.withoutText copies the chain of classes and stack traces without any text", () -> {
                    var root = new EOFException("the secret root");
                    var failure = new IOException("parsing HTTP/1.1 status line, receiving [the secret]", root);
                    var copy = Causes.withoutText(failure);
                    var printed = Support.printed(copy);
                    assertFalse(printed.contains("secret"), printed);
                    assertTrue(copy.getMessage().startsWith("java.io.IOException ("), copy.getMessage());
                    assertTrue(copy.getCause().getMessage().startsWith("java.io.EOFException ("));
                    assertEquals(null, copy.getCause().getCause());
                    assertEquals(List.of(failure.getStackTrace()), List.of(copy.getStackTrace()));
                    assertEquals(List.of(root.getStackTrace()), List.of(copy.getCause().getStackTrace()));
                    assertEquals("java.io.IOException, caused by java.io.EOFException", Causes.describe(failure, false));
                    assertEquals("java.net.ConnectException: Connection refused",
                            Causes.describe(new ConnectException("Connection refused"), true));
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
