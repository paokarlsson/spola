package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.web.SessionSupport.ID;
import static spola.web.SessionSupport.call;
import static spola.web.SessionSupport.client;
import static spola.web.SessionSupport.idOf;
import static spola.web.SessionSupport.setCookies;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import spola.test.Suite;

/** Criteria 9 and 12: 10 000 ids, and 200 virtual threads at once. */
final class SessionScaleTests {

    private SessionScaleTests() {
    }

    static Suite tests() {
        return suite("session scale and concurrency",
                test("10 000 sessions: every id differs and is 43 characters of [A-Za-z0-9_-]", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    try (var server = Support.serve(SessionSupport.app(sessions));
                            var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        var futures = new ArrayList<Future<List<String>>>();
                        for (int t = 0; t < 16; t++) {
                            futures.add(executor.submit(() -> {
                                var ids = new ArrayList<String>();
                                try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                                        .connectTimeout(Duration.ofSeconds(5)).build()) {
                                    for (int i = 0; i < 625; i++) {
                                        var response = call(client, server, "POST", "/count");
                                        if (response.statusCode() != 200) {
                                            throw new AssertionError("status " + response.statusCode());
                                        }
                                        for (var setCookie : response.headers().allValues("Set-Cookie")) {
                                            ids.add(idOf(setCookie));
                                        }
                                    }
                                }
                                return ids;
                            }));
                        }
                        var all = new ArrayList<String>();
                        for (var future : futures) {
                            all.addAll(future.get(60, TimeUnit.SECONDS));
                        }
                        assertEquals(10_000, all.size());
                        assertEquals(10_000, new HashSet<>(all).size(), "every id differs");
                        for (var id : all) {
                            assertTrue(ID.matcher(id).matches(), id);
                        }
                        assertEquals(10_000, sessions.size());
                        var full = SessionSupport.send(server, "POST", "/count");
                        assertEquals(503, full.status(), "the default limit is 10 000");
                        assertEquals(List.of(), setCookies(full));
                    }
                }),
                test("200 virtual threads, each with its own HttpClient and CookieManager: count, renew, count,"
                        + " invalidate", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    var start = new CountDownLatch(1);
                    try (var server = Support.serve(SessionSupport.app(sessions));
                            var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        var futures = new ArrayList<Future<List<String>>>();
                        for (int t = 0; t < 200; t++) {
                            futures.add(executor.submit(() -> {
                                start.await();
                                var ids = new ArrayList<String>();
                                var seen = new ArrayList<String>();
                                var expected = new ArrayList<String>();
                                try (var client = client()) {
                                    for (int i = 1; i <= 10; i++) {
                                        var response = call(client, server, "POST", "/count");
                                        seen.add(response.body());
                                        expected.add(String.valueOf(i));
                                        response.headers().allValues("Set-Cookie").forEach(c -> ids.add(idOf(c)));
                                    }
                                    var renewed = call(client, server, "POST", "/renew");
                                    seen.add(renewed.body());
                                    expected.add("renewed:10");
                                    renewed.headers().allValues("Set-Cookie").forEach(c -> ids.add(idOf(c)));
                                    seen.add(call(client, server, "POST", "/count").body());
                                    expected.add("11");
                                    var removed = call(client, server, "POST", "/invalidate");
                                    seen.add(removed.body() + " " + removed.headers().allValues("Set-Cookie"));
                                    expected.add("invalidated [" + SessionFlowTests.REMOVAL + "]");
                                    seen.add(call(client, server, "GET", "/find").body());
                                    expected.add("none");
                                }
                                if (!seen.equals(expected)) {
                                    throw new AssertionError("expected " + expected + ", saw " + seen);
                                }
                                return ids;
                            }));
                        }
                        start.countDown();
                        var all = new ArrayList<String>();
                        for (var future : futures) {
                            var ids = future.get(120, TimeUnit.SECONDS);
                            assertEquals(2, ids.size(), "one id when created, one when renewed: " + ids.size());
                            all.addAll(ids);
                        }
                        assertEquals(400, new HashSet<>(all).size(), "every id differs");
                        assertEquals(0, sessions.size());
                    }
                }),
                test("200 virtual threads call getOrCreate at once against maxSessions 100: exactly 100 get a session,"
                        + " 100 get 503", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT.withMaxSessions(100));
                    var start = new CountDownLatch(1);
                    try (var server = Support.serve(SessionSupport.app(sessions));
                            var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        var futures = new ArrayList<Future<Support.Reply>>();
                        for (int t = 0; t < 200; t++) {
                            futures.add(executor.submit(() -> {
                                start.await();
                                return SessionSupport.send(server, "POST", "/count");
                            }));
                        }
                        start.countDown();
                        int created = 0;
                        int refused = 0;
                        var ids = new HashSet<String>();
                        for (var future : futures) {
                            var reply = future.get(60, TimeUnit.SECONDS);
                            switch (reply.status()) {
                                case 200 -> {
                                    created++;
                                    ids.add(SessionSupport.issued(reply));
                                }
                                case 503 -> {
                                    refused++;
                                    assertEquals(List.of(), setCookies(reply));
                                }
                                default -> throw new AssertionError("unexpected " + reply);
                            }
                        }
                        assertEquals(100, created);
                        assertEquals(100, refused);
                        assertEquals(100, ids.size());
                        assertEquals(100, sessions.size());
                    }
                }));
    }
}
