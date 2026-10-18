package spola.security.web;

import static spola.security.web.CsrfSupport.cookie;
import static spola.security.web.CsrfSupport.header;
import static spola.security.web.CsrfSupport.raw;
import static spola.security.web.CsrfSupport.xsrfCookies;
import static spola.security.web.LoginSupport.ANNA;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.time.InstantSource;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import spola.http.Response;
import spola.security.Identity;
import spola.test.Suite;
import spola.web.ForwardedHeaders;
import spola.web.ResponseCookie;
import spola.web.Router;
import spola.web.RouterBuilder;
import spola.web.SessionOptions;
import spola.web.Sessions;
import spola.web.TrustedProxies;
import spola.web.WebRequest;

/**
 * 13b, criteria 5, 7 and 9, over a raw socket where every header line is visible:
 * {@code XSRF-TOKEN} gets {@code Secure} over a forwarded {@code https} on the first response
 * whatever its status; {@code ForwardedHeaders} registered after the security filter, or after
 * {@code sessions.filter()} without {@code WebSecurity}, gives no {@code Secure} (the documented
 * order); and the checks at startup are unchanged.
 */
final class SchemeCookieTests {

    private SchemeCookieTests() {
    }

    static final String HTTPS = "X-Forwarded-Proto: https";
    static final String HTTP = "X-Forwarded-Proto: http";

    static final ForwardedHeaders FORWARDED = ForwardedHeaders.trusting(TrustedProxies.privateNetworks());

    /**
     * The routes that {@link CsrfCookieTests#exchanges()} reaches, as {@link CsrfCookieTests#router}
     * has them, on {@code routes}, after the filters already on it.
     */
    static Router routes(RouterBuilder routes) {
        return routes
                .get("/open/hello", request -> Response.text(200, "hello"))
                .post("/open/echo", request -> Response.text(200, "posted"))
                .get("/open/fail", request -> {
                    throw new IllegalStateException("the handler failed on purpose");
                })
                .get("/admin/users", request -> Response.text(200, "users"))
                .get("/me", request -> Response.text(200, "me"))
                .build();
    }

    /** The exact {@code XSRF-TOKEN} line of a reply that must set it, with its token for {@code %s}. */
    static String line(Support.Reply reply) {
        var cookies = xsrfCookies(reply);
        assertEquals(1, cookies.size(), "one Set-Cookie for XSRF-TOKEN in a " + reply.status());
        var token = CsrfSupport.valueOf(cookies.getFirst());
        assertTrue(CsrfSupport.TOKEN.matcher(token).matches(), "a well-formed token");
        assertEquals(List.of("no-store"), reply.all("Cache-Control"));
        return SchemeFlowTests.template(cookies.getFirst());
    }

    /** The {@code Set-Cookie} lines of a reply, sorted, with non-empty values as {@code %s}. */
    static List<String> lines(Support.Reply reply) {
        return reply.all("Set-Cookie").stream().map(SchemeFlowTests::template).sorted().toList();
    }

    /** A login route that logs anna in and answers with the scheme the handler sees. */
    static Response login(WebRequest request) {
        WebSecurity.login(request, new Identity(ANNA, Set.of("USER")));
        return Response.text(200, request.scheme());
    }

