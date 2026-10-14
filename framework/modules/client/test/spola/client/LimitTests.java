package spola.client;

import static spola.client.Support.ascii;
import static spola.client.Support.head;
import static spola.client.Support.since;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertNull;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Function;
import spola.http.Response;
import spola.json.JsonString;
import spola.json.codec.JsonCodecs;
import spola.test.Suite;

/** Criterion 7: maxResponseSize, with Content-Length, chunked and bodies that never end. */
final class LimitTests {

    private LimitTests() {
    }

    static final int LIMIT = 1024;

    /** A JSON string of exactly {@code size} bytes: {@code "aaa...a"}. */
    static byte[] jsonOf(int size) {
        return ascii("\"" + "a".repeat(size - 2) + "\"");
    }

    static RestClient limited(int port) {
        return RestClient.builder().baseUri(Support.base(port)).maxResponseSize(LIMIT).build();
    }

    /** spola.http answers with {@code body}, with Content-Length or chunked (a streaming body). */
    static Response answer(byte[] body, boolean chunked) {
        return chunked
                ? Response.stream(200, "application/json", out -> {
                    out.write(body, 0, body.length / 2);
                    out.flush();
                    out.write(body, body.length / 2, body.length - body.length / 2);
                })
                : Response.bytes(200, "application/json", body);
    }

