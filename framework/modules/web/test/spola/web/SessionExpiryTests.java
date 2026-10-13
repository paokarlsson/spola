package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertNotEquals;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.web.SessionSupport.cookie;
import static spola.web.SessionSupport.issued;
import static spola.web.SessionSupport.send;
import static spola.web.SessionSupport.setCookies;
import static spola.web.SessionSupport.values;

import java.time.Duration;
import java.util.List;
import spola.test.Suite;

/** Criteria 6 and 7: expiry with a controlled clock, the cleanup, and the limit with 503. */
final class SessionExpiryTests {

    private SessionExpiryTests() {
    }

    static final Duration SECOND = Duration.ofSeconds(1);

    static Suite tests() {
        return suite("session expiry and limit",
                test("a session lives idleTimeout minus a second after its last use and is gone at idleTimeout;"
                        + " every request with the cookie moves the expiry", () -> {
                    var clock = new SessionSupport.TestClock();
                    var idle = Duration.ofMinutes(30);
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT.withIdleTimeout(idle), clock);
                    try (var server = Support.serve(SessionSupport.app(sessions))) {
                        var a = issued(send(server, "POST", "/count"));
                        var b = issued(send(server, "POST", "/count"));
                        assertEquals(2, sessions.size());
                        clock.advance(idle.minus(SECOND));
                        assertEquals("found:1", send(server, "GET", "/find", cookie(a)).body(),
                                "alive idleTimeout minus a second after the last use");
                        clock.advance(SECOND);
                        assertEquals("none", send(server, "GET", "/find", cookie(b)).body(), "gone at idleTimeout");
                        assertEquals(1, sessions.size(), "the expired session was removed");
                        // a was last used a second before this, by /find: idleTimeout minus a second after that use
                        clock.advance(idle.minus(SECOND).minus(SECOND));
                        var plain = send(server, "GET", "/plain", cookie(a));
                        assertEquals("plain", plain.body());
                        assertEquals(List.of(), setCookies(plain));
                        clock.advance(idle.minus(SECOND));
                        assertEquals("found:1", send(server, "GET", "/find", cookie(a)).body(),
                                "a route that never reads the session moved the expiry too");
                        clock.advance(idle.minus(SECOND));
                        assertEquals("2", send(server, "POST", "/count", cookie(a)).body());
                        clock.advance(idle);
                        assertEquals("none", send(server, "GET", "/find", cookie(a)).body(),
                                "exactly idleTimeout after the last use");
                        assertEquals(0, sessions.size());
                    }
                }),
                test("a request without a cookie, more than idleTimeout plus a minute later, removes every expired"
                        + " session", () -> {
                    var clock = new SessionSupport.TestClock();
                    var idle = Duration.ofMinutes(30);
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT.withIdleTimeout(idle), clock);
                    try (var server = Support.serve(SessionSupport.app(sessions))) {
                        for (int i = 0; i < 5; i++) {
                            issued(send(server, "POST", "/count"));
                        }
                        var kept = issued(send(server, "POST", "/count"));
                        assertEquals(6, sessions.size());
                        clock.advance(idle.plus(Duration.ofMinutes(1)).plus(SECOND));
                        assertEquals(6, sessions.size(), "expired, but nothing removed them yet");
                        assertEquals("plain", send(server, "GET", "/plain").body());
                        assertEquals(0, sessions.size());
                        assertEquals("none", send(server, "GET", "/find", cookie(kept)).body());
                    }
                }),
                test("the cleanup runs at most once a minute; a session that is not expired stays", () -> {
                    var clock = new SessionSupport.TestClock();
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT.withIdleTimeout(Duration.ofSeconds(10)), clock);
                    try (var server = Support.serve(SessionSupport.app(sessions))) {
                        issued(send(server, "POST", "/count"));
                        clock.advance(Duration.ofSeconds(30));
                        var young = issued(send(server, "POST", "/count"));
                        send(server, "GET", "/plain");
                        assertEquals(2, sessions.size(), "the first one expired, but a minute has not passed");
                        clock.advance(Duration.ofSeconds(30));
                        send(server, "GET", "/plain");
                        assertEquals(0, sessions.size(), "a minute passed: both expired ones removed");
                        assertEquals("none", send(server, "GET", "/find", cookie(young)).body());
                        var old = issued(send(server, "POST", "/count"));
                        clock.advance(Duration.ofSeconds(55));
                        var fresh = issued(send(server, "POST", "/count"));
                        assertEquals(2, sessions.size());
                        clock.advance(Duration.ofSeconds(5));
                        send(server, "GET", "/plain");
                        assertEquals(1, sessions.size(), "the next cleanup, a minute later, removed only the expired one");
                        assertEquals("found:1", send(server, "GET", "/find", cookie(fresh)).body());
                        assertEquals("none", send(server, "GET", "/find", cookie(old)).body());
                    }
                }),
                test("maxSessions 3: the fourth getOrCreate gives 503 without Set-Cookie, the three keep working,"
                        + " renew works at the limit, and an expired session makes room at once", () -> {
                    var clock = new SessionSupport.TestClock();
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT.withMaxSessions(3)
                            .withIdleTimeout(Duration.ofSeconds(10)), clock);
                    try (var server = Support.serve(SessionSupport.app(sessions))) {
                        var a = issued(send(server, "POST", "/count"));
                        var b = issued(send(server, "POST", "/count"));
                        var c = issued(send(server, "POST", "/count"));
                        assertEquals(3, sessions.size());
                        var full = send(server, "POST", "/count");
                        assertEquals(503, full.status(), full.toString());
                        assertEquals(List.of("application/problem+json"), values(full, "Content-Type"));
                        assertEquals(List.of(), setCookies(full));
                        assertEquals("{\"type\":\"about:blank\",\"title\":\"Service Unavailable\",\"status\":503,"
                                + "\"detail\":\"The server cannot take more sessions right now.\",\"instance\":\"/count\"}",
                                full.body());
                        assertEquals(503, send(server, "POST", "/renew").status(), "renew without a session too");
                        assertEquals(3, sessions.size());
                        for (var id : List.of(a, b, c)) {
                            assertEquals("2", send(server, "POST", "/count", cookie(id)).body());
                        }
                        assertEquals(200, send(server, "GET", "/plain").status());
                        assertEquals("none", send(server, "GET", "/find").body());
                        var renewed = send(server, "POST", "/renew", cookie(a));
                        assertEquals("renewed:2", renewed.body());
                        var a2 = issued(renewed);
                        assertNotEquals(a, a2);
                        assertEquals(3, sessions.size(), "renew is not a new session");
                        clock.advance(Duration.ofSeconds(5));
                        assertEquals("found:2", send(server, "GET", "/find", cookie(a2)).body());
                        assertEquals("found:2", send(server, "GET", "/find", cookie(b)).body());
                        clock.advance(Duration.ofSeconds(5));
                        // c was last used 10 s ago: expired; the minute cleanup is not due (it runs a minute after start)
                        assertEquals(3, sessions.size());
                        var d = send(server, "POST", "/count");
                        assertEquals(200, d.status(), d.toString());
                        assertEquals("1", d.body());
                        issued(d);
                        assertEquals(3, sessions.size());
                        assertEquals("none", send(server, "GET", "/find", cookie(c)).body());
                        assertEquals(503, send(server, "POST", "/count").status());
                        assertEquals("found:2", send(server, "GET", "/find", cookie(a2)).body());
                    }
                }),
                test("at the limit, refused requests do not walk the store again until a session can have expired",
                        () -> {
                    var clock = new SessionSupport.TestClock();
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT.withMaxSessions(3)
                            .withIdleTimeout(Duration.ofSeconds(10)), clock);
                    try (var server = Support.serve(SessionSupport.app(sessions))) {
                        var ids = List.of(issued(send(server, "POST", "/count")), issued(send(server, "POST", "/count")),
                                issued(send(server, "POST", "/count")));
                        assertEquals(0, sessions.sweeps());
                        assertEquals(503, send(server, "POST", "/count").status());
                        assertEquals(1, sessions.sweeps(), "the first refusal cleans up");
                        for (int i = 0; i < 20; i++) {
                            assertEquals(503, send(server, "POST", "/count").status());
                        }
                        assertEquals(1, sessions.sweeps(), "nothing could have expired since: no more cleanups");
                        clock.advance(Duration.ofSeconds(9));
                        assertEquals("found:1", send(server, "GET", "/find", cookie(ids.get(0))).body());
                        assertEquals(503, send(server, "POST", "/count").status());
                        assertEquals(1, sessions.sweeps());
                        clock.advance(SECOND);
                        var created = send(server, "POST", "/count");
                        assertEquals(200, created.status(), "the two unused sessions expired: a cleanup made room");
                        issued(created);
                        assertEquals(2, sessions.sweeps());
                        assertEquals(2, sessions.size());
                    }
                }));
    }
}
