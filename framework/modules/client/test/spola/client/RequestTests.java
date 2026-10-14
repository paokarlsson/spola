package spola.client;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import spola.http.HttpServer;
import spola.http.Request;
import spola.http.Response;
import spola.json.JsonNumber;
import spola.json.JsonObject;
import spola.json.codec.JsonCodec;
import spola.json.codec.JsonCodecs;
import spola.json.codec.JsonMappingException;
import spola.test.Suite;

/** Criterion 13: what the server receives. */
final class RequestTests {

    private RequestTests() {
    }

    /**
     * A request as the server saw it.
     *
     * @param method  the method
     * @param target  the request-target
     * @param headers the headers as {@code name: value} lines
     * @param body    the body
     */
    record Seen(String method, String target, spola.http.Headers headers, String body) {
    }

    /** A spola.http server that records every request and answers {@code {"ok":1}}. */
    record Recorder(HttpServer server, List<Seen> seen) implements AutoCloseable {

        static Recorder start() throws Exception {
            var seen = new CopyOnWriteArrayList<Seen>();
            var server = Support.serve(request -> {
                seen.add(new Seen(request.method(), request.target(), request.headers(),
                        new String(request.bytes(), StandardCharsets.UTF_8)));
                return Response.bytes(200, "application/json", "{\"ok\":1}".getBytes(StandardCharsets.UTF_8));
            });
            return new Recorder(server, seen);
        }

        URI base(String path) {
            return URI.create("http://127.0.0.1:" + server.port() + path);
        }

        Seen last() {
            return seen.getLast();
        }

        @Override
        public void close() {
            server.close();
        }
    }

    static final JsonObject OK = JsonObject.of("ok", JsonNumber.of(1));

