package spola.security.web;

import static spola.security.web.CsrfSupport.cookie;
import static spola.security.web.CsrfSupport.header;
import static spola.security.web.CsrfSupport.issued;
import static spola.security.web.CsrfSupport.raw;
import static spola.security.web.CsrfSupport.xsrfCookies;
import static spola.security.web.Support.basic;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import spola.http.HttpServer;
import spola.http.Response;
import spola.security.Identity;
import spola.test.Suite;
import spola.web.Router;

/**
 * Criterion 4: the {@code XSRF-TOKEN} cookie over a raw socket, where every header line is
 * visible: its exact form (with and without {@code Secure}), that it is set on the first
 * response whatever the status (200, 401, 403 from a rule, 403 from the check, 404, 405, a mapped
 * error), that a request with a well-formed cookie gets no new one and one with a malformed
 * cookie does, that a response has at most one, and {@code Cache-Control}.
 */
final class CsrfCookieTests {

    private CsrfCookieTests() {
    }

    /** The {@code Authorization} of ada (ADMIN) and bob (USER), recorded for the leak tests. */
    static String credentials(String username, String password) {
        var value = basic(username, password);
        Support.recordSent(value);
        return "Authorization: " + value;
    }

    /**
     * Basic and CSRF protection: {@code /open} for everyone, {@code /admin} for ADMIN, the rest
     * for any logged-in user.
     */
    static WebSecurity security(Csrf csrf) {
        return WebSecurity.builder()
                .authentication(HttpBasic.of(Support.authenticator(), "app"))
                .csrf(csrf)
                .rule(RequestMatcher.under("/open"), AccessRule.permitAll())
                .rule(RequestMatcher.under("/admin"), AccessRule.hasRole("ADMIN"))
                .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
                .build();
    }

    static Router router(WebSecurity security, AtomicInteger handled) {
        return Support.router()
                .filter(security.filter())
                .get("/open/hello", request -> {
                    handled.incrementAndGet();
                    return Response.text(200, "hello");
                })
                .post("/open/echo", request -> {
                    handled.incrementAndGet();
                    return Response.text(200, "posted");
                })
                .get("/open/cached", request -> {
                    handled.incrementAndGet();
                    return Response.text(200, "cached").withHeader("Cache-Control", "max-age=60");
                })
                .get("/open/fail", request -> {
                    handled.incrementAndGet();
                    throw new IllegalStateException("the handler failed on purpose");
                })
                .get("/admin/users", request -> {
                    handled.incrementAndGet();
                    return Response.text(200, "users");
                })
                .get("/me", request -> {
                    handled.incrementAndGet();
                    return Response.text(200, "me");
                })
                .build();
    }

    /** A request and the status it must get. */
    record Exchange(String name, int status, String method, String target, List<String> headers) {
        Support.Reply send(HttpServer server, String... more) throws Exception {
            var all = new ArrayList<>(headers);
            all.addAll(List.of(more));
            return raw(server, method, target, null, all.toArray(String[]::new));
        }
    }

    /** One request for each kind of response that leaves the security filter. */
    static List<Exchange> exchanges() {
        return List.of(
                new Exchange("200 from the handler", 200, "GET", "/open/hello", List.of()),
                new Exchange("401 from the rules", 401, "GET", "/me", List.of()),
                new Exchange("401 for wrong credentials", 401, "GET", "/open/hello",
                        List.of(credentials(Support.BOB, "zPwdWrong-password"))),
                new Exchange("403 from a rule", 403, "GET", "/admin/users",
                        List.of(credentials(Support.BOB, Support.BOB_PASSWORD))),
                new Exchange("403 from the CSRF check", 403, "POST", "/open/echo", List.of()),
                new Exchange("404 from the router", 404, "GET", "/open/missing", List.of()),
                new Exchange("405 from the router", 405, "GET", "/open/echo", List.of()),
                new Exchange("500 for an exception in the handler", 500, "GET", "/open/fail", List.of()));
    }