    static Suite tests() {
        Function<Boolean, String> how = chunked -> chunked ? "chunked" : "with Content-Length";
        return suite("maxResponseSize",
                test("a body of exactly 1 024 bytes goes through, with Content-Length and chunked", () -> {
                    for (boolean chunked : new boolean[] {false, true}) {
                        var body = jsonOf(LIMIT);
                        try (var server = Support.serve(request -> answer(body, chunked));
                                var client = limited(server.port())) {
                            assertEquals(new JsonString("a".repeat(LIMIT - 2)), client.get("/x").retrieveJson(),
                                    how.apply(chunked));
                            assertEquals("a".repeat(LIMIT - 2), client.get("/x").retrieve(JsonCodecs.STRING));
                            assertEquals(200, client.get("/x").retrieveBodiless());
                        }
                    }
                }),
                test("a body of 1 025 bytes fails, with Content-Length and chunked, in every retrieve", () -> {
                    for (boolean chunked : new boolean[] {false, true}) {
                        var body = jsonOf(LIMIT + 1);
                        try (var server = Support.serve(request -> answer(body, chunked));
                                var client = limited(server.port())) {
                            for (var call : java.util.List.<spola.test.Body>of(
                                    () -> client.get("/x").retrieveJson(),
                                    () -> client.get("/x").retrieve(JsonCodecs.STRING),
                                    () -> client.get("/x").retrieveBodiless())) {
                                var e = assertThrows(RestClientResponseException.class, call);
                                assertEquals(200, e.status(), how.apply(chunked));
                                assertTrue(e.getMessage().contains("larger than maxResponseSize"), e.getMessage());
                                assertNull(e.getCause());
                                // with Content-Length nothing is read; chunked stops at the limit
                                assertEquals(chunked ? "\"" + "a".repeat(999) + "..." : "", e.bodyExcerpt());
                            }
                        }
                    }
                }),
                test("Content-Length: 10000000000 fails at once; the server never sends the body", () -> {
                    try (var server = new Support.RawServer((socket, raw) -> {
                        var in = socket.getInputStream();
                        if (raw.readRequest(in) != null) {
                            socket.getOutputStream().write(ascii(head(200, "Content-Type", "application/json",
                                    "Content-Length", "10000000000")));
                            socket.getOutputStream().flush();
                            raw.awaitClose(in);
                        }
                    }); var client = RestClient.builder().baseUri(server.base()).maxResponseSize(LIMIT)
                            .timeout(Duration.ofSeconds(30)).build()) {
                        long start = System.nanoTime();
                        var e = assertThrows(RestClientResponseException.class, () -> client.get("/x").retrieveJson());
                        assertTrue(since(start) < 2_000, "took " + since(start) + " ms");
                        assertEquals(200, e.status());
                        assertEquals("", e.bodyExcerpt());
                        assertTrue(e.getMessage().contains("Content-Length 10000000000"), e.getMessage());
                        awaitClosed(server, 1, start);
                    }
                }),
                test("a chunked body that never ends fails within 2 s and the connection is closed", () -> {
                    try (var server = new Support.RawServer((socket, raw) -> {
                        var in = socket.getInputStream();
                        if (raw.readRequest(in) != null) {
                            var out = socket.getOutputStream();
                            out.write(ascii(head(200, "Content-Type", "application/json", "Transfer-Encoding", "chunked")));
                            out.write(ascii("1\r\n[\r\n"));
                            raw.trickle(out, ascii("40\r\n" + "1,".repeat(32) + "\r\n"), 5);
                        }
                    }); var client = RestClient.builder().baseUri(server.base()).maxResponseSize(LIMIT)
                            .timeout(Duration.ofSeconds(30)).build()) {
                        long start = System.nanoTime();
                        var e = assertThrows(RestClientResponseException.class, () -> client.get("/x").retrieveJson());
                        assertTrue(since(start) < 2_000, "took " + since(start) + " ms");
                        assertEquals(200, e.status());
                        assertTrue(e.bodyExcerpt().startsWith("[1,1,1,"), e.bodyExcerpt());
                        awaitClosed(server, 1, start);
                    }
                }),
                test("with timeout 30 s, a 500 with a body that never ends fails within 2 s; the excerpt is at most"
                        + " 1 000 characters plus ...", () -> {
                    try (var server = new Support.RawServer((socket, raw) -> {
                        var in = socket.getInputStream();
                        if (raw.readRequest(in) != null) {
                            var out = socket.getOutputStream();
                            out.write(ascii(head(500, "Content-Type", "text/plain", "Transfer-Encoding", "chunked")));
                            raw.trickle(out, ascii("400\r\n" + "x".repeat(1024) + "\r\n"), 0);
                        }
                    }); var client = RestClient.builder().baseUri(server.base()).timeout(Duration.ofSeconds(30)).build()) {
                        long start = System.nanoTime();
                        var e = assertThrows(RestClientResponseException.class, () -> client.get("/x").retrieveJson());
                        assertTrue(since(start) < 2_000, "took " + since(start) + " ms");
                        assertEquals(500, e.status());
                        assertEquals("x".repeat(1_000) + "...", e.bodyExcerpt());
                        assertTrue(e.getMessage().length() <= RestClientException.MAX_MESSAGE);
                        awaitClosed(server, 1, start);
                    }
                }),
                test("a 500 with Content-Length over the limit is not read", () -> {
                    try (var server = new Support.RawServer((socket, raw) -> {
                        var in = socket.getInputStream();
                        if (raw.readRequest(in) != null) {
                            socket.getOutputStream().write(ascii(head(500, "Content-Length", "2000")));
                            socket.getOutputStream().flush();
                            raw.awaitClose(in);
                        }
                    }); var client = RestClient.builder().baseUri(server.base()).maxResponseSize(LIMIT).build()) {
                        var e = assertThrows(RestClientResponseException.class, () -> client.get("/x").retrieveBodiless());
                        assertEquals(500, e.status());
                        assertEquals("", e.bodyExcerpt());
                    }
                }),
                test("an error body is read up to its first 8 KiB, also with the default limit", () -> {
                    var body = "e".repeat(20_000);
                    try (var server = Support.serve(request -> Response.text(503, body));
                            var client = Support.client(server.port())) {
                        var e = assertThrows(RestClientResponseException.class, () -> client.get("/x").retrieveJson());
                        assertEquals(503, e.status());
                        assertEquals("e".repeat(1_000) + "...", e.bodyExcerpt());
                    }
                }),
                test("JSON is parsed with maxInputSize = maxResponseSize: a 5 MB body passes with an 8 MiB limit", () -> {
                    var element = "\"" + "b".repeat(55) + "\"";
                    var big = "[" + (element + ",").repeat(89_999) + element + "]";
                    assertTrue(big.length() > 5_000_000, "over JsonLimits.DEFAULT's 4 MiB");
                    try (var server = Support.serve(request -> Response.bytes(200, "application/json", ascii(big)));
                            var client = RestClient.builder().baseUri(Support.base(server.port()))
                                    .maxResponseSize(8 << 20).build()) {
                        var json = (spola.json.JsonArray) client.get("/x").retrieveJson();
                        assertEquals(90_000, json.size());
                    }
                }));
    }

    /** Waits up to 5 s from {@code start} until the server has seen {@code count} connections close. */
    static void awaitClosed(Support.RawServer server, int count, long start) throws IOException, InterruptedException {
        while (server.closedAt.size() < count && since(start) < 5_000) {
            Thread.sleep(10);
        }
        assertTrue(server.closedAt.size() >= count, "the server saw " + server.closedAt.size()
                + " connections close within 5 s");
    }
}
