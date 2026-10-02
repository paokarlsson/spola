package spola.http;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertNull;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import spola.test.Suite;

/** Chunked transfer coding in both directions (RFC 9112 7.1). */
final class ChunkedTests {

    private ChunkedTests() {
    }

    /** Writes "abc", flushes, writes "defgh", flushes, writes "ij" and returns. */
    static final BodyWriter THREE_PARTS = out -> {
        out.write("abc".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        out.write("defgh".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        out.flush(); // an empty flush must not end the body
        out.write("ij".getBytes(StandardCharsets.US_ASCII));
        out.close(); // has no effect
    };

    static Suite tests() {
        return suite("chunked",
                test("a chunked request body with extensions and trailers is read exactly", () -> {
                    var seen = new AtomicReference<String>();
                    Handler handler = request -> {
                        seen.set(new String(request.bytes(), StandardCharsets.US_ASCII));
                        return Response.text(200, "len=" + seen.get().length());
                    };
                    try (var server = Support.start(handler); var c = new Support.Client(server.port())) {
                        c.send("POST /up HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: Chunked\r\n\r\n"
                                + "5;name=value\r\nhello\r\n"
                                + "6 ; a ; b = \"q\\\"x;y\"\r\n world\r\n"
                                + "00A\r\n0123456789\r\n"
                                + "0;last=1\r\nTrailer-A: 1\r\nTrailer-B: two\r\n\r\n");
                        assertEquals("len=21", c.read().text());
                        assertEquals("hello world0123456789", seen.get());
                        // the trailers were consumed: the connection carries the next request
                        c.send("POST /up HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n");
                        assertEquals("len=0", c.read().text());
                        assertEquals("", seen.get());
                    }
                }),
                test("a streaming response is chunked for HTTP/1.1, byte for byte", () -> {
                    try (var server = Support.start(request -> Response.stream(200, "text/plain", THREE_PARTS));
                         var c = new Support.Client(server.port())) {
                        var head = c.send(Support.get("/s")).head();
                        assertEquals("HTTP/1.1 200 OK", head.statusLine());
                        assertEquals("chunked", head.header("Transfer-Encoding"));
                        assertNull(head.header("Content-Length"));
                        assertNull(head.header("Connection"));
                        var expected = "3\r\nabc\r\n5\r\ndefgh\r\n2\r\nij\r\n0\r\n\r\n";
                        assertEquals(expected, Support.ascii(c.exactly(expected.length())));
                        // the connection stays open for the next request
                        assertEquals(200, c.send(Support.get("/s")).read().status());
                    }
                }),
                test("a large streaming response goes out in chunks and arrives complete", () -> {
                    var data = new byte[300_000];
                    for (int i = 0; i < data.length; i++) {
                        data[i] = (byte) i;
                    }
                    try (var server = Support.start(request -> Response.stream(200, "application/octet-stream", out -> {
                        out.write(data, 0, 100);
                        out.write(data, 100, 200_000);
                        for (int i = 200_100; i < data.length; i++) {
                            out.write(data[i]);
                        }
                    })); var c = new Support.Client(server.port())) {
                        var reply = c.send(Support.get("/big")).read();
                        assertTrue(java.util.Arrays.equals(data, reply.body()), "body differs");
                    }
                }),
                test("a streaming response to HTTP/1.0 is delimited by closing the connection", () -> {
                    try (var server = Support.start(request -> Response.stream(200, "text/plain", THREE_PARTS));
                         var c = new Support.Client(server.port())) {
                        var head = c.send("GET /s HTTP/1.0\r\n\r\n").head();
                        assertEquals("HTTP/1.1 200 OK", head.statusLine());
                        assertNull(head.header("Transfer-Encoding"));
                        assertNull(head.header("Content-Length"));
                        assertEquals("close", head.header("Connection"));
                        assertEquals("abcdefghij", Support.ascii(c.rest()));
                    }
                }),
                test("HEAD with a streaming response: the head only, the writer is not called", () -> {
                    var called = new java.util.concurrent.atomic.AtomicBoolean();
                    try (var server = Support.start(request -> Response.stream(200, "text/plain", out -> called.set(true)));
                         var c = new Support.Client(server.port())) {
                        var head = c.send("HEAD / HTTP/1.1\r\nHost: a\r\n\r\n").read(true);
                        assertEquals(200, head.status());
                        assertEquals("chunked", head.header("Transfer-Encoding"));
                        assertFalse(called.get(), "the writer was called for HEAD");
                        // the connection carries the next request; a GET calls the writer
                        assertEquals(200, c.send(Support.get("/")).read().status());
                        assertTrue(called.get());
                    }
                }),
                test("a chunked body over maxBodySize gives 413", () -> {
                    var options = Support.options().withLimits(HttpLimits.DEFAULT.withMaxBodySize(10));
                    try (var server = Support.start(options, request -> Response.text(200, new String(request.bytes(),
                            StandardCharsets.US_ASCII)))) {
                        try (var c = new Support.Client(server.port())) {
                            c.send("POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n"
                                    + "5\r\nhello\r\n5\r\nworld\r\n0\r\n\r\n");
                            assertEquals("helloworld", c.read().text()); // exactly at the limit
                            c.send("POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n"
                                    + "5\r\nhello\r\n6\r\nworld!\r\n0\r\n\r\n");
                            var reply = c.read();
                            assertEquals(413, reply.status());
                            assertTrue(reply.closes());
                            assertTrue(c.closedByServer());
                        }
                    }
                    // a handler that does not read the body: the server skips the rest before it answers,
                    // stops at the limit and answers 413 instead of the handler's response
                    var ignoring = Support.options().withLimits(HttpLimits.DEFAULT.withMaxBodySize(10));
                    try (var server = Support.start(ignoring, request -> Response.text(200, "ignored"));
                         var c = new Support.Client(server.port())) {
                        c.send("POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n"
                                + "b\r\nhello world\r\n0\r\n\r\n" + Support.get("/never"));
                        var reply = c.read();
                        assertEquals(413, reply.status());
                        assertTrue(reply.closes());
                        assertTrue(c.closedByServer(), "closed instead of reading past the limit");
                    }
                }),
                test("an unread body with broken framing after a streaming response closes the connection", () -> {
                    // A body writer may read the request body, so the server cannot skip it before a
                    // streaming response: the response goes out, then the framing error ends the connection.
                    var paths = new java.util.concurrent.CopyOnWriteArrayList<String>();
                    Handler handler = request -> {
                        paths.add(request.path());
                        return Response.stream(200, "text/plain", THREE_PARTS);
                    };
                    try (var server = Support.start(handler); var c = new Support.Client(server.port())) {
                        c.send("POST /first HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n"
                                + "5\r\nhelloX\r\n0\r\n\r\n" + Support.get("/never"));
                        assertEquals("abcdefghij", c.read().text());
                        assertTrue(c.closedByServer(), "closed after the response");
                        assertEquals(java.util.List.of("/first"), java.util.List.copyOf(paths));
                    }
                }));
    }
}
