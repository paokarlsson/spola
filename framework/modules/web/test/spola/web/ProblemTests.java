package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import spola.http.ResponseBody;
import spola.json.Json;
import spola.json.JsonArray;
import spola.json.JsonNumber;
import spola.json.JsonObject;
import spola.json.JsonString;
import spola.test.Suite;

/** Criterion 12: the RFC 9457 problem response. */
final class ProblemTests {

    private ProblemTests() {
    }

    static JsonObject body(spola.http.Response response) {
        var bytes = ((ResponseBody.Bytes) response.body()).bytes();
        return (JsonObject) Json.parse(new String(bytes, StandardCharsets.UTF_8));
    }

    static Suite tests() {
        return suite("problem responses",
                test("Content-Type, members in order, detail only when present", () -> {
                    var without = Problem.of(404).withInstance("/a b").toResponse();
                    assertEquals(404, without.status());
                    assertEquals("application/problem+json", without.headers().first("Content-Type").orElseThrow());
                    var json = body(without);
                    assertEquals(List.of("type", "title", "status", "instance"), List.copyOf(json.members().keySet()));
                    assertEquals("about:blank", json.string("type"));
                    assertEquals("Not Found", json.string("title"));
                    assertEquals(404L, json.number("status").longValueExact());
                    assertEquals("/a b", json.string("instance"));
                    assertFalse(json.contains("detail"));

                    var with = body(Problem.of(409, "Already exists.").withInstance("/x").toResponse());
                    assertEquals(List.of("type", "title", "status", "detail", "instance"), List.copyOf(with.members().keySet()));
                    assertEquals("Already exists.", with.string("detail"));
                }),
                test("type, title and extension members", () -> {
                    var problem = Problem.of(422)
                            .withType("https://example.com/problems/out-of-stock")
                            .withTitle("Out of stock")
                            .withDetail("Only 2 left.")
                            .withInstance("/orders/7")
                            .withExtension("available", JsonNumber.of(2))
                            .withExtension("skus", JsonArray.of(JsonString.of("A-1"), JsonString.of("B-2")));
                    var json = body(problem.toResponse());
                    assertEquals(List.of("type", "title", "status", "detail", "instance", "available", "skus"),
                            List.copyOf(json.members().keySet()));
                    assertEquals("https://example.com/problems/out-of-stock", json.string("type"));
                    assertEquals("Out of stock", json.string("title"));
                    assertEquals(2L, json.number("available").longValueExact());
                    assertEquals(JsonArray.of(JsonString.of("A-1"), JsonString.of("B-2")), json.array("skus"));
                    assertEquals(json, problem.toJson());
                }),
                test("detail is cut to at most 2 000 characters, strings in extensions too", () -> {
                    var long5000 = "d".repeat(5_000);
                    var problem = Problem.of(400, long5000).withExtension("note", JsonString.of(long5000))
                            .withExtension("nested", JsonObject.of("deep", JsonArray.of(JsonString.of(long5000))));
                    var json = body(problem.toResponse());
                    var detail = json.string("detail");
                    assertEquals(2_000, detail.length());
                    assertTrue(detail.endsWith("..."));
                    assertEquals(2_000, json.string("note").length());
                    assertEquals(2_000, ((JsonString) json.object("nested").array("deep").values().getFirst()).value().length());
                    assertEquals("x".repeat(2_000), Problem.of(400, "x".repeat(2_000)).detail().orElseThrow(),
                            "exactly 2 000 is kept");
                    // a surrogate pair is never cut in half
                    var emoji = "a".repeat(1_996) + "😀" + "tail";
                    var cut = Problem.of(400, emoji).detail().orElseThrow();
                    assertEquals("a".repeat(1_996) + "...", cut);
                }),
                test("control characters and unpaired surrogates still give valid JSON", () -> {
                    var problem = Problem.of(400, "line\nbreak \u001b[31m \uD800 end").withInstance("/a\u0000b");
                    var text = new String(((ResponseBody.Bytes) problem.toResponse().body()).bytes(), StandardCharsets.UTF_8);
                    for (char c : text.toCharArray()) {
                        assertFalse(c < 0x20, "raw control character in " + text);
                    }
                    var json = (JsonObject) Json.parse(text);
                    assertEquals("line\nbreak \u001b[31m � end", json.string("detail"));
                }),
                test("invalid problems are refused", () -> {
                    assertThrows(IllegalArgumentException.class, () -> Problem.of(200));
                    assertThrows(IllegalArgumentException.class, () -> Problem.of(600));
                    assertThrows(IllegalArgumentException.class, () -> Problem.of(400).withType(""));
                    assertThrows(IllegalArgumentException.class, () -> Problem.of(400).withExtension("status", JsonNumber.of(1)));
                    assertThrows(IllegalArgumentException.class, () -> Problem.of(400).withExtension("a", JsonNumber.of(1))
                            .withExtension("a", JsonNumber.of(2)));
                    assertThrows(IllegalArgumentException.class, () -> new WebException(302));
                }),
                test("through the router: the instance is the decoded path, escaped as JSON", () -> {
                    var router = Router.builder()
                            .get("/orders/{id}", r -> {
                                throw new WebException(409, "Order " + Text.quote(r.pathParam("id")) + " is locked.");
                            })
                            .build();
                    try (var server = Support.serve(router)) {
                        var reply = Support.send(server.port(), "GET", "/orders/%C3%A5%22%5D");
                        assertEquals(409, reply.status());
                        assertEquals("application/problem+json", reply.header("Content-Type"));
                        var json = reply.json();
                        assertEquals("/orders/å\"]", json.string("instance"));
                        assertEquals("Conflict", json.string("title"));
                        assertEquals("Order \"\\xe5\\\"]\" is locked.", json.string("detail"));
                    }
                }));
    }
}
