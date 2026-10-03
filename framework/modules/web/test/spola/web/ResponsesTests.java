package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import spola.http.HttpServer;
import spola.json.JsonObject;
import spola.test.Suite;

/** Criteria 11–13: Responses.json with 406, problems not negotiated, encode failures, concurrency. */
final class ResponsesTests {

    private ResponsesTests() {
    }

    /** The README's negotiation example. */
    static spola.http.Response show(WebRequest request) {
        var item = new ItemApi.Item(7, "x", 2);
        var type = request.negotiate(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN);
        if (type.equals(MediaType.TEXT_PLAIN)) {
            return spola.http.Response.text(200, item.name() + " x " + item.quantity());
        }
        return Responses.json(request, 200, ItemApi.ITEM, item);
    }

    static Suite tests() {
        var server = new AtomicReference<HttpServer>();
        var log = new Support.CapturedLog();
        return suite("Responses",
                test("406 when Accept does not take application/json", () -> {
                    for (var accept : List.of("text/html", "application/json;q=0, */*", "*/*, application/json;q=0",
                            "application/xml, text/*")) {
                        var reply = Support.send(server.get().port(), "GET", "/item", "Accept: " + accept);
                        assertEquals(406, reply.status(), accept + ": " + reply);
                        assertEquals("application/problem+json", reply.header("Content-Type"));
                        assertEquals("Acceptable representations: application/json.", reply.json().string("detail"));
                    }
                }),
                test("200 for */*, application/*, a lower but non-zero weight, and no Accept", () -> {
                    for (var accept : List.of("*/*", "application/*", "application/json;q=0.5, text/html;q=0.9",
                            "application/json", "text/html, application/json;q=0.001")) {
                        var reply = Support.send(server.get().port(), "GET", "/item", "Accept: " + accept);
                        assertEquals(200, reply.status(), accept + ": " + reply);
                        assertEquals("application/json", reply.header("Content-Type"));
                        assertEquals("{\"id\":7,\"name\":\"x\",\"quantity\":2}", reply.body());
                    }
                    var none = Support.send(server.get().port(), "GET", "/item");
                    assertEquals(200, none.status());
                    assertEquals(String.valueOf(none.body().length()), none.header("Content-Length"));
                }),
                test("an invalid Accept on a JSON response: 400, not 406", () -> {
                    var reply = Support.send(server.get().port(), "GET", "/item", "Accept: application/json;q=2");
                    assertEquals(400, reply.status());
                }),
                test("problem responses are application/problem+json even with Accept: text/html", () -> {
                    var missing = Support.send(server.get().port(), "GET", "/nowhere", "Accept: text/html");
                    assertEquals(404, missing.status());
                    assertEquals("application/problem+json", missing.header("Content-Type"));
                    var invalid = JsonBodyTests.post(server.get().port(), "/echo", "{\"name\":1}",
                            "Content-Type: application/json", "Accept: text/html");
                    assertEquals(400, invalid.status());
                    assertEquals("application/problem+json", invalid.header("Content-Type"));
                    assertEquals(2, invalid.json().array("errors").values().size());
                    var notAcceptable = Support.send(server.get().port(), "GET", "/item", "Accept: text/html");
                    assertEquals("application/problem+json", notAcceptable.header("Content-Type"));
                }),
                test("JsonValue responses, created with Location, noContent", () -> {
                    var value = Support.send(server.get().port(), "GET", "/value");
                    assertEquals(200, value.status());
                    assertEquals("{\"ok\":true,\"text\":\"åäö €\"}", value.body());
                    assertEquals(String.valueOf("{\"ok\":true,\"text\":\"åäö €\"}".getBytes(
                            java.nio.charset.StandardCharsets.UTF_8).length), value.header("Content-Length"));
                    var created = Support.send(server.get().port(), "GET", "/created");
                    assertEquals(201, created.status());
                    assertEquals("/items/7", created.header("Location"));
                    assertEquals("application/json", created.header("Content-Type"));
                    assertEquals(204, Support.send(server.get().port(), "GET", "/none").status());
                    assertEquals(204, Responses.noContent().status());
                }),
                test("an encode failure is 500 without details, never 400", () -> {
                    var reply = Support.send(server.get().port(), "GET", "/broken");
                    assertEquals(500, reply.status(), reply.toString());
                    assertEquals("application/problem+json", reply.header("Content-Type"));
                    var json = reply.json();
                    assertEquals(Optional.empty(), Optional.ofNullable(json.members().get("detail")));
                    assertFalse(reply.body().contains("null"), reply.body());
                    assertFalse(reply.body().contains("$.name"), reply.body());
                    assertTrue(log.records().stream().anyMatch(r -> r.contains("could not be encoded as JSON")
                            && r.contains("$.name")), log.records().toString());
                    // a mapper for the codec's exception (meant for input) does not turn it into a 400
                    var router = Router.builder()
                            .get("/broken", r -> Responses.json(r, 200, ItemApi.ITEM, new ItemApi.Item(1, null, 1)))
                            .onException(spola.json.codec.JsonMappingException.class,
                                    (e, r) -> Problem.of(400, "bad input").toResponse())
                            .log(new Support.CapturedLog())
                            .build();
                    try (var own = Support.serve(router)) {
                        assertEquals(500, Support.send(own.port(), "GET", "/broken").status());
                    }
                }),
                test("the README's own negotiation: JSON or text after Accept", () -> {
                    assertEquals("application/json", Support.send(server.get().port(), "GET", "/both").header("Content-Type"));
                    var text = Support.send(server.get().port(), "GET", "/both", "Accept: text/*, application/json;q=0.5");
                    assertEquals("text/plain; charset=utf-8", text.header("Content-Type"));
                    assertEquals("x x 2", text.body());
                    var json = Support.send(server.get().port(), "GET", "/both", "Accept: text/plain;q=0.5, */*");
                    assertEquals("{\"id\":7,\"name\":\"x\",\"quantity\":2}", json.body());
                    assertEquals(406, Support.send(server.get().port(), "GET", "/both", "Accept: image/png").status());
                }),
                test("Responses.json checks its arguments", () -> {
                    assertThrows(NullPointerException.class, () -> Responses.json(null, 200, ItemApi.ITEM, null));
                }),
                test("200 concurrent clients (virtual threads) posting JSON each get their own body back", () -> {
                    try (var client = Support.client(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        var start = new CountDownLatch(1);
                        var futures = new ArrayList<Future<HttpResponse<String>>>();
                        for (int i = 0; i < 200; i++) {
                            int n = i;
                            futures.add(executor.submit(() -> {
                                start.await();
                                var body = ItemApi.ITEM.write(new ItemApi.Item(n, "client " + n + " ✓", n * 3));
                                var request = HttpRequest.newBuilder(
                                                URI.create("http://127.0.0.1:" + server.get().port() + "/echo"))
                                        .header("Content-Type", "application/json")
                                        .timeout(Duration.ofSeconds(30))
                                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                                return client.send(request, HttpResponse.BodyHandlers.ofString());
                            }));
                        }
                        start.countDown();
                        for (int i = 0; i < 200; i++) {
                            var response = futures.get(i).get();
                            assertEquals(200, response.statusCode(), response.body());
                            assertEquals(new ItemApi.Item(i, "client " + i + " ✓", i * 3),
                                    ItemApi.ITEM.read(response.body()));
                        }
                    }
                }))
                .beforeAll(() -> server.set(Support.serve(Router.builder()
                        .get("/item", r -> Responses.json(r, 200, ItemApi.ITEM, new ItemApi.Item(7, "x", 2)))
                        .get("/value", r -> Responses.json(r, 200,
                                JsonObject.builder().put("ok", true).put("text", "åäö €").build()))
                        .get("/created", r -> Responses.created(r, "/items/7", ItemApi.ITEM, new ItemApi.Item(7, "x", 2)))
                        .get("/none", r -> Responses.noContent())
                        .get("/both", ResponsesTests::show)
                        .get("/broken", r -> Responses.json(r, 200, ItemApi.ITEM, new ItemApi.Item(1, null, 1)))
                        .post("/echo", r -> {
                            var item = r.readJson(ItemApi.ITEM);
                            Thread.sleep(10); // overlap the requests
                            return Responses.json(r, 200, ItemApi.ITEM, item);
                        })
                        .log(log)
                        .build())))
                .afterAll(() -> server.get().close());
    }
}
