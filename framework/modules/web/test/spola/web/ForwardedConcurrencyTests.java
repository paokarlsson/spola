package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import spola.test.Suite;

/** Criterion 10: 200 virtual threads at once, each with its own headers, each gets exactly its own values. */
final class ForwardedConcurrencyTests {

    private ForwardedConcurrencyTests() {
    }

    static final int CLIENTS = 200;

    static Suite tests() {
        return suite("ForwardedHeaders under concurrency",
                test("200 concurrent clients with their own X-Forwarded-For, every other one with https", () -> {
                    var calls = new AtomicInteger();
                    var log = new Support.CapturedLog();
                    var router = ForwardedSupport.whoRouter(ForwardedSupport.TRUSTING, calls, log);
                    var failures = new ConcurrentLinkedQueue<String>();
                    var ok = new AtomicInteger();
                    var start = new CountDownLatch(1);
                    try (var server = Support.serve(router)) {
                        var threads = new ArrayList<Thread>();
                        for (int i = 0; i < CLIENTS; i++) {
                            int client = i;
                            threads.add(Thread.ofVirtual().start(() -> {
                                // even clients IPv4, odd ones IPv6, each behind its own trusted proxy
                                var address = client % 2 == 0 ? "198.51.100." + client
                                        : "2001:db8:0:0:0:0:0:" + Integer.toHexString(client);
                                var scheme = client % 2 == 0 ? "https" : "http";
                                var lines = new ArrayList<String>();
                                lines.add("X-Forwarded-For: " + address + ", 10.0." + client + ".1");
                                if (scheme.equals("https")) {
                                    lines.add("X-Forwarded-Proto: https");
                                }
                                var expected = scheme + " " + address + " "
                                        + (client % 2 == 0 ? "Inet4Address" : "Inet6Address");
                                try {
                                    start.await();
                                    var reply = Support.send(server.port(), "GET", "/who", lines.toArray(String[]::new));
                                    if (reply.status() == 200 && reply.body().equals(expected)) {
                                        ok.incrementAndGet();
                                    } else {
                                        failures.add(client + ": expected " + expected + ", got " + reply);
                                    }
                                } catch (Exception e) {
                                    failures.add(client + ": " + e);
                                }
                            }));
                        }
                        start.countDown();
                        for (var thread : threads) {
                            assertTrue(thread.join(Duration.ofSeconds(30)), "a client did not finish");
                        }
                    }
                    assertEquals(List.of(), failures.stream().limit(5).toList());
                    assertEquals(CLIENTS, ok.get());
                    assertEquals(CLIENTS, calls.get());
                    assertEquals(List.of(), log.records());
                }));
    }
}
