package spola.client;

import static spola.client.Support.ascii;
import static spola.client.Support.response;
import static spola.json.codec.JsonCodec.field;
import static spola.json.codec.JsonCodecs.INT;
import static spola.json.codec.JsonCodecs.STRING;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertNull;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import spola.http.Response;
import spola.json.JsonNumber;
import spola.json.JsonObject;
import spola.json.JsonParseException;
import spola.json.codec.JsonCodec;
import spola.json.codec.JsonMappingException;
import spola.test.Suite;

/** Criterion 6: responses that cannot be used, and two that can. */
final class ResponseTests {

    private ResponseTests() {
    }

    record Point(int x, int y) {
    }

    static final JsonCodec<Point> POINT = JsonCodec.object(Point::new, field("x", INT, Point::x), field("y", INT, Point::y));

    record Named(String name) {
    }

    static final JsonCodec<Named> NAMED = JsonCodec.object(Named::new, field("name", STRING, Named::name));

    static Suite tests() {
        return suite("responses that cannot be used",
                parameterized("a redirect to another local server gives the status, and that server gets nothing",
                        List.of(301, 302, 307, 308), status -> {
                            try (var other = Support.answering(response(200, "application/json", ascii("{}")));
                                    var redirecting = Support.answering(ascii(Support.head(status,
                                            "Location", "http://127.0.0.1:" + other.port() + "/elsewhere",
                                            "Content-Length", "0", "Connection", "close")));
                                    var client = RestClient.builder().baseUri(redirecting.base())
                                            .defaultHeader("x-api-key", "REDIRECTMARKER").build()) {
                                for (var call : List.<spola.test.Body>of(
                                        () -> client.get("/start").retrieveJson(),
                                        () -> client.post("/start").json(JsonObject.of()).retrieve(NAMED),
                                        () -> client.delete("/start").retrieveBodiless())) {
                                    var e = assertThrows(RestClientResponseException.class, call);
                                    assertEquals(status, e.status());
                                    assertTrue(e.getMessage().contains("redirect"), e.getMessage());
                                    assertEquals("http://127.0.0.1:" + other.port() + "/elsewhere",
                                            e.headers().firstValue("Location").orElseThrow());
                                }
                                assertEquals(3, redirecting.requests.size());
                                assertEquals(0, other.connections.get());
                                assertEquals(0, other.requests.size());
                            }
                        }),
                test("200 with an empty body fails in retrieve and in retrieveJson, with JsonParseException", () -> {
                    try (var server = Support.serve(request -> Response.bytes(200, "application/json", new byte[0]));
                            var client = Support.client(server.port())) {
                        var e = assertThrows(RestClientResponseException.class, () -> client.get("/x").retrieve(POINT));
                        assertEquals(200, e.status());
                        assertTrue(e.getCause() instanceof JsonParseException, String.valueOf(e.getCause()));
                        assertEquals("", e.bodyExcerpt());
                        assertTrue(e.getMessage().contains("empty"), e.getMessage());
                        var tree = assertThrows(RestClientResponseException.class, () -> client.get("/x").retrieveJson());
                        assertEquals(200, tree.status());
                        assertTrue(tree.getCause() instanceof JsonParseException, String.valueOf(tree.getCause()));
                    }
                }),
                test("200 without a body at all (204-like) also fails in retrieve", () -> {
                    try (var server = Support.serve(request -> Response.status(204));
                            var client = Support.client(server.port())) {
                        var e = assertThrows(RestClientResponseException.class, () -> client.get("/x").retrieve(POINT));
                        assertEquals(204, e.status());
                        assertTrue(e.getCause() instanceof JsonParseException);
                        assertEquals(204, client.get("/x").retrieveBodiless());
                    }
                }),
                test("200 with HTML gives JsonParseException as cause and the HTML in the excerpt", () -> {
                    var html = "<!DOCTYPE html>\n<html><body><h1>Bad gateway</h1></body></html>";
                    try (var server = Support.serve(request -> Response.bytes(200, "text/html; charset=utf-8",
                            html.getBytes(StandardCharsets.UTF_8)));
                            var client = Support.client(server.port())) {
                        var e = assertThrows(RestClientResponseException.class, () -> client.get("/x").retrieve(POINT));
                        assertEquals(200, e.status());
                        assertTrue(e.getCause() instanceof JsonParseException, String.valueOf(e.getCause()));
                        assertEquals("<!DOCTYPE html>\\u000a<html><body><h1>Bad gateway</h1></body></html>",
                                e.bodyExcerpt());
                        var tree = assertThrows(RestClientResponseException.class, () -> client.get("/x").retrieveJson());
                        assertTrue(tree.getCause() instanceof JsonParseException);
                    }
                }),
                test("200 with broken JSON gives JsonParseException as cause", () -> {
                    try (var server = Support.serve(request -> Response.bytes(200, "application/json",
                            ascii("{\"x\":1,")));
                            var client = Support.client(server.port())) {
                        var e = assertThrows(RestClientResponseException.class, () -> client.get("/x").retrieve(POINT));
                        assertEquals(200, e.status());
                        var cause = (JsonParseException) e.getCause();
                        assertEquals(1L, cause.line());
                        assertEquals("{\"x\":1,", e.bodyExcerpt());
                        assertTrue(e.getMessage().contains("not JSON"), e.getMessage());
                    }
                }),
                test("200 with JSON that does not fit the codec gives JsonMappingException with the path", () -> {
                    try (var server = Support.serve(request -> Response.bytes(200, "application/json",
                            ascii("{\"x\":1,\"y\":\"two\"}")));
                            var client = Support.client(server.port())) {
                        var e = assertThrows(RestClientResponseException.class, () -> client.get("/x").retrieve(POINT));
                        assertEquals(200, e.status());
                        var cause = (JsonMappingException) e.getCause();
                        assertEquals("$.y", cause.problems().getFirst().path());
                        assertTrue(e.getMessage().contains("does not fit"), e.getMessage());
                        assertEquals("{\"x\":1,\"y\":\"two\"}", e.bodyExcerpt());
                    }
                }),
                test("201 with text/plain and valid JSON gives the value: Content-Type does not decide", () -> {
                    try (var server = Support.serve(request -> Response.text(201, "{\"x\":3,\"y\":4}"));
                            var client = Support.client(server.port())) {
                        assertEquals(new Point(3, 4), client.get("/x").retrieve(POINT));
                        assertEquals(JsonObject.of("x", JsonNumber.of(3), "y", JsonNumber.of(4)),
                                client.get("/x").retrieveJson());
                    }
                }),
                test("200 without Content-Type and valid JSON gives the value", () -> {
                    try (var server = Support.answering(response(200, null, ascii("{\"name\":\"a\"}")));
                            var client = RestClient.builder().baseUri(server.base()).build()) {
                        assertEquals(new Named("a"), client.get("/x").retrieve(NAMED));
                    }
                }),
                parameterized("retrieveBodiless gives 2xx statuses", List.of(200, 201, 202, 204), status -> {
                    try (var server = Support.serve(request -> status == 204 ? Response.status(204)
                            : Response.text(status, "anything, not JSON"));
                            var client = Support.client(server.port())) {
                        assertEquals(status, client.post("/x").retrieveBodiless());
                    }
                }),
                test("retrieveBodiless with 404 throws with the status and the excerpt", () -> {
                    try (var server = Support.serve(request -> Response.text(404, "no such thing"));
                            var client = Support.client(server.port())) {
                        var e = assertThrows(RestClientResponseException.class, () -> client.delete("/x").retrieveBodiless());
                        assertEquals(404, e.status());
                        assertEquals("no such thing", e.bodyExcerpt());
                        assertNull(e.getCause());
                    }
                }),
                test("a nullable codec may decode JSON null", () -> {
                    try (var server = Support.serve(request -> Response.bytes(200, "application/json", ascii("null")));
                            var client = Support.client(server.port())) {
                        assertNull(client.get("/x").retrieve(spola.json.codec.JsonCodecs.nullable(POINT)));
                    }
                }));
    }
}