    static Suite tests() {
        return suite("requests",
                test("Accept: application/json is sent by default", () -> {
                    try (var recorder = Recorder.start();
                            var client = RestClient.builder().baseUri(recorder.base("")).build()) {
                        assertEquals(OK, client.get("/x").retrieveJson());
                        assertEquals(List.of("application/json"), recorder.last().headers().all("Accept"));
                    }
                }),
                test("defaultHeader(\"Accept\", ...) replaces it", () -> {
                    try (var recorder = Recorder.start(); var client = RestClient.builder().baseUri(recorder.base(""))
                            .defaultHeader("accept", "application/vnd.api+json").build()) {
                        client.get("/x").retrieveJson();
                        assertEquals(List.of("application/vnd.api+json"), recorder.last().headers().all("Accept"));
                    }
                }),
                test("header(\"Accept\", ...) replaces it, also over a default Accept", () -> {
                    try (var recorder = Recorder.start(); var client = RestClient.builder().baseUri(recorder.base(""))
                            .defaultHeader("Accept", "application/vnd.api+json").build()) {
                        client.get("/x").header("ACCEPT", "application/problem+json, application/json").retrieveJson();
                        assertEquals(List.of("application/problem+json, application/json"),
                                recorder.last().headers().all("Accept"));
                    }
                    try (var recorder = Recorder.start();
                            var client = RestClient.builder().baseUri(recorder.base("")).build()) {
                        client.get("/x").header("Accept", "*/*").retrieveJson();
                        assertEquals(List.of("*/*"), recorder.last().headers().all("Accept"));
                    }
                }),
                test("header replaces a default header with the same name in another case, and an earlier header",
                        () -> {
                            try (var recorder = Recorder.start(); var client = RestClient.builder()
                                    .baseUri(recorder.base("")).defaultHeader("x-api-key", "default")
                                    .defaultHeader("anthropic-version", "2023-06-01").build()) {
                                client.get("/x").header("X-API-KEY", "first").header("x-Api-Key", "second").retrieveJson();
                                assertEquals(List.of("second"), recorder.last().headers().all("x-api-key"));
                                assertEquals(List.of("2023-06-01"), recorder.last().headers().all("anthropic-version"));
                                client.get("/x").retrieveJson();
                                assertEquals(List.of("default"), recorder.last().headers().all("x-api-key"));
                            }
                        }),
                test("get and delete without a body have no Content-Type; json(...) sets application/json", () -> {
                    try (var recorder = Recorder.start();
                            var client = RestClient.builder().baseUri(recorder.base("")).build()) {
                        client.get("/x").retrieveJson();
                        assertFalse(recorder.last().headers().contains("Content-Type"));
                        assertEquals("", recorder.last().body());
                        client.delete("/x").retrieveBodiless();
                        assertEquals("DELETE", recorder.last().method());
                        assertFalse(recorder.last().headers().contains("Content-Type"));
                        client.post("/x").retrieveBodiless();
                        assertFalse(recorder.last().headers().contains("Content-Type"));
                        client.patch("/x").json(JsonObject.of("a", JsonNumber.of(1))).retrieveJson();
                        assertEquals("PATCH", recorder.last().method());
                        assertEquals(List.of("application/json"), recorder.last().headers().all("Content-Type"));
                        assertEquals("{\"a\":1}", recorder.last().body());
                        client.put("/x").json(List.of("a", "ö"), JsonCodecs.list(JsonCodecs.STRING)).retrieveJson();
                        assertEquals("PUT", recorder.last().method());
                        assertEquals("[\"a\",\"ö\"]", recorder.last().body());
                    }
                }),
                test("a new json(...) replaces the body", () -> {
                    try (var recorder = Recorder.start();
                            var client = RestClient.builder().baseUri(recorder.base("")).build()) {
                        client.post("/x").json(JsonObject.of("a", JsonNumber.of(1))).json(JsonObject.of("b", JsonNumber.of(2)))
                                .retrieveJson();
                        assertEquals("{\"b\":2}", recorder.last().body());
                        assertEquals(List.of("application/json"), recorder.last().headers().all("Content-Type"));
                    }
                }),
                test("a baseUri with a path (/api and /api/) and a path with a query give the right target", () -> {
                    try (var recorder = Recorder.start()) {
                        for (var base : List.of("/api", "/api/")) {
                            try (var client = RestClient.builder().baseUri(recorder.base(base)).build()) {
                                client.get("/v1/items?page=2&q=caf%C3%A9").retrieveJson();
                                assertEquals("/api/v1/items?page=2&q=caf%C3%A9", recorder.last().target());
                                client.get("/").retrieveJson();
                                assertEquals("/api/", recorder.last().target());
                            }
                        }
                        try (var client = RestClient.builder().baseUri(recorder.base("/")).build()) {
                            client.get("/v1/x?").retrieveJson();
                            assertEquals("/v1/x", recorder.last().target(), "java.net.http drops an empty query");
                        }
                        try (var client = RestClient.builder().baseUri(recorder.base("")).build()) {
                            client.get("/a/../b").retrieveJson();
                            assertEquals("/a/../b", recorder.last().target(), "sent as it stands");
                        }
                    }
                }),
                test("//other.example/x goes to the base's host, as a path", () -> {
                    try (var recorder = Recorder.start();
                            var client = RestClient.builder().baseUri(recorder.base("")).build()) {
                        client.get("//other.example/x").retrieveJson();
                        assertEquals("//other.example/x", recorder.last().target());
                        assertEquals(List.of("127.0.0.1:" + recorder.server().port()), recorder.last().headers().all("Host"));
                    }
                }),
                test("the same RestRequest can be sent twice", () -> {
                    try (var recorder = Recorder.start();
                            var client = RestClient.builder().baseUri(recorder.base("")).build()) {
                        var request = client.post("/x").header("Idempotency-Key", "k1").json(OK);
                        assertEquals(OK, request.retrieveJson());
                        assertEquals(OK, request.retrieveJson());
                        assertEquals(200, request.retrieveBodiless());
                        assertEquals(3, recorder.seen().size());
                        for (var seen : recorder.seen()) {
                            assertEquals("{\"ok\":1}", seen.body());
                            assertEquals(List.of("k1"), seen.headers().all("Idempotency-Key"));
                        }
                    }
                }),
                test("every method returns a new request; the old one is unchanged", () -> {
                    try (var recorder = Recorder.start();
                            var client = RestClient.builder().baseUri(recorder.base("")).build()) {
                        var plain = client.post("/x");
                        var withHeader = plain.header("X-A", "1");
                        var withBody = withHeader.json(OK);
                        assertFalse(plain == withHeader);
                        plain.retrieveJson();
                        assertFalse(recorder.last().headers().contains("X-A"));
                        assertEquals("", recorder.last().body());
                        withHeader.retrieveJson();
                        assertEquals(List.of("1"), recorder.last().headers().all("X-A"));
                        assertEquals("", recorder.last().body());
                        withBody.retrieveJson();
                        assertEquals("{\"ok\":1}", recorder.last().body());
                    }
                }),
                test("an error from the codec in json(...) passes through and the server gets nothing", () -> {
                    record Price(String currency, double amount) {
                    }
                    JsonCodec<Price> price = JsonCodec.object(Price::new, JsonCodec.field("currency", JsonCodecs.STRING,
                            Price::currency), JsonCodec.field("amount", JsonCodecs.DOUBLE, Price::amount));
                    try (var recorder = Recorder.start();
                            var client = RestClient.builder().baseUri(recorder.base("")).build()) {
                        var e = assertThrows(JsonMappingException.class,
                                () -> client.post("/x").json(new Price("SEK", Double.NaN), price));
                        assertEquals("$.amount", e.problems().getFirst().path());
                        var missing = assertThrows(JsonMappingException.class,
                                () -> client.post("/x").json(new Price(null, 1), price).retrieveJson());
                        assertEquals("$.currency", missing.problems().getFirst().path());
                        assertEquals(0, recorder.seen().size());
                    }
                }),
                test("java.net.http adds Host and Content-Length; no Connection, Expect or Upgrade", () -> {
                    try (var recorder = Recorder.start();
                            var client = RestClient.builder().baseUri(recorder.base("")).build()) {
                        client.post("/x").json(OK).retrieveJson();
                        var headers = recorder.last().headers();
                        assertEquals(List.of("8"), headers.all("Content-Length"));
                        assertTrue(headers.contains("Host"));
                        assertFalse(headers.contains("Expect"));
                        assertFalse(headers.contains("Upgrade"));
                        assertFalse(headers.contains("Transfer-Encoding"));
                    }
                }),
                test("one request at a time reuses the connection (keep-alive)", () -> {
                    var connections = new CopyOnWriteArrayList<Integer>();
                    try (var server = Support.serve((Request request) -> {
                        connections.add(request.remoteAddress().getPort());
                        return Response.text(200, "{}");
                    }); var client = Support.client(server.port())) {
                        for (int i = 0; i < 5; i++) {
                            client.get("/x").retrieveJson();
                        }
                        assertEquals(1L, connections.stream().distinct().count(), connections.toString());
                    }
                }),
                test("a decoded value comes from the codec", () -> {
                    try (var recorder = Recorder.start();
                            var client = RestClient.builder().baseUri(recorder.base("")).build()) {
                        var codec = JsonCodec.object(Ok::new, JsonCodec.field("ok", JsonCodecs.INT, Ok::ok));
                        assertEquals(new Ok(1), client.get("/x").retrieve(codec));
                        assertEquals("GET", recorder.last().method());
                    }
                }));
    }

    record Ok(int ok) {
    }
}
