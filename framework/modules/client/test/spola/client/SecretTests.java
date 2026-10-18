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
 * marker is in uri(). Servers that send the request back are among the failures: the message and
 * safeExcerpt() hide what they quote, and bodyExcerpt() keeps it. Mission 15d: the credentials of
 * Authorization on their own (after Bearer, bearer, and Basic decoded) are hidden too (K1), and
 * safeExcerpt() is the excerpt to log (K2).
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
            "a 404 problem that sends the path and query back",
            "a 401 that names the bearer token on its own",
            "a 401 that names the token after a lower-case bearer",
            "a 401 that names the decoded user:password from Basic");

    static Failures.Kind kind(String name) {
        return Failures.all().stream().filter(k -> k.name().equals(name)).findFirst().orElseThrow();
    }

    static void assertNoMarker(String where, String text) {
        for (var marker : MARKERS) {
            assertFalse(text.contains(marker), where + " shows " + marker + ": " + text);
        }
    }

    /**
     * A header and what {@code secrets()} has for it.
     *
     * @param name   the row's name
     * @param header the header's name
     * @param value  its value
     * @param hidden what a message hides, in order
     */
    record SecretRow(String name, String header, String value, List<String> hidden) {
    }

    static List<SecretRow> secretRows() {
        var basic = Failures.base64("anna:pw:1");
        var swedish = Failures.base64("anna:lösen");
        return List.of(
                new SecretRow("Bearer", "Authorization", "Bearer sk-1", List.of("Bearer sk-1", "sk-1")),
                new SecretRow("bearer, the name in lower case", "authorization", "bearer sk-1",
                        List.of("bearer sk-1", "sk-1")),
                new SecretRow("Proxy-Authorization", "Proxy-Authorization", "Bearer sk-1", List.of("Bearer sk-1", "sk-1")),
                new SecretRow("PROXY-AUTHORIZATION, two spaces", "PROXY-AUTHORIZATION", "Token  sk-1",
                        List.of("Token  sk-1", "sk-1")),
                new SecretRow("credentials with a space", "Authorization", "Digest a=1, b=2",
                        List.of("Digest a=1, b=2", "a=1, b=2")),
                new SecretRow("no scheme", "Authorization", "sk-1", List.of("sk-1")),
                new SecretRow("Basic", "Authorization", "Basic " + basic, List.of("Basic " + basic, basic, "anna:pw:1", "pw:1")),
                new SecretRow("BASIC", "Authorization", "BASIC " + basic, List.of("BASIC " + basic, basic, "anna:pw:1", "pw:1")),
                new SecretRow("basic without a colon", "Authorization", "basic " + Failures.base64("anna"),
                        List.of("basic " + Failures.base64("anna"), Failures.base64("anna"), "anna")),
                new SecretRow("Basic with an empty password", "Authorization", "Basic " + Failures.base64("anna:"),
                        List.of("Basic " + Failures.base64("anna:"), Failures.base64("anna:"), "anna:")),
                new SecretRow("Basic that is not Base64", "Authorization", "Basic not*base64",
                        List.of("Basic not*base64", "not*base64")),
                new SecretRow("Basic with Swedish letters: as UTF-8 and as ISO-8859-1", "Authorization", "Basic " + swedish,
                        List.of("Basic " + swedish, swedish, "anna:lösen", "lösen", "anna:lÃ¶sen", "lÃ¶sen")),
                new SecretRow("another scheme that starts with Basic", "Authorization", "Basicx " + basic,
                        List.of("Basicx " + basic, basic)),
                new SecretRow("another header", "X-Auth", "Bearer sk-1", List.of("Bearer sk-1")),
                new SecretRow("x-api-key with Basic", "x-api-key", "Basic " + basic, List.of("Basic " + basic)),
                new SecretRow("a name that only starts with Authorization", "Authorization-Extra", "Bearer sk-1",
                        List.of("Bearer sk-1")));
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
                                case "a 401 that names the bearer token on its own",
                                        "a 401 that names the token after a lower-case bearer",
                                        "a 401 that names the decoded user:password from Basic" -> List.of(AUTH_MARKER);
                                default -> List.of(KEY_MARKER, AUTH_MARKER, TRACE_MARKER);
                            };
                            for (var marker : expected) {
                                assertTrue(e.bodyExcerpt().contains(marker), marker + " in " + e.bodyExcerpt());
                            }
                            assertTrue(e.safeExcerpt().contains(Text.HIDDEN), e.safeExcerpt());
                            assertNoMarker(name + " safeExcerpt", e.safeExcerpt());
                        }),
                test("the 401s name the token alone and the decoded user:password, which the messages hide", () -> {
                    var bearer = (RestClientResponseException) kind("a 401 that names the bearer token on its own")
                            .make().make(SECRET);
                    assertTrue(bearer.bodyExcerpt().contains("\"Invalid token: " + AUTH_MARKER + "\""), bearer.bodyExcerpt());
                    assertTrue(bearer.getMessage().endsWith(": status 401: a client error: {\"error\":{\"type\":"
                            + "\"authentication_error\",\"message\":\"Invalid token: <hidden>\"}}"), bearer.getMessage());
                    var lower = (RestClientResponseException) kind("a 401 that names the token after a lower-case bearer")
                            .make().make(SECRET);
                    assertTrue(lower.bodyExcerpt().contains("\"Invalid token: " + AUTH_MARKER + "\""), lower.bodyExcerpt());
                    assertTrue(lower.safeExcerpt().contains("\"Invalid token: <hidden>\""), lower.safeExcerpt());
                    var basic = (RestClientResponseException) kind("a 401 that names the decoded user:password from Basic")
                            .make().make(SECRET);
                    assertTrue(basic.bodyExcerpt().contains("\"Invalid credentials for anna:" + AUTH_MARKER + "\""),
                            basic.bodyExcerpt());
                    assertTrue(basic.safeExcerpt().contains("\"Invalid credentials for <hidden>\""), basic.safeExcerpt());
                }),
                parameterized("the values of a header that the module hides", secretRows(), row -> row.name(), row -> {
                    try (var client = RestClient.builder().baseUri(URI.create("https://h")).build();
                            var withDefault = RestClient.builder().baseUri(URI.create("https://h"))
                                    .defaultHeader(row.header(), row.value()).build()) {
                        assertEquals(row.hidden(), client.get("/x").header(row.header(), row.value()).secrets());
                        assertEquals(row.hidden(), withDefault.get("/x").secrets());
                    }
                }),
                test("a header that is not Authorization is hidden only whole, as before: its part after a space shows",
                        () -> {
                            try (var server = new Support.RawServer(Failures.namesCredentials("X-Auth"));
                                    var client = RestClient.builder().baseUri(server.base()).build()) {
                                var e = (RestClientResponseException) Failures.thrown(
                                        client.get("/x").header("X-Auth", "Bearer " + AUTH_MARKER), true);
                                assertTrue(e.getMessage().contains("Invalid token: " + AUTH_MARKER), e.getMessage());
                                assertTrue(e.safeExcerpt().contains("Invalid token: " + AUTH_MARKER), e.safeExcerpt());
                            }
                            try (var server = new Support.RawServer(Failures.sendsBack(request -> Failures.jsonResponse(401,
                                    "{\"got\":\"" + Failures.headerValue(request, "X-Auth") + "\"}")));
                                    var client = RestClient.builder().baseUri(server.base()).build()) {
                                var e = (RestClientResponseException) Failures.thrown(
                                        client.get("/x").header("X-Auth", "Bearer " + AUTH_MARKER), true);
                                assertTrue(e.getMessage().endsWith(": {\"got\":\"<hidden>\"}"), e.getMessage());
                            }
                        }),
                test("safeExcerpt() hides an echoed header value; bodyExcerpt() is raw", () -> {
                    var e = (RestClientResponseException) kind(
                            "a 200 that sends the request headers back, with a codec that does not fit").make().make(SECRET);
                    for (var marker : List.of(KEY_MARKER, AUTH_MARKER, TRACE_MARKER)) {
                        assertTrue(e.bodyExcerpt().contains(marker), marker + " in " + e.bodyExcerpt());
                    }
                    assertNoMarker("safeExcerpt", e.safeExcerpt());
                    assertTrue(e.safeExcerpt().contains("\"x-api-key\":\"<hidden>\""), e.safeExcerpt());
                    assertTrue(e.safeExcerpt().contains("\"Authorization\":\"<hidden>\""), e.safeExcerpt());
                    assertTrue(e.safeExcerpt().contains("\"X-Trace\":\"<hidden>\""), e.safeExcerpt());
                    assertTrue(e.safeExcerpt().contains("\"Accept\":\"application/json\""), e.safeExcerpt());
                }),
                parameterized("safeExcerpt() of every response failure: no marker, and it is the excerpt in the message",
                        Failures.all().stream().filter(k -> k.status() >= 0).toList(), kind -> {
                            var e = (RestClientResponseException) kind.make().make(SECRET);
                            assertNoMarker(kind + " safeExcerpt", e.safeExcerpt());
                            var message = e.getMessage();
                            var shown = Text.ascii(e.safeExcerpt());
                            if (shown.isEmpty()) {
                                assertEquals("", e.bodyExcerpt());
                            } else if (!message.endsWith(": " + shown)) { // the message was cut inside the excerpt
                                assertEquals(RestClientException.MAX_MESSAGE, message.length(), message);
                                int start = message.indexOf(": " + shown.substring(0, Math.min(20, shown.length())));
                                assertTrue(start > 0, message);
                                assertTrue(shown.startsWith(message.substring(start + 2, message.length() - 3)), message);
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
