package spola.http;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.charset.StandardCharsets;
import spola.test.Suite;

/** {@code Expect: 100-continue} (RFC 9110 10.1.1, 15.2.1). */
final class ExpectTests {

    private ExpectTests() {
    }

    static Suite tests() {
        return tests(Transport.PLAIN);
    }

    /** The same tests over {@code t} (plain TCP or TLS). */
    static Suite tests(Transport t) {
        return suite("Expect: 100-continue",
                test("100 Continue is sent when the handler reads the body", () -> {
                    Handler handler = request -> Response.text(200, "got " + new String(request.bytes(),
                            StandardCharsets.US_ASCII));
                    try (var server = t.start(handler); var c = t.client(server.port())) {
                        c.send("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 5\r\nExpect: 100-continue\r\n\r\n");
                        // the client waits for 100 before it sends the body
                        assertEquals("HTTP/1.1 100 Continue", c.line());
                        assertEquals("", c.line());
                        var reply = c.send("hello").read();
                        assertEquals("got hello", reply.text());
                        // also for a chunked body, and the connection stays usable
                        c.send("POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\nExpect: 100-Continue\r\n\r\n");
                        assertEquals("HTTP/1.1 100 Continue", c.line());
                        assertEquals("", c.line());
                        assertEquals("got abc", c.send("3\r\nabc\r\n0\r\n\r\n").read().text());
                    }
                }),
                test("no 100 Continue when the handler answers without reading the body", () -> {
                    try (var server = t.start(request -> Response.text(401, "no"));
                         var c = t.client(server.port())) {
                        c.send("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 5\r\nExpect: 100-continue\r\n\r\n");
                        var reply = c.read();
                        assertEquals("HTTP/1.1 401 Unauthorized", reply.statusLine());
                        // the client may or may not send the body now, so the server closes
                        assertTrue(reply.closes());
                        assertTrue(c.closedByServer());
                    }
                }),
                test("no 100 Continue when Content-Length is already over maxBodySize: 413 at once", () -> {
                    var calls = new java.util.concurrent.atomic.AtomicInteger();
                    var options = Support.options().withLimits(HttpLimits.DEFAULT.withMaxBodySize(100));
                    try (var server = t.start(options, request -> {
                        calls.incrementAndGet();
                        return Response.status(204);
                    }); var c = t.client(server.port())) {
                        c.send("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 101\r\nExpect: 100-continue\r\n\r\n");
                        var reply = c.read();
                        assertEquals("HTTP/1.1 413 Content Too Large", reply.statusLine());
                        assertTrue(reply.closes());
                        assertTrue(c.closedByServer());
                    }
                    assertEquals(0, calls.get());
                }),
                test("Expect is ignored in HTTP/1.0 and for a request without a body", () -> {
                    Handler handler = request -> Response.text(200, new String(request.bytes(), StandardCharsets.US_ASCII));
                    try (var server = t.start(handler)) {
                        try (var c = t.client(server.port())) {
                            c.send("POST / HTTP/1.0\r\nContent-Length: 2\r\nExpect: 100-continue\r\n\r\nhi");
                            var reply = c.read();
                            assertEquals("HTTP/1.1 200 OK", reply.statusLine());
                            assertEquals("hi", reply.text());
                        }
                        try (var c = t.client(server.port())) {
                            var reply = c.send(Support.get("/", "Expect: 100-continue")).read();
                            assertEquals("HTTP/1.1 200 OK", reply.statusLine());
                            assertEquals(200, c.send(Support.get("/")).read().status());
                        }
                    }
                }));
    }
}
