package spola.http;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import spola.test.Suite;

/** Many clients at once against one server. */
final class ConcurrencyTests {

    private ConcurrencyTests() {
    }

    static final int CLIENTS = 1_000;
    static final int REQUESTS = 3;

    static Suite tests() {
        return suite("concurrency",
                test("1 000 concurrent keep-alive clients with 3 requests each get the right answers", () -> {
                    Handler handler = request -> {
                        var body = new String(request.bytes(), StandardCharsets.US_ASCII);
                        return Response.text(200, request.path() + "|" + body);
                    };
                    var failures = new ConcurrentLinkedQueue<String>();
                    var ok = new AtomicInteger();
                    var start = new CountDownLatch(1);
                    try (var server = Support.start(handler)) {
                        var threads = new ArrayList<Thread>();
                        for (int i = 0; i < CLIENTS; i++) {
                            int client = i;
                            threads.add(Thread.ofVirtual().start(() -> {
                                try {
                                    start.await();
                                    try (var c = new Support.Client(server.port(), 20_000)) {
                                        for (int r = 0; r < REQUESTS; r++) {
                                            var body = "body-" + client + "-" + r;
                                            var reply = c.send("POST /c" + client + "/r" + r + " HTTP/1.1\r\nHost: a\r\n"
                                                    + "Content-Length: " + body.length() + "\r\n\r\n" + body).read();
                                            var expected = "/c" + client + "/r" + r + "|" + body;
                                            if (reply.status() != 200 || !reply.text().equals(expected)) {
                                                failures.add(client + "/" + r + ": " + reply.statusLine() + " " + reply.text());
                                            } else {
                                                ok.incrementAndGet();
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    failures.add(client + ": " + e);
                                }
                            }));
                        }
                        start.countDown();
                        for (var thread : threads) {
                            assertTrue(thread.join(java.time.Duration.ofSeconds(30)), "a client did not finish");
                        }
                    }
                    assertEquals(java.util.List.of(), failures.stream().limit(5).toList());
                    assertEquals(CLIENTS * REQUESTS, ok.get());
                }));
    }
}
