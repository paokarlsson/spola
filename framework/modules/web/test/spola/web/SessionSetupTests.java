package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertSame;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.web.SessionSupport.cookie;
import static spola.web.SessionSupport.issued;
import static spola.web.SessionSupport.send;
import static spola.web.SessionSupport.setCookies;
import static spola.web.SessionSupport.text;
import static spola.web.SessionSupport.thrown;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import spola.http.Response;
import spola.test.Body;
import spola.test.Suite;

/** Criteria 10 and 11: checking SessionOptions and Sessions.inMemory, and a missing or doubled filter. */
final class SessionSetupTests {

    private SessionSetupTests() {
    }

    /**
     * Settings that must be refused.
     *
     * @param name     what the case shows
     * @param expected the exception
     * @param body     the code that must throw it
     */
    record Refused(String name, Class<? extends Throwable> expected, Body body) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Refused iae(String name, Body body) {
        return new Refused(name, IllegalArgumentException.class, body);
    }

    static Refused npe(String name, Body body) {
        return new Refused(name, NullPointerException.class, body);
    }

    static final SessionOptions D = SessionOptions.DEFAULT;

    static final List<Refused> REFUSED = List.of(
            iae("idleTimeout 0", () -> D.withIdleTimeout(Duration.ZERO)),
            iae("idleTimeout negative", () -> D.withIdleTimeout(Duration.ofMinutes(-30))),
            iae("idleTimeout under 1 second", () -> D.withIdleTimeout(Duration.ofMillis(999))),
            iae("idleTimeout a nanosecond under 1 second", () -> D.withIdleTimeout(Duration.ofSeconds(1).minusNanos(1))),
            iae("idleTimeout over 365 days", () -> D.withIdleTimeout(Duration.ofDays(365).plusNanos(1))),
            iae("idleTimeout 1 000 years", () -> D.withIdleTimeout(Duration.ofDays(365_000))),
            iae("maxSessions 0", () -> D.withMaxSessions(0)),
            iae("maxSessions -1", () -> D.withMaxSessions(-1)),
            iae("maxSessions 10 000 001", () -> D.withMaxSessions(10_000_001)),
            iae("maxSessions Integer.MIN_VALUE", () -> D.withMaxSessions(Integer.MIN_VALUE)),
            iae("cookieName empty", () -> D.withCookieName("")),
            iae("cookieName \"a b\"", () -> D.withCookieName("a b")),
            iae("cookieName \"a=b\"", () -> D.withCookieName("a=b")),
            iae("cookieName with ;", () -> D.withCookieName("a;b")),
            iae("cookieName with a control character", () -> D.withCookieName("a\u001bb")),
            iae("cookieName non-ASCII", () -> D.withCookieName("séssion")),
            iae("a cookie function that returns null", () -> Sessions.inMemory(D.withCookie(cookie -> null))),
            iae("SameSite=None without Secure", () -> Sessions.inMemory(D.withCookie(
                    cookie -> cookie.sameSite(ResponseCookie.SameSite.NONE)))),
            iae("Domain on a __Host- name", () -> Sessions.inMemory(D.withCookieName("__Host-SESSION")
                    .withCookie(cookie -> cookie.secure(true).domain("example.com")))),
            iae("a __Host- name without Secure", () -> Sessions.inMemory(D.withCookieName("__Host-SESSION"))),
            iae("a __Secure- name without Secure", () -> Sessions.inMemory(D.withCookieName("__Secure-SESSION"))),
            iae("an invalid path", () -> Sessions.inMemory(D.withCookie(cookie -> cookie.path("app")))),
            iae("a cookie function that returns another cookie's builder",
                    () -> Sessions.inMemory(D.withCookie(cookie -> ResponseCookie.of("other", "x")))),
            iae("a cookie function that sets Max-Age=0", () -> Sessions.inMemory(D.withCookie(
                    cookie -> cookie.maxAge(Duration.ZERO)))),
            npe("withIdleTimeout(null)", () -> D.withIdleTimeout(null)),
            npe("withCookieName(null)", () -> D.withCookieName(null)),
            npe("withCookie(null)", () -> D.withCookie(null)),
            npe("the canonical constructor with null", () -> new SessionOptions(null, 1, "s", UnaryOperator.identity())),
            npe("inMemory(null)", () -> Sessions.inMemory(null)),
            npe("inMemory(options, null)", () -> Sessions.inMemory(D, null)),
            npe("Sessions.find(null)", () -> Sessions.find(null)),
            npe("Sessions.getOrCreate(null)", () -> Sessions.getOrCreate(null)),
            npe("Sessions.renew(null)", () -> Sessions.renew(null)),
            npe("Sessions.invalidate(null)", () -> Sessions.invalidate(null)));

