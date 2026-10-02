package spola.http;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import spola.test.Suite;

/** Graceful stop: close() refuses new connections, closes idle ones and lets requests in progress finish. */
final class ShutdownTests {

    private ShutdownTests() {
    }

    private static boolean refused(int port) {
        try (var socket = new Socket(Support.LOCALHOST, port)) {
            return !socket.isConnected();
        } catch (ConnectException e) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static Suite tests() {
        return suite("graceful stop",
                test("a request in progress finishes with Connection: close; idle connections are closed", () -> {
                    var entered = new CountDownLatch(1);
                    var release = new CountDownLatch(1);
                    Handler handler = request -> {
                        if (request.path().equals("/slow")) {
                            entered.countDown();
                            release.await();
                            return Response.text(200, "slow done");
                        }
                        return Response.text(200, "quick");
                    };
                    var server = Support.start(handler);
                    int port = server.port();
                    try (var idle = new Support.Client(port); var busy = new Support.Client(port)) {
                        assertEquals("quick", idle.send(Support.get("/")).read().text());
                        busy.send(Support.get("/slow"));
                        assertTrue(entered.await(5, TimeUnit.SECONDS));

                        var closer = Thread.ofVirtual().start(server::close);
                        // the idle keep-alive connection is closed at once
                        assertTrue(idle.closedByServer(), "idle connection closed");
                        // new connections are refused
                        long deadline = System.nanoTime() + 2_000_000_000L;
                        while (!refused(port) && System.nanoTime() < deadline) {
                            Thread.sleep(10);
                        }
                        assertTrue(refused(port), "new connections are refused");
                        // close() waits for the request in progress
                        assertFalse(closer.join(Duration.ofMillis(300)), "close() returned before the request finished");
                        release.countDown();
                        var reply = busy.read();
                        assertEquals("slow done", reply.text());
                        assertTrue(reply.closes(), "Connection: close on the last response");
                        assertTrue(busy.closedByServer());
                        assertTrue(closer.join(Duration.ofSeconds(5)), "close() returned");
                    } finally {
                        release.countDown();
                        server.close();
                    }
                }),
                test("a request that does not finish is aborted after shutdownTimeout and close() returns", () -> {
                    var entered = new CountDownLatch(1);
                    var interrupted = new CountDownLatch(1);
                    Handler handler = request -> {
                        entered.countDown();
                        try {
                            Thread.sleep(60_000);
                        } catch (InterruptedException e) {
                            interrupted.countDown();
                            throw e;
                        }
                        return Response.text(200, "never");
                    };
                    var options = Support.options().withTimeouts(Support.FAST.withShutdownTimeout(Duration.ofMillis(300)));
                    var server = Support.start(options, handler);
                    try (var busy = new Support.Client(server.port())) {
                        busy.send(Support.get("/stuck"));
                        assertTrue(entered.await(5, TimeUnit.SECONDS));
                        long start = System.nanoTime();
                        server.close();
                        long elapsed = (System.nanoTime() - start) / 1_000_000;
                        assertTrue(elapsed >= 250 && elapsed < 3_000, "close() took " + elapsed + " ms");
                        assertTrue(interrupted.await(1, TimeUnit.SECONDS), "the handler thread was interrupted");
                        var rest = Support.ascii(busy.rest());
                        assertFalse(rest.contains("never"), "no complete response: " + rest);
                    } finally {
                        server.close();
                    }
                }),
                test("a handler may stop its own server", () -> {
                    var holder = new java.util.concurrent.atomic.AtomicReference<HttpServer>();
                    var server = Support.start(request -> {
                        holder.get().close();
                        return Response.text(200, "stopping");
                    });
                    holder.set(server);
                    try (var c = new Support.Client(server.port())) {
                        var reply = c.send(Support.get("/stop")).read();
                        assertEquals("stopping", reply.text());
                        assertTrue(reply.closes());
                        assertTrue(c.closedByServer());
                    }
                    assertTrue(refused(server.port()));
                    server.close();
                }),
                test("close() twice and close() of an unused server are fine", () -> {
                    var server = Support.start(request -> Response.status(204));
                    server.close();
                    server.close();
                    assertTrue(refused(server.port()));
                }));
    }
}
