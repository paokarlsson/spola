package spola.http;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertNull;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import spola.test.Suite;

/** Keep-alive, pipelining, Connection: close, HTTP/1.0, and skipping a body the handler did not read. */
final class ConnectionTests {

    private ConnectionTests() {
    }

    /** Answers with the path and the number of the request on this server. */
    static Handler counting(AtomicInteger calls) {
        return request -> Response.text(200, request.path() + " #" + calls.incrementAndGet());
    }

    static Suite tests() {
        return suite("connections",
                test("keep-alive: three requests on one connection", () -> {
                    var calls = new AtomicInteger();
                    try (var server = Support.start(counting(calls)); var c = new Support.Client(server.port())) {
                        for (int i = 1; i <= 3; i++) {
                            var reply = c.send(Support.get("/r" + i)).read();
                            assertEquals(200, reply.status());
                            assertEquals("/r" + i + " #" + i, reply.text());
                            assertNull(reply.header("Connection")); // the connection stays open
                        }
                        assertTrue(c.silentFor(200), "the connection stays open and quiet");
                    }
                }),
                test("pipelining: two requests in one write give two responses in order", () -> {
                    var calls = new AtomicInteger();
                    try (var server = Support.start(counting(calls)); var c = new Support.Client(server.port())) {
                        c.send(Support.get("/first") + Support.get("/second"));
                        assertEquals("/first #1", c.read().text());
                        assertEquals("/second #2", c.read().text());
                        // and a third, pipelined with a body in between
                        c.send("POST /third HTTP/1.1\r\nHost: a\r\nContent-Length: 5\r\n\r\nhello" + Support.get("/fourth"));
                        assertEquals("/third #3", c.read().text());
                        assertEquals("/fourth #4", c.read().text());
                    }
                }),
                test("Connection: close from the client: the response says close and the server closes", () -> {
                    var calls = new AtomicInteger();
                    try (var server = Support.start(counting(calls)); var c = new Support.Client(server.port())) {
                        var reply = c.send(Support.get("/bye", "Connection: close")).read();
                        assertEquals(200, reply.status());
                        assertTrue(reply.closes(), "Connection: close in the response");
                        assertTrue(c.closedByServer(), "closed after the response");
                    }
                    try (var server = Support.start(counting(calls)); var c = new Support.Client(server.port())) {
                        // as one token in a list, any case
                        var reply = c.send(Support.get("/bye", "Connection: x-foo, CLOSE")).read();
                        assertTrue(reply.closes());
                        assertTrue(c.closedByServer());
                    }
                }),
                test("HTTP/1.0 is closed after the response, also with Connection: keep-alive", () -> {
                    var calls = new AtomicInteger();
                    try (var server = Support.start(counting(calls))) {
                        try (var c = new Support.Client(server.port())) {
                            var reply = c.send("GET /old HTTP/1.0\r\n\r\n").read();
                            assertEquals(200, reply.status());
                            assertEquals("/old #1", reply.text());
                            assertTrue(reply.closes());
                            assertTrue(c.closedByServer());
                        }
                        try (var c = new Support.Client(server.port())) {
                            var reply = c.send("GET /old HTTP/1.0\r\nConnection: keep-alive\r\nHost: a\r\n\r\n").read();
                            assertEquals(200, reply.status());
                            assertTrue(reply.closes());
                            assertTrue(c.closedByServer());
                        }
                    }
                }),
                test("a body the handler did not read is skipped; the next request works", () -> {
                    var calls = new AtomicInteger();
                    try (var server = Support.start(counting(calls)); var c = new Support.Client(server.port())) {
                        c.send("POST /ignored HTTP/1.1\r\nHost: a\r\nContent-Length: 11\r\n\r\nhello world");
                        assertEquals("/ignored #1", c.read().text());
                        assertEquals("/next #2", c.send(Support.get("/next")).read().text());

                        c.send("POST /chunked HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n"
                                + "5\r\nhello\r\n6\r\n world\r\n0\r\nX-Trailer: 1\r\n\r\n");
                        assertEquals("/chunked #3", c.read().text());
                        assertEquals("/next #4", c.send(Support.get("/next")).read().text());

                        var big = "x".repeat(200_000);
                        c.send("POST /big HTTP/1.1\r\nHost: a\r\nContent-Length: " + big.length() + "\r\n\r\n" + big);
                        assertEquals("/big #5", c.read().text());
                        assertEquals("/next #6", c.send(Support.get("/next")).read().text());
                    }
                }),
                test("a body read halfway is skipped too", () -> {
                    Handler handler = request -> {
                        var first = request.body().readNBytes(3);
                        return Response.text(200, new String(first, StandardCharsets.US_ASCII));
                    };
                    try (var server = Support.start(handler); var c = new Support.Client(server.port())) {
                        c.send("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 10\r\n\r\n0123456789" + Support.get("/"));
                        assertEquals("012", c.read().text());
                        assertEquals("", c.read().text()); // the GET, which has no body
                    }
                }),
                test("empty lines before a request-line are skipped (RFC 9112 2.2)", () -> {
                    var calls = new AtomicInteger();
                    try (var server = Support.start(counting(calls)); var c = new Support.Client(server.port())) {
                        assertEquals(200, c.send("\r\n\r\n" + Support.get("/after-empty")).read().status());
                    }
                }),
                test("the request accessors: target, path, query, host, version, headers", () -> {
                    var seen = new java.util.concurrent.atomic.AtomicReference<String>();
                    Handler handler = request -> {
                        seen.set(request.method() + "|" + request.target() + "|" + request.path() + "|"
                                + request.query().orElse("<none>") + "|" + request.host() + "|" + request.version()
                                + "|" + request.headers().all("x-multi") + "|" + request.headers().first("X-ONE").orElse("-"));
                        return Response.status(204);
                    };
                    try (var server = Support.start(handler); var c = new Support.Client(server.port())) {
                        c.send("GET /a/b%20c?x=1&y HTTP/1.1\r\nHost: example.com:8080\r\nX-Multi: 1\r\nx-one:  v  \r\n"
                                + "X-MULTI: 2\r\n\r\n");
                        assertEquals(204, c.read().status());
                        assertEquals("GET|/a/b%20c?x=1&y|/a/b%20c|x=1&y|example.com:8080|HTTP_1_1|[1, 2]|v", seen.get());

                        c.send("GET http://other.example/p?q HTTP/1.1\r\nHost: example.com\r\n\r\n");
                        assertEquals(204, c.read().status());
                        assertEquals("GET|http://other.example/p?q|/p|q|other.example|HTTP_1_1|[]|-", seen.get());

                        c.send("OPTIONS * HTTP/1.1\r\nHost: example.com\r\n\r\n");
                        assertEquals(204, c.read().status());
                        assertEquals("OPTIONS|*|*|<none>|example.com|HTTP_1_1|[]|-", seen.get());

                        c.send("GET http://h.example HTTP/1.1\r\nHost: h.example\r\n\r\n");
                        assertEquals(204, c.read().status());
                        assertEquals("GET|http://h.example|/|<none>|h.example|HTTP_1_1|[]|-", seen.get());

                        c.send("PURGE /? HTTP/1.1\r\nHost: a\r\n\r\n");
                        assertEquals(204, c.read().status());
                        assertEquals("PURGE|/?|/||a|HTTP_1_1|[]|-", seen.get());
                    }
                }));
    }
}
