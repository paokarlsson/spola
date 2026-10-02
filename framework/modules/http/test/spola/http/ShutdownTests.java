package spola.http;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
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

    /** The next line of {@code lines} that starts with {@code prefix}, waiting at most 10 s. */
    private static String awaitLine(BlockingQueue<String> lines, String prefix, StringBuffer output)
            throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (true) {
            var line = lines.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (line == null) {
                throw new AssertionError("no line starting with \"" + prefix + "\" from the child JVM:\n" + output);
            }
            if (line.startsWith(prefix)) {
                return line;
            }
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
                test("close() from outside and from a handler at the same time both return", () -> {
                    var entered = new CountDownLatch(1);
                    var externalStarted = new CountDownLatch(1);
                    var holder = new java.util.concurrent.atomic.AtomicReference<HttpServer>();
                    var handlerClosed = new java.util.concurrent.atomic.AtomicBoolean();
                    Handler handler = request -> {
                        entered.countDown();
                        externalStarted.await();
                        while (!holder.get().closing()) { // the outside close() goes first
                            Thread.sleep(5);
                        }
                        Thread.sleep(50); // and is waiting for this connection
                        holder.get().close(); // the second call, on a connection thread: returns at once
                        handlerClosed.set(true);
                        return Response.text(200, "stopping");
                    };
                    var options = Support.options().withTimeouts(Support.FAST.withShutdownTimeout(Duration.ofSeconds(1)));
                    var server = Support.start(options, handler);
                    holder.set(server);
                    try (var c = new Support.Client(server.port())) {
                        c.send(Support.get("/stop"));
                        assertTrue(entered.await(5, TimeUnit.SECONDS));
                        var closer = Thread.ofVirtual().start(() -> {
                            externalStarted.countDown();
                            server.close();
                        });
                        var reply = c.read();
                        assertEquals("stopping", reply.text());
                        assertTrue(reply.closes(), "Connection: close");
                        assertTrue(c.closedByServer());
                        assertTrue(closer.join(Duration.ofSeconds(5)), "the outside close() returned");
                        assertTrue(handlerClosed.get(), "the handler's close() returned");
                    } finally {
                        externalStarted.countDown();
                        server.close();
                    }
                }),
                test("a second close() from outside waits for the first", () -> {
                    var entered = new CountDownLatch(1);
                    var release = new CountDownLatch(1);
                    var server = Support.start(request -> {
                        entered.countDown();
                        release.await();
                        return Response.text(200, "done");
                    });
                    try (var c = new Support.Client(server.port())) {
                        c.send(Support.get("/slow"));
                        assertTrue(entered.await(5, TimeUnit.SECONDS));
                        var first = Thread.ofVirtual().start(server::close);
                        Thread.sleep(50);
                        var second = Thread.ofVirtual().start(server::close);
                        assertFalse(second.join(Duration.ofMillis(200)), "the second close() returned early");
                        release.countDown();
                        assertTrue(first.join(Duration.ofSeconds(5)));
                        assertTrue(second.join(Duration.ofSeconds(5)));
                        assertEquals("done", c.read().text());
                    } finally {
                        release.countDown();
                        server.close();
                    }
                }),
                test("a handler that ignores the interrupt does not keep close() from returning", () -> {
                    var entered = new CountDownLatch(1);
                    var release = new java.util.concurrent.atomic.AtomicBoolean();
                    Handler handler = request -> {
                        entered.countDown();
                        while (!release.get()) {
                            try {
                                Thread.sleep(20);
                            } catch (InterruptedException e) {
                                // ignored on purpose
                            }
                        }
                        return Response.text(200, "late");
                    };
                    var options = Support.options().withTimeouts(Support.FAST.withShutdownTimeout(Duration.ofMillis(300)));
                    var server = Support.start(options, handler);
                    try (var c = new Support.Client(server.port())) {
                        c.send(Support.get("/stuck"));
                        assertTrue(entered.await(5, TimeUnit.SECONDS));
                        long start = System.nanoTime();
                        server.close();
                        long elapsed = (System.nanoTime() - start) / 1_000_000;
                        assertTrue(elapsed >= 250 && elapsed < 300 + HttpServer.ABORT_GRACE_MILLIS + 1_500,
                                "close() took " + elapsed + " ms");
                    } finally {
                        release.set(true);
                        server.close();
                    }
                }),
                test("a request in progress at close() with pipelined input still gets its whole response", () -> {
                    // closing a socket with unread input sends a reset that can destroy the response;
                    // the server lingers (within shutdownTimeout) during a stop as well (RFC 9112 9.6)
                    var entered = new CountDownLatch(1);
                    var release = new CountDownLatch(1);
                    var big = "x".repeat(200_000);
                    var server = Support.start(request -> {
                        entered.countDown();
                        release.await();
                        return Response.text(200, big);
                    });
                    try (var c = new Support.Client(server.port())) {
                        c.send(Support.get("/slow"));
                        assertTrue(entered.await(5, TimeUnit.SECONDS));
                        c.send(Support.get("/next") + Support.get("/next")); // unread in the server's socket
                        var closer = Thread.ofVirtual().start(server::close);
                        Thread.sleep(100);
                        release.countDown();
                        Thread.sleep(300); // the server has answered and closed before the client reads
                        var reply = c.read();
                        assertEquals(big.length(), reply.text().length());
                        assertTrue(reply.closes());
                        c.socket.close(); // the client is done: the server stops lingering
                        assertTrue(closer.join(Duration.ofSeconds(5)), "close() returned");
                    } finally {
                        release.countDown();
                        server.close();
                    }
                }),
                test("main returns after start() and a handler stops the server: the JVM waits for every response", () -> {
                    // only the server keeps the child JVM alive; the handler of /shutdown calls close()
                    // while /slow is in progress, and both responses must arrive before the JVM exits
                    var classes = Path.of(ReturningMainProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                    var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                    var process = new ProcessBuilder(java, "--module-path", classes.toString(),
                            "--module", "spola.http/" + ReturningMainProbe.class.getName()).redirectErrorStream(true).start();
                    process.getOutputStream().close();
                    var lines = new LinkedBlockingQueue<String>();
                    var output = new StringBuffer();
                    var reader = Thread.ofVirtual().start(() -> {
                        try (var in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                            for (var line = in.readLine(); line != null; line = in.readLine()) {
                                output.append(line).append('\n');
                                lines.add(line);
                            }
                        } catch (IOException e) {
                            output.append("[reading failed: ").append(e).append("]\n");
                        }
                    });
                    try {
                        int port = Integer.parseInt(awaitLine(lines, "port ", output).substring("port ".length()));
                        try (var slow = new Support.Client(port); var stop = new Support.Client(port)) {
                            slow.send(Support.get("/slow"));
                            awaitLine(lines, "entered /slow", output);
                            stop.send(Support.get("/shutdown"));
                            var done = slow.read();
                            assertEquals("slow done", done.text(), output.toString());
                            assertTrue(done.closes(), "Connection: close on the request in progress");
                            var bye = stop.read();
                            assertEquals("bye", bye.text(), output.toString());
                            assertTrue(bye.closes(), "Connection: close on the handler's own response");
                            assertTrue(stop.closedByServer());
                        }
                        assertTrue(process.waitFor(15, TimeUnit.SECONDS), "the child JVM exited:\n" + output);
                        assertEquals(0, process.exitValue(), output.toString());
                        assertTrue(reader.join(Duration.ofSeconds(5)), "the child's output ended");
                        assertTrue(output.toString().contains("stopped listening"), output.toString());
                    } finally {
                        process.destroyForcibly();
                    }
                }),
                test("close() twice and close() of an unused server are fine", () -> {
                    var server = Support.start(request -> Response.status(204));
                    server.close();
                    server.close();
                    assertTrue(refused(server.port()));
                }));
    }
}
