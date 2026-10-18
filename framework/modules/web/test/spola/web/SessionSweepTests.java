package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.web.SessionSupport.cookie;
import static spola.web.SessionSupport.issued;
import static spola.web.SessionSupport.send;
import static spola.web.SessionSupport.setCookies;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import spola.test.Suite;

/**
 * Uppdrag 15a: the cleanup at the limit runs for one request at a time. A request that finds
 * a cleanup running is refused with 503 without walking the store itself.
 */
final class SessionSweepTests {

    private SessionSweepTests() {
    }

    static Suite tests() {
        return suite("one cleanup at a time at the limit (15a)",
                test("while a cleanup runs, a refused request gets 503 without a cleanup of its own; afterwards the"
                        + " next one cleans up and gets its session", () -> {
                    var clock = new SessionSupport.TestClock();
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT.withMaxSessions(2)
                            .withIdleTimeout(Duration.ofSeconds(10)), clock);
                    try (var server = Support.serve(SessionSupport.app(sessions))) {
                        issued(send(server, "POST", "/count"));
                        var kept = issued(send(server, "POST", "/count"));
                        clock.advance(Duration.ofSeconds(9));
                        assertEquals("found:1", send(server, "GET", "/find", cookie(kept)).body());
                        clock.advance(Duration.ofSeconds(1)); // the first one has expired, nothing removed it
                        assertEquals(2, sessions.size());
                        assertTrue(sessions.sweeping.compareAndSet(false, true), "the test holds the cleanup");
                        try {
                            var refused = send(server, "POST", "/count");
                            assertEquals(503, refused.status(), refused.toString());
                            assertEquals(List.of(), setCookies(refused));
                            assertEquals("{\"type\":\"about:blank\",\"title\":\"Service Unavailable\",\"status\":503,"
                                    + "\"detail\":\"The server cannot take more sessions right now.\","
                                    + "\"instance\":\"/count\"}", refused.body());
                            assertEquals(0, sessions.sweeps(), "no cleanup while another one runs");
                            assertEquals(2, sessions.size(), "the expired session is still there");
                        } finally {
                            sessions.sweeping.set(false);
                        }
                        var created = send(server, "POST", "/count");
                        assertEquals(200, created.status(), created.toString());
                        issued(created);
                        assertEquals(1, sessions.sweeps());
                        assertEquals(2, sessions.size());
                        assertEquals("found:1", send(server, "GET", "/find", cookie(kept)).body());
                    }
                }),
                test("the cleanup once a minute is skipped while a cleanup runs, and runs a minute later", () -> {
                    var clock = new SessionSupport.TestClock();
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT.withIdleTimeout(Duration.ofSeconds(10)),
                            clock);
                    try (var server = Support.serve(SessionSupport.app(sessions))) {
                        issued(send(server, "POST", "/count"));
                        clock.advance(Sessions.SWEEP_INTERVAL);
                        sessions.sweeping.set(true);
                        try {
                            send(server, "GET", "/plain");
                            assertEquals(0, sessions.sweeps());
                            assertEquals(1, sessions.size());
                        } finally {
                            sessions.sweeping.set(false);
                        }
                        send(server, "GET", "/plain");
                        assertEquals(0, sessions.sweeps(), "not due again until a minute later");
                        clock.advance(Sessions.SWEEP_INTERVAL);
                        send(server, "GET", "/plain");
                        assertEquals(1, sessions.sweeps());
                        assertEquals(0, sessions.size());
                    }
                }),
                test("200 refused requests at once at the limit of 100 000 sessions: the store is walked at most"
                        + " twice", () -> {
                    var clock = new SessionSupport.TestClock();
                    int max = 100_000;
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT.withMaxSessions(max), clock);
                    for (int i = 0; i < max; i++) {
                        sessions.create(); // live sessions whose ids no one knows: a long walk for each cleanup
                    }
                    assertEquals(max, sessions.size());
                    assertEquals(0, sessions.sweeps());
                    var start = new CountDownLatch(1);
                    try (var server = Support.serve(SessionSupport.app(sessions));
                            var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        var futures = new ArrayList<Future<Integer>>();
                        for (int t = 0; t < 200; t++) {
                            futures.add(executor.submit(() -> {
                                start.await();
                                var reply = send(server, "POST", "/count");
                                assertEquals(List.of(), setCookies(reply));
                                return reply.status();
                            }));
                        }
                        start.countDown();
                        for (var future : futures) {
                            assertEquals(503, future.get(60, TimeUnit.SECONDS));
                        }
                    }
                    // the first request to get the cleanup notes that no session expires for 30 minutes,
                    // and every later one skips it; those that came while it ran were refused at once
                    assertTrue(sessions.sweeps() >= 1 && sessions.sweeps() <= 2, sessions.sweeps() + " cleanups");
                    assertEquals(max, sessions.size());
                }));
    }
}