    static Suite tests() {
        return suite("the XSRF-TOKEN cookie",
                test("exactly XSRF-TOKEN=<token>; Path=/; SameSite=Lax, without HttpOnly and Secure", () -> {
                    try (var server = Support.serve(router(security(Csrf.cookie()), new AtomicInteger()))) {
                        var reply = raw(server, "GET", "/open/hello", null);
                        assertEquals(200, reply.status());
                        var token = issued(reply);
                        assertTrue(reply.head().contains("\r\nSet-Cookie: XSRF-TOKEN=" + token + "; Path=/; SameSite=Lax\r\n"),
                                "the raw Set-Cookie line");
                        assertFalse(xsrfCookies(reply).getFirst().contains("HttpOnly"));
                        assertFalse(xsrfCookies(reply).getFirst().contains("Secure"));
                        assertEquals(List.of("no-store"), reply.all("Cache-Control"));
                    }
                }),
                test("Csrf.cookie().withCookie(c -> c.secure(true)) adds ; Secure", () -> {
                    var csrf = Csrf.cookie().withCookie(c -> c.secure(true));
                    try (var server = Support.serve(router(security(csrf), new AtomicInteger()))) {
                        var reply = raw(server, "GET", "/open/hello", null);
                        var cookies = xsrfCookies(reply);
                        assertEquals(1, cookies.size());
                        var token = CsrfSupport.valueOf(cookies.getFirst());
                        assertTrue(CsrfSupport.TOKEN.matcher(token).matches());
                        assertTrue(reply.head().contains("\r\nSet-Cookie: XSRF-TOKEN=" + token
                                + "; Path=/; Secure; SameSite=Lax\r\n"), "the raw Set-Cookie line with Secure");
                    }
                }),
                test("the first response gets the cookie whatever its status: 200, 401, 403 from a rule, 403 from the"
                        + " check, 404, 405 and a mapped 500", () -> {
                    var handled = new AtomicInteger();
                    try (var server = Support.serve(router(security(Csrf.cookie()), handled))) {
                        var tokens = new HashSet<String>();
                        for (var exchange : exchanges()) {
                            var reply = exchange.send(server);
                            assertEquals(exchange.status(), reply.status(), exchange.name());
                            tokens.add(issued(reply));
                        }
                        assertEquals(exchanges().size(), tokens.size(), "a new token every time");
                        // the handler ran for 200 and 500 only
                        assertEquals(2, handled.get());
                    }
                }),
                test("a request with a well-formed cookie gets no new one, whatever the status", () -> {
                    var handled = new AtomicInteger();
                    try (var server = Support.serve(router(security(Csrf.cookie()), handled))) {
                        var token = CsrfSupport.madeUp(); // the server keeps no tokens: any well-formed one works
                        for (var exchange : exchanges()) {
                            var reply = exchange.send(server, cookie(token));
                            assertEquals(exchange.status(), reply.status(), exchange.name());
                            assertEquals(List.of(), reply.all("Set-Cookie"), exchange.name());
                        }
                        // with the header the POST passes, and still no new cookie
                        var post = raw(server, "POST", "/open/echo", null, cookie(token), header(token));
                        assertEquals("posted", post.body());
                        assertEquals(List.of(), post.all("Set-Cookie"));
                        // a well-formed cookie next to a malformed one: no new one either
                        var two = raw(server, "GET", "/open/hello", null, "Cookie: XSRF-TOKEN=\"" + token
                                + "\"; XSRF-TOKEN=" + token);
                        assertEquals(List.of(), two.all("Set-Cookie"));
                    }
                }),
                test("a request with only a malformed cookie gets a new one", () -> {
                    try (var server = Support.serve(router(security(Csrf.cookie()), new AtomicInteger()))) {
                        var token = CsrfSupport.madeUp();
                        var malformed = List.of(
                                "Cookie: XSRF-TOKEN=" + token.substring(1),
                                "Cookie: XSRF-TOKEN=" + token + "A",
                                "Cookie: XSRF-TOKEN=\"" + token + "\"",
                                "Cookie: XSRF-TOKEN=" + token.substring(1) + "+",
                                "Cookie: XSRF-TOKEN=" + token.substring(1) + "/",
                                "Cookie: XSRF-TOKEN=" + token + "=",
                                "Cookie: XSRF-TOKEN=",
                                "Cookie: xsrf-token=" + token,
                                "Cookie: XSRF-TOKEN =" + token,
                                "Cookie: SESSION=" + token);
                        for (var cookie : malformed) {
                            var reply = raw(server, "GET", "/open/hello", null, cookie);
                            assertEquals(200, reply.status(), cookie);
                            assertTrue(!issued(reply).equals(token), cookie);
                        }
                    }
                }),
                test("a response has at most one Set-Cookie for XSRF-TOKEN, also when login runs on a first response",
                        () -> {
                    var sessions = CsrfExample.sessions(false, InstantSource.system());
                    var security = WebSecurity.builder()
                            .sessions(sessions)
                            .csrf(Csrf.cookie())
                            .rule(RequestMatcher.anyRequest(), AccessRule.permitAll())
                            .build();
                    var anna = new Identity(LoginSupport.ANNA, Set.of("USER"));
                    // GET routes that log in and out: the request has no cookie and passes the check,
                    // so the first response and the new token at login are the same Set-Cookie
                    var router = Support.router()
                            .filter(security.filter())
                            .get("/login-twice", request -> {
                                WebSecurity.login(request, anna);
                                WebSecurity.login(request, anna);
                                return Response.text(200, "in");
                            })
                            .get("/login-logout", request -> {
                                WebSecurity.login(request, anna);
                                WebSecurity.logout(request);
                                return Response.text(200, "out");
                            })
                            .post("/logout", request -> {
                                WebSecurity.logout(request);
                                return Response.text(200, "out");
                            })
                            .build();
                    try (var server = Support.serve(router)) {
                        var twice = raw(server, "GET", "/login-twice", null);
                        assertEquals(200, twice.status());
                        issued(twice);
                        assertEquals(1, CsrfSupport.sessionCookies(twice).size());
                        var both = raw(server, "GET", "/login-logout", null);
                        issued(both);
                        assertEquals(List.of(LoginFlowTests.REMOVAL), CsrfSupport.sessionCookies(both));
                        // a logout with a cookie and a header: one new token
                        var token = CsrfSupport.madeUp();
                        var logout = raw(server, "POST", "/logout", null, cookie(token), header(token));
                        assertEquals(200, logout.status());
                        assertTrue(!issued(logout).equals(token));
                        assertEquals(1, sessions.size(), "the session of /login-twice, which never logged out");
                    }
                    // and no reply of these tests has more than one
                    List<Support.Reply> replies;
                    synchronized (Support.REPLIES) {
                        replies = List.copyOf(Support.REPLIES);
                    }
                    for (var reply : replies) {
                        assertTrue(xsrfCookies(reply).size() <= 1, "a " + reply.status() + " with two XSRF-TOKEN cookies");
                    }
                }),
                test("Cache-Control: no-store is added only when the response sets the cookie and has none", () -> {
                    try (var server = Support.serve(router(security(Csrf.cookie()), new AtomicInteger()))) {
                        var fresh = raw(server, "GET", "/open/hello", null);
                        issued(fresh);
                        assertEquals(List.of("no-store"), fresh.all("Cache-Control"));
                        var cached = raw(server, "GET", "/open/cached", null);
                        issued(cached);
                        assertEquals(List.of("max-age=60"), cached.all("Cache-Control"), "the handler's is kept");
                        var token = CsrfSupport.madeUp();
                        var known = raw(server, "GET", "/open/hello", null, cookie(token));
                        assertEquals(List.of(), known.all("Set-Cookie"));
                        assertEquals(List.of(), known.all("Cache-Control"), "no cookie, nothing added");
                        var knownCached = raw(server, "GET", "/open/cached", null, cookie(token));
                        assertEquals(List.of("max-age=60"), knownCached.all("Cache-Control"));
                        // the filter's own 401 and 403 have one Cache-Control, with or without the cookie
                        assertEquals(List.of("no-store"), raw(server, "GET", "/me", null).all("Cache-Control"));
                        assertEquals(List.of("no-store"), raw(server, "POST", "/open/echo", null).all("Cache-Control"));
                    }
                }));
    }
}
