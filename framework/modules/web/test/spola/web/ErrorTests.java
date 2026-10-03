package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.IOException;
import java.io.Serial;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import spola.http.HttpServer;
import spola.http.Response;
import spola.test.Suite;

/** Criterion 11: exception mapping. */
final class ErrorTests {

    private ErrorTests() {
    }

    static class Base extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    static class Mid extends Base {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    static final class Leaf extends Mid {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    static final class OrderNotFound extends WebException {
        @Serial
        private static final long serialVersionUID = 1L;

        OrderNotFound(long id) {
            super(404, "Order not found", "No order " + id + ".");
        }
    }

    static final class Teapot extends WebException {
        @Serial
        private static final long serialVersionUID = 1L;

        Teapot() {
            super(418);
        }
    }

    static Response mapped(String name) {
        return Response.text(299, name);
    }

    static RouterBuilder throwing(RouterBuilder builder) {
        return builder
                .get("/leaf", r -> {
                    throw new Leaf();
                })
                .get("/mid", r -> {
                    throw new Mid();
                })
                .get("/base", r -> {
                    throw new Base();
                })
                .get("/state", r -> {
                    throw new IllegalStateException("x");
                })
                .get("/checked", r -> {
                    throw new IOException("checked");
                });
    }

    static Suite tests() {
        return suite("exception mapping",
                test("the closest mapper in the class hierarchy wins, whatever the registration order", () -> {
                    var forward = throwing(Router.builder())
                            .onException(RuntimeException.class, (e, r) -> mapped("runtime"))
                            .onException(Base.class, (e, r) -> mapped("base"))
                            .onException(Mid.class, (e, r) -> mapped("mid"))
                            .onException(Exception.class, (e, r) -> mapped("exception"))
                            .build();
                    var reverse = throwing(Router.builder())
                            .onException(Exception.class, (e, r) -> mapped("exception"))
                            .onException(Mid.class, (e, r) -> mapped("mid"))
                            .onException(Base.class, (e, r) -> mapped("base"))
                            .onException(RuntimeException.class, (e, r) -> mapped("runtime"))
                            .build();
                    for (var router : new Router[] {forward, reverse}) {
                        try (var server = Support.serve(router)) {
                            assertEquals("mid", Support.send(server.port(), "GET", "/leaf").body());
                            assertEquals("mid", Support.send(server.port(), "GET", "/mid").body());
                            assertEquals("base", Support.send(server.port(), "GET", "/base").body());
                            assertEquals("runtime", Support.send(server.port(), "GET", "/state").body());
                            assertEquals("exception", Support.send(server.port(), "GET", "/checked").body());
                            assertEquals(299, Support.send(server.port(), "GET", "/leaf").status());
                        }
                    }
                }),
                test("a WebException subtype without a mapper gives its status as a problem response", () -> {
                    var router = Router.builder()
                            .get("/orders/{id}", r -> {
                                throw new OrderNotFound(r.pathParam("id", Param.LONG));
                            })
                            .get("/tea", r -> {
                                throw new Teapot();
                            })
                            .build();
                    try (var server = Support.serve(router)) {
                        var reply = Support.send(server.port(), "GET", "/orders/7");
                        assertEquals(404, reply.status());
                        assertEquals("application/problem+json", reply.header("Content-Type"));
                        assertEquals("Order not found", reply.json().string("title"));
                        assertEquals("No order 7.", reply.json().string("detail"));
                        assertEquals("/orders/7", reply.json().string("instance"));
                        var tea = Support.send(server.port(), "GET", "/tea");
                        assertEquals(418, tea.status());
                        assertEquals("Client Error", tea.json().string("title"));
                    }
                }),
                test("a mapper for WebException can give a custom 404 page (and keep Allow on 405)", () -> {
                    var router = Router.builder()
                            .get("/here", r -> Response.text(200, "here"))
                            .onException(WebException.class, (e, r) -> {
                                if (e.status() == 404) {
                                    return Response.bytes(404, "text/html; charset=utf-8",
                                            "<h1>Nothing at this address</h1>".getBytes(StandardCharsets.UTF_8));
                                }
                                var response = e.toResponse(r);
                                return response.withHeader("X-Mapped", "yes");
                            })
                            .build();
                    try (var server = Support.serve(router)) {
                        var reply = Support.send(server.port(), "GET", "/nowhere");
                        assertEquals(404, reply.status());
                        assertEquals("text/html; charset=utf-8", reply.header("Content-Type"));
                        assertEquals("<h1>Nothing at this address</h1>", reply.body());
                        var notAllowed = Support.send(server.port(), "DELETE", "/here");
                        assertEquals(405, notAllowed.status());
                        assertEquals("GET, HEAD, OPTIONS", notAllowed.header("Allow"));
                        assertEquals("yes", notAllowed.header("X-Mapped"));
                    }
                }),
                test("an unknown RuntimeException: 500 without the message or a stack trace in the body", () -> {
                    var log = new Support.CapturedLog();
                    var router = Router.builder()
                            .get("/boom", r -> {
                                throw new IllegalStateException("secret-db-password=hunter2");
                            })
                            .log(log)
                            .build();
                    try (var server = Support.serve(router)) {
                        var reply = Support.send(server.port(), "GET", "/boom");
                        assertEquals(500, reply.status());
                        assertEquals("application/problem+json", reply.header("Content-Type"));
                        assertFalse(reply.body().contains("hunter2"), reply.body());
                        assertFalse(reply.body().contains("IllegalStateException"), reply.body());
                        assertFalse(reply.body().contains("at spola."), reply.body());
                        assertEquals("Internal Server Error", reply.json().string("title"));
                        assertFalse(reply.json().contains("detail"));
                    }
                    assertEquals(1, log.records().size(), String.valueOf(log.records()));
                    var record = log.records().getFirst();
                    assertTrue(record.startsWith("ERROR GET /boom failed; answering 500\n"), record);
                    assertTrue(record.contains("java.lang.IllegalStateException: secret-db-password=hunter2"), record);
                }),
                test("a mapper that throws gives 500 without calling a mapper again", () -> {
                    var calls = new AtomicInteger();
                    var log = new Support.CapturedLog();
                    var router = Router.builder()
                            .get("/fail", r -> {
                                throw new IllegalArgumentException("first");
                            })
                            .onException(RuntimeException.class, (e, r) -> {
                                calls.incrementAndGet();
                                throw new IllegalStateException("the mapper failed");
                            })
                            .onException(Exception.class, (e, r) -> {
                                calls.incrementAndGet();
                                return Response.text(200, "should not be used");
                            })
                            .log(log)
                            .build();
                    try (var server = Support.serve(router)) {
                        var reply = Support.send(server.port(), "GET", "/fail");
                        assertEquals(500, reply.status());
                        assertEquals("application/problem+json", reply.header("Content-Type"));
                    }
                    assertEquals(1, calls.get());
                    assertTrue(log.records().getFirst().startsWith("ERROR GET /fail: the exception mapper for"
                            + " java.lang.RuntimeException failed on java.lang.IllegalArgumentException: first;"
                            + " answering 500"), log.records().getFirst());
                }),
                test("a mapper that returns null gives 500", () -> {
                    var router = Router.builder()
                            .get("/fail", r -> {
                                throw new IllegalArgumentException("first");
                            })
                            .onException(IllegalArgumentException.class, (e, r) -> null)
                            .log(new Support.CapturedLog())
                            .build();
                    try (var server = Support.serve(router)) {
                        assertEquals(500, Support.send(server.port(), "GET", "/fail").status());
                    }
                }),
                test("an exception from a filter goes to the same mappers; the filters around it see the response", () -> {
                    var router = Router.builder()
                            .filter((request, next) -> next.proceed(request).withHeader("X-Outer", "saw it"))
                            .filter((request, next) -> {
                                if (request.query("deny").isPresent()) {
                                    throw new Mid();
                                }
                                return next.proceed(request);
                            })
                            .get("/x", r -> Response.text(200, "x"))
                            .onException(Base.class, (e, r) -> mapped("base from " + r.path()))
                            .build();
                    try (var server = Support.serve(router)) {
                        var reply = Support.send(server.port(), "GET", "/x?deny");
                        assertEquals(299, reply.status());
                        assertEquals("base from /x", reply.body());
                        assertEquals("saw it", reply.header("X-Outer"));
                        assertEquals("x", Support.send(server.port(), "GET", "/x").body());
                    }
                }),
                test("a handler or filter returning null gives 500", () -> {
                    var router = Router.builder()
                            .get("/null", r -> null)
                            .group("/g", g -> g.filter((request, next) -> null).get("/x", r -> Response.text(200, "x")))
                            .log(new Support.CapturedLog())
                            .build();
                    try (var server = Support.serve(router)) {
                        assertEquals(500, Support.send(server.port(), "GET", "/null").status());
                        assertEquals(500, Support.send(server.port(), "GET", "/g/x").status());
                    }
                }),
                test("RequestBodyException (chunked body over maxBodySize) gives the body's status from spola.http", () -> {
                    var router = Router.builder()
                            .post("/upload", r -> Response.text(200, "read " + r.bytes().length))
                            .onException(Exception.class, (e, r) -> Response.text(500, "swallowed"))
                            .build();
                    var options = Support.options().withLimits(Support.options().limits().withMaxBodySize(16));
                    try (var server = HttpServer.start(options, router)) {
                        var small = chunkedPost(server.port(), "/upload", "x".repeat(10));
                        assertEquals(200, small.status());
                        assertEquals("read 10", small.body());
                        var large = chunkedPost(server.port(), "/upload", "x".repeat(100));
                        assertEquals(413, large.status(), large.toString());
                        assertFalse(large.body().contains("swallowed"));
                    }
                    var plain = Router.builder()
                            .post("/upload", r -> Response.text(200, "read " + r.bytes().length))
                            .post("/wrapped", r -> {
                                try {
                                    return Response.text(200, "read " + r.bytes().length);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            })
                            .build();
                    try (var server = HttpServer.start(options, plain)) {
                        assertEquals(413, chunkedPost(server.port(), "/upload", "x".repeat(100)).status());
                        assertEquals(413, chunkedPost(server.port(), "/wrapped", "x".repeat(100)).status(),
                                "a wrapped body failure without a mapper also goes to the server");
                    }
                    var exact = Router.builder()
                            .post("/upload", r -> Response.text(200, "read " + r.bytes().length))
                            .onException(spola.http.RequestBodyException.class,
                                    (e, r) -> Problem.of(e.status(), "Uploads are limited to 16 bytes.").toResponse())
                            .build();
                    try (var server = HttpServer.start(options, exact)) {
                        var reply = chunkedPost(server.port(), "/upload", "x".repeat(100));
                        assertEquals(413, reply.status());
                        assertEquals("Uploads are limited to 16 bytes.", reply.json().string("detail"));
                        assertEquals("close", reply.header("Connection"));
                    }
                }));
    }

    static Support.Reply chunkedPost(int port, String target, String body) throws IOException {
        var request = "POST " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n"
                + Integer.toHexString(body.length()) + "\r\n" + body + "\r\n0\r\n\r\n";
        return Support.raw(port, request.getBytes(StandardCharsets.ISO_8859_1), false);
    }
}
