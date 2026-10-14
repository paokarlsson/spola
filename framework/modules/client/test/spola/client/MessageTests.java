package spola.client;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.URI;
import java.util.List;
import spola.json.JsonObject;
import spola.test.Suite;

/**
 * Criterion 10, second half: for every kind of failure, getMessage() is one line of printable
 * ASCII of at most 1 000 characters with the method and the URI without its query, and for a
 * response also the status.
 */
final class MessageTests {

    private MessageTests() {
    }

    static void check(Failures.Kind kind, RestClientException e, Failures.Setup setup) {
        var message = e.getMessage();
        assertTrue(Support.printableAscii(message), kind + ": not printable ASCII: " + message);
        assertTrue(message.length() <= RestClientException.MAX_MESSAGE, kind + ": " + message.length() + " characters");
        assertTrue(message.startsWith("POST "), kind + ": " + message);
        var base = URI.create(e.uri().getScheme() + "://" + e.uri().getRawAuthority());
        var shown = setup.shownUri(base);
        var expectedUri = shown.length() <= RestClientException.MAX_MESSAGE_URI ? shown
                : Text.limit(shown, RestClientException.MAX_MESSAGE_URI);
        assertTrue(message.startsWith("POST " + expectedUri + ": "), kind + ": " + message);
        int query = setup.path().indexOf('?');
        assertFalse(message.contains(setup.path().substring(query)), kind + ": the query is in " + message);
        assertEquals("POST", e.method());
        if (kind.status() >= 0) {
            var response = (RestClientResponseException) e;
            assertEquals(kind.status(), response.status(), kind.name());
            assertTrue(message.contains(": status " + kind.status() + ": "), kind + ": " + message);
        } else {
            var transport = (RestClientTransportException) e;
            assertEquals(kind.timedOut(), transport.timedOut(), kind.name());
        }
    }

    static final Failures.Setup LONG_PATH = new Failures.Setup(List.of("x-api-key", "k"), List.of("X-Trace", "t"),
            "/" + "segment/".repeat(400) + "end?page=2", JsonObject.of());

    static Suite tests() {
        return suite("messages",
                parameterized("every kind of failure", Failures.all(), kind -> {
                    var e = kind.make().make(Failures.Setup.PLAIN);
                    check(kind, e, Failures.Setup.PLAIN);
                    assertEquals(URI.create(e.uri().getScheme() + "://" + e.uri().getRawAuthority()
                            + Failures.Setup.PLAIN.path()), e.uri());
                }),
                parameterized("every kind of failure with a path of 3 200 characters: the URI is cut, the status stays",
                        Failures.all(), kind -> {
                            var e = kind.make().make(LONG_PATH);
                            check(kind, e, LONG_PATH);
                        }),
                test("a message cut at 1 000 characters never ends inside a \\u escape", () -> {
                    for (int pad = 0; pad < 6; pad++) {
                        var body = "x".repeat(pad) + "é".repeat(1_000);
                        try (var server = Support.serve(request -> spola.http.Response.bytes(500, "text/plain",
                                body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                                var client = Support.client(server.port())) {
                            var e = (RestClientResponseException) Failures.thrown(client.get("/x"), true);
                            var message = e.getMessage();
                            assertTrue(message.length() <= RestClientException.MAX_MESSAGE, message);
                            assertTrue(message.endsWith("..."), message);
                            assertTrue(message.matches(".*: (x*)(\\\\u00e9)*\\.\\.\\.$"), "cut inside an escape: " + message);
                        }
                    }
                }),
                test("toString is the class name and the message", () -> {
                    var e = Failures.all().getFirst().make().make(Failures.Setup.PLAIN);
                    assertEquals(RestClientResponseException.class.getName() + ": " + e.getMessage(), e.toString());
                }));
    }
}
