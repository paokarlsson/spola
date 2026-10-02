package spola.http;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import spola.test.Suite;

/** The timeouts, with short values: header (total), idle, read (body) and write (guard). */
final class TimeoutTests {

    private TimeoutTests() {
    }

    private static final Duration LONG = Duration.ofSeconds(10);

    private static HttpServerOptions timeouts(Duration header, Duration idle, Duration read, Duration write) {
        return Support.options().withTimeouts(new HttpTimeouts(header, idle, read, write, Duration.ofSeconds(3)));
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    static Suite tests() {
        return suite("timeouts",
                test("headerTimeout is a total: a client sending one byte every 50 ms is cut off", () -> {
                    var options = timeouts(Duration.ofMillis(500), LONG, LONG, LONG);
                    try (var server = Support.start(options, request -> Response.status(204));
                         var c = new Support.Client(server.port())) {
                        var request = ("GET / HTTP/1.1\r\nHost: localhost\r\nX-Slow: " + "a".repeat(200))
                                .getBytes(StandardCharsets.US_ASCII);
                        var start = System.nanoTime();
                        var writer = Thread.ofVirtual().start(() -> {
                            try {
                                for (byte b : request) {
                                    c.out.write(b);
                                    c.out.flush();
                                    Thread.sleep(50); // far below every per-read limit (10 s)
                                }
                            } catch (IOException | InterruptedException e) {
                                // the server closed the connection
                            }
                        });
                        var reply = c.read();
                        long elapsed = millisSince(start);
                        assertEquals(408, reply.status());
                        assertTrue(reply.closes());
                        assertTrue(c.closedByServer());
                        assertTrue(elapsed >= 450 && elapsed < 2_500, "cut off after " + elapsed + " ms");
                        writer.interrupt();
                        writer.join();
                    }
                }),
                test("a new connection that sends nothing is closed after headerTimeout", () -> {
                    var options = timeouts(Duration.ofMillis(300), LONG, LONG, LONG);
                    try (var server = Support.start(options, request -> Response.status(204));
                         var c = new Support.Client(server.port())) {
                        var start = System.nanoTime();
                        assertTrue(c.closedByServer());
                        long elapsed = millisSince(start);
                        assertTrue(elapsed >= 250 && elapsed < 2_500, "closed after " + elapsed + " ms");
                    }
                }),
                test("an idle keep-alive connection is closed after idleTimeout, without a response", () -> {
                    var options = timeouts(LONG, Duration.ofMillis(300), LONG, LONG);
                    try (var server = Support.start(options, request -> Response.status(204));
                         var c = new Support.Client(server.port())) {
                        assertEquals(204, c.send(Support.get("/")).read().status());
                        var start = System.nanoTime();
                        assertTrue(c.closedByServer(), "closed without sending anything");
                        long elapsed = millisSince(start);
                        assertTrue(elapsed >= 250 && elapsed < 2_500, "closed after " + elapsed + " ms");
                    }
                }),
                test("a body that stops arriving is cut off after readTimeout", () -> {
                    var options = timeouts(LONG, LONG, Duration.ofMillis(300), LONG);
                    var thrown = new AtomicReference<Throwable>();
                    Handler handler = request -> {
                        try {
                            return Response.text(200, new String(request.bytes(), StandardCharsets.US_ASCII));
                        } catch (IOException e) {
                            thrown.set(e);
                            throw e;
                        }
                    };
                    try (var server = Support.start(options, handler); var c = new Support.Client(server.port())) {
                        c.send("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 10\r\n\r\nabc");
                        var start = System.nanoTime();
                        var reply = c.read();
                        long elapsed = millisSince(start);
                        assertEquals(408, reply.status());
                        assertTrue(reply.closes());
                        assertTrue(c.closedByServer());
                        assertTrue(elapsed >= 250 && elapsed < 2_500, "cut off after " + elapsed + " ms");
                        assertTrue(thrown.get() instanceof RequestBodyException e && e.status() == 408,
                                "the handler saw " + thrown.get());
                    }
                }),
                test("a client that stops reading a large streaming response is cut off after writeTimeout", () -> {
                    var options = timeouts(LONG, LONG, LONG, Duration.ofMillis(300));
                    var done = new CountDownLatch(1);
                    var stalledAt = new AtomicLong();
                    var stalledFor = new AtomicLong();
                    var failure = new AtomicReference<Throwable>();
                    Handler handler = request -> {
                        if (request.path().equals("/ping")) {
                            return Response.text(200, "pong");
                        }
                        return Response.stream(200, "application/octet-stream", out -> {
                            var block = new byte[64 * 1024];
                            try {
                                while (true) { // endless: only the write timeout can stop it
                                    stalledAt.set(System.nanoTime());
                                    out.write(block);
                                }
                            } catch (IOException e) {
                                stalledFor.set(millisSince(stalledAt.get()));
                                failure.set(e);
                                done.countDown();
                                throw e;
                            }
                        });
                    };
                    try (var server = Support.start(options, handler); var socket = new Socket()) {
                        socket.setReceiveBufferSize(4 * 1024);
                        socket.connect(new InetSocketAddress(Support.LOCALHOST, server.port()));
                        socket.getOutputStream().write(Support.get("/stream").getBytes(StandardCharsets.US_ASCII));
                        // never read
                        assertTrue(done.await(10, TimeUnit.SECONDS), "the handler's write never failed");
                        assertTrue(failure.get() instanceof IOException);
                        // the last write call blocked for the write timeout before it failed
                        assertTrue(stalledFor.get() >= 250 && stalledFor.get() < 3_000,
                                "the blocked write failed after " + stalledFor.get() + " ms");
                        // the server is fine
                        try (var c = new Support.Client(server.port())) {
                            assertEquals("pong", c.send(Support.get("/ping")).read().text());
                        }
                        // the connection was closed: draining it ends
                        socket.setSoTimeout(5_000);
                        var in = socket.getInputStream();
                        var buffer = new byte[64 * 1024];
                        try {
                            while (in.read(buffer) >= 0) {
                                // what was in flight
                            }
                        } catch (java.net.SocketException e) {
                            // reset: closed as well
                        }
                    }
                }),
                test("a slow but steady reader is not cut off by writeTimeout", () -> {
                    var options = timeouts(LONG, LONG, LONG, Duration.ofMillis(300));
                    var data = new byte[2 * 1024 * 1024];
                    try (var server = Support.start(options, request -> Response.bytes(200, "application/octet-stream",
                            data)); var socket = new Socket()) {
                        socket.setReceiveBufferSize(8 * 1024);
                        socket.connect(new InetSocketAddress(Support.LOCALHOST, server.port()));
                        socket.getOutputStream().write(Support.get("/").getBytes(StandardCharsets.US_ASCII));
                        var in = socket.getInputStream();
                        var buffer = new byte[64 * 1024];
                        long total = 0;
                        var start = System.nanoTime();
                        int n;
                        while ((n = in.read(buffer)) >= 0) {
                            total += n;
                            if (total > 200_000 && total < 2_000_000 && millisSince(start) < 900) {
                                Thread.sleep(5); // slower than the network, never stalled for 300 ms
                            }
                            if (total >= data.length + 100) {
                                break; // head + body
                            }
                        }
                        assertTrue(millisSince(start) > 300, "the transfer took longer than the write timeout");
                        assertTrue(total >= data.length, "got " + total + " bytes");
                    }
                }));
    }
}