    /** Fails unless {@code text} is printable ASCII (newlines between lines allowed). */
    static void assertPrintable(String text) {
        for (char c : text.toCharArray()) {
            assertTrue(c == '\n' || (c >= 0x20 && c <= 0x7E), "not printable: U+" + Integer.toHexString(c) + " in " + text);
        }
    }

    static Suite tests() {
        return suite("session setup",
                parameterized("refused settings (" + REFUSED.size() + " cases)", REFUSED, refused -> {
                    var e = thrown(assertThrows(refused.expected(), refused.body()));
                    assertSame(refused.expected(), e.getClass());
                    if (e.getMessage() != null) {
                        assertPrintable(e.getMessage());
                        assertTrue(e.getMessage().length() < 400, e.getMessage());
                    }
                }),
                test("the limits themselves are accepted", () -> {
                    var options = D.withIdleTimeout(Duration.ofSeconds(1)).withMaxSessions(1);
                    assertEquals(Duration.ofSeconds(1), options.idleTimeout());
                    assertEquals(Duration.ofDays(365), D.withIdleTimeout(Duration.ofDays(365)).idleTimeout());
                    assertEquals(10_000_000, D.withMaxSessions(10_000_000).maxSessions());
                    assertEquals("__Host-SESSION", D.withCookieName("__Host-SESSION").cookieName());
                    Sessions.inMemory(D.withCookieName("__Host-SESSION").withCookie(cookie -> cookie.secure(true)));
                    Sessions.inMemory(D.withCookie(cookie -> cookie.secure(true).sameSite(ResponseCookie.SameSite.NONE)));
                    Sessions.inMemory(D.withCookie(cookie -> cookie.maxAge(Duration.ofDays(1)).httpOnly(false)));
                }),
                test("the defaults: 30 minutes, 10 000 sessions, SESSION, the builder unchanged", () -> {
                    assertEquals(Duration.ofMinutes(30), D.idleTimeout());
                    assertEquals(10_000, D.maxSessions());
                    assertEquals("SESSION", D.cookieName());
                    var builder = ResponseCookie.of("SESSION", "x");
                    assertSame(builder, D.cookie().apply(builder));
                    assertEquals("Sessions[0 sessions, max 10000, idle PT30M, cookie SESSION]",
                            text(Sessions.inMemory(D)));
                    assertTrue(text(D).startsWith("SessionOptions[idleTimeout=PT30M, maxSessions=10000, cookieName=SESSION,"),
                            text(D));
                }),
                test("the canonical constructor with three wrong values gives one exception with three lines", () -> {
                    var e = thrown(assertThrows(IllegalArgumentException.class,
                            () -> new SessionOptions(Duration.ZERO, 0, "a b", UnaryOperator.identity())));
                    var lines = e.getMessage().split("\n", -1);
                    assertEquals(3, lines.length, e.getMessage());
                    assertEquals("idleTimeout must be at least 1 second and at most 365 days, was PT0S", lines[0]);
                    assertEquals("maxSessions must be between 1 and 10000000, was 0", lines[1]);
                    assertEquals("cookieName must be a token (RFC 9110 5.6.2), was \"a b\"", lines[2]);
                    var control = thrown(assertThrows(IllegalArgumentException.class, () -> D.withCookieName("a\nb\u001b")));
                    assertEquals("cookieName must be a token (RFC 9110 5.6.2), was \"a\\x0ab\\x1b\"", control.getMessage());
                }),
                test("an exception from the cookie function passes unchanged; the function runs once", () -> {
                    var failure = new UnsupportedOperationException("from the function");
                    var e = assertThrows(UnsupportedOperationException.class, () -> Sessions.inMemory(D.withCookie(cookie -> {
                        throw failure;
                    })));
                    assertSame(failure, e);
                    var calls = new int[1];
                    var sessions = Sessions.inMemory(D.withCookie(cookie -> {
                        calls[0]++;
                        return cookie.secure(true);
                    }));
                    assertEquals(1, calls[0]);
                    try (var server = Support.serve(SessionSupport.app(sessions))) {
                        for (int i = 0; i < 3; i++) {
                            assertTrue(setCookies(send(server, "POST", "/count")).getFirst().contains("; Secure;"));
                        }
                    }
                    assertEquals(1, calls[0], "applied once, in inMemory");
                }),
                test("find, getOrCreate, renew and invalidate without sessions.filter() give 500 with"
                        + " IllegalStateException", () -> {
                    int before = SessionSupport.LOG.records().size();
                    try (var server = Support.serve(SessionSupport.routes(SessionSupport.router()).build())) {
                        for (var call : List.of(new String[] {"GET", "/find"}, new String[] {"POST", "/count"},
                                new String[] {"POST", "/renew"}, new String[] {"POST", "/invalidate"})) {
                            var reply = send(server, call[0], call[1], cookie(SessionCookieTests.unknownId()));
                            assertEquals(500, reply.status(), call[1] + " " + reply);
                            assertEquals(List.of(), setCookies(reply));
                        }
                        assertEquals(200, send(server, "GET", "/plain").status());
                    }
                    var lines = SessionSupport.LOG.records().subList(before, SessionSupport.LOG.records().size());
                    assertEquals(4, lines.size(), String.valueOf(lines));
                    for (var line : lines) {
                        assertTrue(line.contains("java.lang.IllegalStateException: the request has no session state:"
                                + " register sessions.filter() on the router"), line);
                    }
                }),
                test("sessions.filter() registered twice, two Sessions on one router, or once more on a group:"
                        + " 500", () -> {
                    var sessions = Sessions.inMemory(D);
                    var other = Sessions.inMemory(D);
                    assertSame(sessions.filter(), sessions.filter());
                    var routers = List.of(
                            SessionSupport.routes(SessionSupport.router().filter(sessions.filter()).filter(sessions.filter()))
                                    .build(),
                            SessionSupport.routes(SessionSupport.router().filter(sessions.filter()).filter(other.filter()))
                                    .build(),
                            SessionSupport.router().filter(sessions.filter())
                                    .group("/g", g -> g.filter(sessions.filter()).post("/count", r -> {
                                        Sessions.getOrCreate(r);
                                        return Response.text(200, "never");
                                    }))
                                    .get("/plain", r -> Response.text(200, "plain"))
                                    .build());
                    int before = SessionSupport.LOG.records().size();
                    for (var router : routers) {
                        try (var server = Support.serve(router)) {
                            var reply = send(server, "POST", router == routers.get(2) ? "/g/count" : "/count");
                            assertEquals(500, reply.status(), reply.toString());
                            assertEquals(List.of(), setCookies(reply));
                        }
                    }
                    assertEquals(0, sessions.size() + other.size(), "no handler ran");
                    var lines = SessionSupport.LOG.records().subList(before, SessionSupport.LOG.records().size());
                    assertEquals(3, lines.size(), String.valueOf(lines));
                    for (var line : lines) {
                        assertTrue(line.contains("java.lang.IllegalStateException: the session filter ran twice for one"
                                + " request"), line);
                    }
                    try (var server = Support.serve(routers.get(2))) {
                        assertEquals(200, send(server, "GET", "/plain").status(), "once on the router alone works");
                    }
                }),
                test("a filter before the session filter has no session state; a filter after it has", () -> {
                    var sessions = Sessions.inMemory(D);
                    var router = SessionSupport.router()
                            .filter((request, next) -> {
                                var e = thrown(assertThrows(IllegalStateException.class, () -> Sessions.find(request)));
                                return next.proceed(request).withHeader("X-Outer", e.getClass().getSimpleName());
                            })
                            .filter(sessions.filter())
                            .filter((request, next) -> next.proceed(request).withHeader("X-Inner",
                                    String.valueOf(Sessions.find(request).isPresent())))
                            .post("/count", r -> {
                                Sessions.getOrCreate(r);
                                return Response.text(200, "ok");
                            })
                            .build();
                    try (var server = Support.serve(router)) {
                        var reply = send(server, "POST", "/count");
                        assertEquals("IllegalStateException", reply.header("X-Outer"));
                        assertEquals("true", reply.header("X-Inner"));
                        var again = send(server, "POST", "/count", cookie(issued(reply)));
                        assertEquals(List.of(), setCookies(again));
                    }
                }),
                test("a session made after the response was built is refused; an exception that skips the filter"
                        + " ends the session the request created", () -> {
                    var sessions = Sessions.inMemory(D);
                    var late = new AtomicReference<WebRequest>();
                    var router = SessionSupport.router().filter(sessions.filter())
                            .get("/late", r -> {
                                late.set(r);
                                return Response.text(200, "late");
                            })
                            .post("/body", r -> {
                                Sessions.getOrCreate(r);
                                r.bytes(); // malformed chunked framing: a RequestBodyException passes the filter
                                return Response.text(200, "never");
                            })
                            .build();
                    try (var server = Support.serve(router)) {
                        send(server, "GET", "/late");
                        thrown(assertThrows(IllegalStateException.class, () -> Sessions.getOrCreate(late.get())));
                        thrown(assertThrows(IllegalStateException.class, () -> Sessions.renew(late.get())));
                        assertFalse(Sessions.find(late.get()).isPresent());
                        Sessions.invalidate(late.get());
                        assertEquals(0, sessions.size());
                        var broken = Support.raw(server.port(), ("POST /body HTTP/1.1\r\nHost: localhost\r\n"
                                + "Transfer-Encoding: chunked\r\n\r\nZZ\r\n").getBytes(StandardCharsets.US_ASCII), false);
                        SessionSupport.record(broken);
                        assertEquals(400, broken.status(), broken.toString());
                        assertEquals(List.of(), setCookies(broken));
                        assertEquals(0, sessions.size(), "the session nobody can reach was removed");
                    }
                }));
    }
}