    static Suite tests() {
        return suite("Secure on XSRF-TOKEN and the order (13b)",
                test("X-Forwarded-Proto: https gives XSRF-TOKEN with Secure on the first response: 200, 401, 401 for"
                        + " wrong credentials, 403 from a rule, 403 from the CSRF check, 404, 405 and a mapped 500",
                        () -> {
                    var security = CsrfCookieTests.security(Csrf.cookie());
                    try (var server = Support.serve(routes(Support.router().filter(FORWARDED).filter(security.filter())))) {
                        var tokens = new HashSet<String>();
                        for (var exchange : CsrfCookieTests.exchanges()) {
                            var reply = exchange.send(server, HTTPS);
                            assertEquals(exchange.status(), reply.status(), exchange.name());
                            assertEquals(SchemeFlowTests.SECURE_XSRF, line(reply), exchange.name());
                            assertTrue(reply.head().contains("\r\nSet-Cookie: XSRF-TOKEN="
                                    + CsrfSupport.valueOf(xsrfCookies(reply).getFirst()) + "; Path=/; Secure; SameSite=Lax"
                                    + "\r\n"), "the raw line: " + exchange.name());
                            tokens.add(CsrfSupport.valueOf(xsrfCookies(reply).getFirst()));
                        }
                        assertEquals(CsrfCookieTests.exchanges().size(), tokens.size(), "a new token every time");
                        // the same exchanges over a forwarded http and without the header: no Secure
                        for (var exchange : CsrfCookieTests.exchanges()) {
                            assertEquals(CsrfSupport.XSRF_COOKIE, line(exchange.send(server, HTTP)), exchange.name());
                            assertEquals(CsrfSupport.XSRF_COOKIE, line(exchange.send(server)), exchange.name());
                        }
                    }
                }),
                test("a request with a well-formed cookie still gets no new one over https", () -> {
                    var security = CsrfCookieTests.security(Csrf.cookie());
                    try (var server = Support.serve(routes(Support.router().filter(FORWARDED).filter(security.filter())))) {
                        var token = CsrfSupport.madeUp();
                        for (var exchange : CsrfCookieTests.exchanges()) {
                            var reply = exchange.send(server, HTTPS, cookie(token));
                            assertEquals(exchange.status(), reply.status(), exchange.name());
                            assertEquals(List.of(), reply.all("Set-Cookie"), exchange.name());
                        }
                    }
                }),
                test("Csrf.cookie().withCookie(c -> c.secure(true)) gives one Secure over https and over http", () -> {
                    var security = CsrfCookieTests.security(Csrf.cookie().withCookie(c -> c.secure(true)));
                    try (var server = Support.serve(routes(Support.router().filter(FORWARDED).filter(security.filter())))) {
                        for (var proto : List.of(HTTPS, HTTP)) {
                            assertEquals(SchemeFlowTests.SECURE_XSRF, line(raw(server, "GET", "/open/hello", null, proto)));
                        }
                        assertEquals(SchemeFlowTests.SECURE_XSRF, line(raw(server, "GET", "/open/hello", null)));
                    }
                }),
                test("ForwardedHeaders registered after security.filter() comes too late: the handler sees https, but"
                        + " neither XSRF-TOKEN nor the session cookie gets Secure", () -> {
                    Sessions sessions = CsrfExample.sessions(false, InstantSource.system());
                    var security = CsrfExample.security(sessions);
                    var router = Support.router()
                            .filter(security.filter())
                            .filter(FORWARDED) // after the security filter: too late
                            .get("/actuator/health", request -> Response.text(200, request.scheme()))
                            .post("/api/auth/login", SchemeCookieTests::login)
                            .post("/api/auth/logout", request -> {
                                WebSecurity.logout(request);
                                return Response.text(200, request.scheme());
                            })
                            .build();
                    try (var server = Support.serve(router)) {
                        var health = raw(server, "GET", "/actuator/health", null, HTTPS);
                        assertEquals("https", health.body(), "ForwardedHeaders ran, after the security filter");
                        assertEquals(CsrfSupport.XSRF_COOKIE, line(health));
                        var token = CsrfSupport.valueOf(xsrfCookies(health).getFirst());

                        var login = raw(server, "POST", "/api/auth/login", null, HTTPS, cookie(token), header(token));
                        assertEquals("https", login.body());
                        assertEquals(List.of(LoginFlowTests.SESSION_COOKIE, CsrfSupport.XSRF_COOKIE), lines(login));
                        var id = CsrfSupport.valueOf(CsrfSupport.sessionCookies(login).getFirst());
                        var second = CsrfSupport.valueOf(xsrfCookies(login).getFirst());

                        var logout = raw(server, "POST", "/api/auth/logout", null, HTTPS,
                                "Cookie: SESSION=" + id + "; XSRF-TOKEN=" + second, header(second));
                        assertEquals("https", logout.body());
                        assertEquals(List.of(LoginFlowTests.REMOVAL, CsrfSupport.XSRF_COOKIE), lines(logout));
                    }
                }),
                test("ForwardedHeaders registered after sessions.filter(), without WebSecurity: the handler sees https,"
                        + " the session cookie gets no Secure", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    var router = Support.router()
                            .filter(sessions.filter())
                            .filter(FORWARDED) // after the session filter: too late
                            .post("/cart", request -> {
                                Sessions.getOrCreate(request);
                                return Response.text(200, request.scheme());
                            })
                            .post("/empty", request -> {
                                Sessions.invalidate(request);
                                return Response.text(200, request.scheme());
                            })
                            .build();
                    try (var server = Support.serve(router)) {
                        var created = raw(server, "POST", "/cart", null, HTTPS);
                        assertEquals("https", created.body());
                        assertEquals(List.of(LoginFlowTests.SESSION_COOKIE), lines(created));
                        var id = CsrfSupport.valueOf(CsrfSupport.sessionCookies(created).getFirst());
                        var removed = raw(server, "POST", "/empty", null, HTTPS, "Cookie: SESSION=" + id);
                        assertEquals("https", removed.body());
                        assertEquals(List.of(LoginFlowTests.REMOVAL), lines(removed));
                    }
                    // the documented order, for contrast: ForwardedHeaders first gives Secure
                    var ordered = Sessions.inMemory(SessionOptions.DEFAULT);
                    var first = Support.router()
                            .filter(FORWARDED)
                            .filter(ordered.filter())
                            .post("/cart", request -> {
                                Sessions.getOrCreate(request);
                                return Response.text(200, request.scheme());
                            })
                            .build();
                    try (var server = Support.serve(first)) {
                        var created = raw(server, "POST", "/cart", null, HTTPS);
                        assertEquals("https", created.body());
                        assertEquals(List.of(SchemeFlowTests.SECURE_SESSION), lines(created));
                    }
                }),
                test("a login with ForwardedHeaders first: SESSION and XSRF-TOKEN in one response, both with Secure"
                        + " over https and both without over http", () -> {
                    Sessions sessions = CsrfExample.sessions(false, InstantSource.system());
                    var router = Support.router()
                            .filter(FORWARDED)
                            .filter(CsrfExample.security(sessions).filter())
                            .post("/api/auth/login", SchemeCookieTests::login)
                            .build();
                    try (var server = Support.serve(router)) {
                        var token = CsrfSupport.madeUp();
                        var login = raw(server, "POST", "/api/auth/login", null, HTTPS, cookie(token), header(token));
                        assertEquals(200, login.status(), login.body());
                        assertEquals("https", login.body());
                        assertEquals(List.of(SchemeFlowTests.SECURE_SESSION, SchemeFlowTests.SECURE_XSRF), lines(login));
                        assertEquals(List.of("no-store"), login.all("Cache-Control"));
                        var plain = raw(server, "POST", "/api/auth/login", null, HTTP, cookie(token), header(token));
                        assertEquals("http", plain.body());
                        assertEquals(List.of(LoginFlowTests.SESSION_COOKIE, CsrfSupport.XSRF_COOKIE), lines(plain));
                    }
                }),
                test("the checks at startup are unchanged: SameSite=None without secure(true) is an"
                        + " IllegalArgumentException for Sessions and for Csrf", () -> {
                    var none = ResponseCookie.SameSite.NONE;
                    var sessions = CsrfSupport.thrown(assertThrows(IllegalArgumentException.class,
                            () -> Sessions.inMemory(SessionOptions.DEFAULT.withCookie(c -> c.sameSite(none)))));
                    assertTrue(sessions.getMessage().contains("SameSite=None needs Secure"), sessions.getMessage());
                    var csrf = CsrfSupport.thrown(assertThrows(IllegalArgumentException.class,
                            () -> WebSecurity.builder().csrf(Csrf.cookie().withCookie(c -> c.sameSite(none))).build()));
                    assertTrue(csrf.getMessage().contains("SameSite=None needs Secure"), csrf.getMessage());
                    // the __Host- and __Secure- prefixes still need secure(true) as well
                    CsrfSupport.thrown(assertThrows(IllegalArgumentException.class,
                            () -> Sessions.inMemory(SessionOptions.DEFAULT.withCookieName("__Host-SESSION"))));
                    CsrfSupport.thrown(assertThrows(IllegalArgumentException.class,
                            () -> Sessions.inMemory(SessionOptions.DEFAULT.withCookieName("__Secure-SESSION"))));
                    // with secure(true) both build, and the cookie is Secure over http
                    var secureNone = Sessions.inMemory(SessionOptions.DEFAULT.withCookie(c -> c.secure(true).sameSite(none)));
                    var security = WebSecurity.builder()
                            .sessions(secureNone)
                            .csrf(Csrf.cookie().withCookie(c -> c.secure(true).sameSite(none)))
                            .rule(RequestMatcher.anyRequest(), AccessRule.permitAll())
                            .build();
                    var router = Support.router()
                            .filter(FORWARDED)
                            .filter(security.filter())
                            .post("/api/auth/login", SchemeCookieTests::login)
                            .build();
                    try (var server = Support.serve(router)) {
                        var token = CsrfSupport.madeUp();
                        var login = raw(server, "POST", "/api/auth/login", null, HTTP, cookie(token), header(token));
                        assertEquals(List.of("SESSION=%s; Path=/; Secure; HttpOnly; SameSite=None",
                                "XSRF-TOKEN=%s; Path=/; Secure; SameSite=None"), lines(login));
                    }
                }));
    }
}
