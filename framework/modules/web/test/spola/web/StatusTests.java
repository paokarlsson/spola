package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertNull;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import spola.http.Response;
import spola.test.Suite;

/** Criterion 5: 404, 405 with Allow, HEAD, automatic and explicit OPTIONS, OPTIONS *. */
final class StatusTests {

    private StatusTests() {
    }

    static Router router() {
        return Router.builder()
                .get("/items/{id}", r -> Response.text(200, "item " + r.pathParam("id")))
                .post("/items/{id}", r -> Response.text(201, "created"))
                .delete("/items/{id}", r -> Response.status(204))
                .put("/items/special", r -> Response.text(200, "special"))
                .post("/form", r -> Response.text(200, "form"))
                .get("/custom", r -> Response.text(200, "the GET body"))
                .route("HEAD", "/custom", r -> Response.status(200).withHeader("X-Head", "own"))
                .route("OPTIONS", "/custom", r -> Response.status(204).withHeader("Allow", "GET").withHeader("X-Options", "own"))
                .build();
    }

    static Suite tests() {
        return suite("404, 405, HEAD and OPTIONS",
                test("no route for the path: 404 with a problem response", () -> {
                    try (var server = Support.serve(router())) {
                        var reply = Support.send(server.port(), "GET", "/nothing/x");
                        assertEquals(404, reply.status());
                        assertEquals("application/problem+json", reply.header("Content-Type"));
                        var json = reply.json();
                        assertEquals(404L, json.number("status").longValueExact());
                        assertEquals("Not Found", json.string("title"));
                        assertEquals("/nothing/x", json.string("instance"));
                    }
                }),
                test("405 with the exact Allow value: every method of every route matching the path, sorted", () -> {
                    try (var server = Support.serve(router())) {
                        var reply = Support.send(server.port(), "PATCH", "/items/special");
                        assertEquals(405, reply.status());
                        // /items/{id} has GET, POST, DELETE; /items/special has PUT; HEAD with GET; OPTIONS always
                        assertEquals("DELETE, GET, HEAD, OPTIONS, POST, PUT", reply.header("Allow"));
                        assertEquals("application/problem+json", reply.header("Content-Type"));
                        assertEquals(405L, reply.json().number("status").longValueExact());

                        var form = Support.send(server.port(), "GET", "/form");
                        assertEquals(405, form.status());
                        assertEquals("OPTIONS, POST", form.header("Allow"));

                        var lower = Support.send(server.port(), "get", "/items/1");
                        assertEquals(405, lower.status(), "methods are case-sensitive");
                        assertEquals("DELETE, GET, HEAD, OPTIONS, POST", lower.header("Allow"));
                    }
                }),
                test("HEAD without a HEAD route uses the GET route: no body, same Content-Length", () -> {
                    try (var server = Support.serve(router()); var client = Support.client()) {
                        var base = "http://127.0.0.1:" + server.port();
                        var get = client.send(HttpRequest.newBuilder(URI.create(base + "/items/12345")).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                        var head = client.send(HttpRequest.newBuilder(URI.create(base + "/items/12345"))
                                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                        assertEquals(200, get.statusCode());
                        assertEquals("item 12345", get.body());
                        assertEquals(200, head.statusCode());
                        assertEquals("", head.body());
                        assertEquals(get.headers().firstValue("Content-Length"), head.headers().firstValue("Content-Length"));
                        assertEquals(Optional.of("10"), head.headers().firstValue("Content-Length"));
                    }
                    try (var server = Support.serve(router())) {
                        var raw = Support.send(server.port(), "HEAD", "/items/1");
                        assertEquals(200, raw.status());
                        assertEquals("", raw.body());
                        assertEquals("6", raw.header("Content-Length"));
                    }
                }),
                test("OPTIONS without an OPTIONS route: 204 with Allow", () -> {
                    try (var server = Support.serve(router())) {
                        var reply = Support.send(server.port(), "OPTIONS", "/items/1");
                        assertEquals(204, reply.status());
                        assertEquals("DELETE, GET, HEAD, OPTIONS, POST", reply.header("Allow"));
                        assertEquals("", reply.body());
                        assertNull(reply.header("Content-Type"));

                        var missing = Support.send(server.port(), "OPTIONS", "/nothing");
                        assertEquals(404, missing.status(), "no route for the path: 404, not an automatic OPTIONS");
                    }
                }),
                test("an explicit HEAD or OPTIONS route goes before the automatic answer", () -> {
                    try (var server = Support.serve(router())) {
                        var head = Support.send(server.port(), "HEAD", "/custom");
                        assertEquals(200, head.status());
                        assertEquals("own", head.header("X-Head"));
                        var options = Support.send(server.port(), "OPTIONS", "/custom");
                        assertEquals(204, options.status());
                        assertEquals("own", options.header("X-Options"));
                        assertEquals("GET", options.header("Allow"));
                        var get = Support.send(server.port(), "GET", "/custom");
                        assertEquals("the GET body", get.body());
                    }
                }),
                test("OPTIONS * (asterisk-form): 404", () -> {
                    try (var server = Support.serve(router())) {
                        var reply = Support.send(server.port(), "OPTIONS", "*");
                        assertEquals(404, reply.status());
                        assertEquals("application/problem+json", reply.header("Content-Type"));
                        assertEquals("*", reply.json().string("instance"));
                        assertTrue(reply.json().contains("detail"));
                    }
                }));
    }
}
