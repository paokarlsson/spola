package spola.security.web;

import static spola.security.web.LoginSupport.ANNA;
import static spola.security.web.LoginSupport.ANNA_PASSWORD;
import static spola.security.web.UserSessionSupport.raw;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.time.InstantSource;
import java.util.List;
import java.util.Set;
import spola.http.HttpServer;
import spola.http.Response;
import spola.security.Identity;
import spola.test.Suite;
import spola.web.Router;
import spola.web.Sessions;
import spola.web.WebRequest;

/**
 * Uppdrag 15a (criteria 6 and 7): a response that sets the session cookie or
 * {@code XSRF-TOKEN} never stays storable by a shared cache, and a response to a logged-in
 * request without {@code Cache-Control} gets {@code no-store}, while a {@code Cache-Control} that
 * the handler set stays unless it has {@code public} or {@code s-maxage}. An anonymous response
 * without a cookie is left alone.
 */
final class CacheHeaderTests {

    private CacheHeaderTests() {
    }

    static final List<String> NO_STORE = List.of("no-store");
    static final Identity ANNA_IDENTITY = new Identity(ANNA, Set.of("USER"));

    /**
     * The handler's {@code Cache-Control} fields, and what a response must have that sets a
     * cookie, and one to a logged-in request that sets none.
     */
    record Case(String name, List<String> handler, List<String> withCookie, List<String> loggedIn) {
        @Override
        public String toString() {
            return name;
        }
    }

    static final List<Case> CASES = List.of(
            new Case("none", List.of(), NO_STORE, NO_STORE),
            new Case("public, max-age=60", List.of("public, max-age=60"), NO_STORE, NO_STORE),
            new Case("s-maxage=60", List.of("s-maxage=60"), NO_STORE, NO_STORE),
            new Case("private, max-age=60", List.of("private, max-age=60"), List.of("private, max-age=60"),
                    List.of("private, max-age=60")),
            new Case("private, no-cache", List.of("private, no-cache"), List.of("private, no-cache"),
                    List.of("private, no-cache")),
            new Case("no-store", List.of("no-store"), NO_STORE, NO_STORE),
            new Case("max-age=60", List.of("max-age=60"), NO_STORE, List.of("max-age=60")),
            new Case("no-cache", List.of("no-cache"), NO_STORE, List.of("no-cache")),
            new Case("Public, Max-Age=60 in mixed case", List.of("Public, Max-Age=60"), NO_STORE, NO_STORE),
            new Case("private, s-maxage=60", List.of("private, s-maxage=60"), NO_STORE, NO_STORE),
            new Case("private and public in two fields", List.of("private", "public"), NO_STORE, NO_STORE),
            new Case("private=\"Set-Cookie\"", List.of("private=\"Set-Cookie\""), NO_STORE,
                    List.of("private=\"Set-Cookie\"")),
            new Case("a quoted public", List.of("no-cache=\"public\""), NO_STORE, List.of("no-cache=\"public\"")),
            new Case("a semicolon instead of a comma", List.of("private; max-age=60"), NO_STORE, NO_STORE),
            new Case("an empty value", List.of(""), NO_STORE, NO_STORE));

    /**
     * {@code POST /login-cached/{n}} logs anna in and answers with the case's fields;
     * {@code /open/{n}} (open) and {@code /private/{n}} (logged in) answer with them;
     * {@code /private/fail} throws.
     */
    static Router router(WebSecurity security) {
        return Support.router()
                .filter(security.filter())
                .post("/login-cached/{n}", request -> {
                    WebSecurity.login(request, ANNA_IDENTITY);
                    return answer(request);
                })
                .get("/open/{n}", CacheHeaderTests::answer)
                .get("/private/fail", request -> {
                    throw new IllegalStateException("the handler failed on purpose");
                })
                .get("/private/{n}", CacheHeaderTests::answer)
                .build();
    }

    static Response answer(WebRequest request) {
        var response = Response.text(200, "cached").withHeader("X-Other", "kept");
        for (var value : CASES.get(Integer.parseInt(request.pathParam("n"))).handler()) {
            response = response.withHeader("Cache-Control", value);
        }
        return response;
    }

    /** Sessions and HttpBasic, no CSRF protection: a login sets only the session cookie. */
    static WebSecurity sessionSecurity(Sessions sessions) {
        return WebSecurity.builder()
                .sessions(sessions)
                .authentication(HttpBasic.of(LoginSupport.authenticator(), "app"))
                .rule(RequestMatcher.under("/login-cached"), AccessRule.permitAll())
                .rule(RequestMatcher.under("/open"), AccessRule.permitAll())
                .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
                .build();
    }

    /** CSRF protection and HttpBasic, no sessions: the cookie is {@code XSRF-TOKEN}. */
    static WebSecurity csrfSecurity() {
        return WebSecurity.builder()
                .csrf(Csrf.cookie())
                .authentication(HttpBasic.of(LoginSupport.authenticator(), "app"))
                .rule(RequestMatcher.under("/open"), AccessRule.permitAll())
                .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
                .build();
    }

    static String basic() {
        var value = Support.basic(ANNA, ANNA_PASSWORD);
        Support.recordSent(value);
        return "Authorization: " + value;
    }

    static Support.Reply call(HttpServer server, String method, String path, String... headers) throws Exception {
        var reply = raw(server, method, path, null, headers);
        assertEquals(200, reply.status(), reply.toString());
        assertEquals(List.of("kept"), reply.all("X-Other"), "other fields stay");
        return reply;
    }

    static String path(String prefix, Case c) {
        return prefix + CASES.indexOf(c);
    }

    static Suite tests() {
        return suite("Cache-Control",
                parameterized("a response that creates the session (criterion 6)", CASES, c -> {
                    var sessions = LoginExample.sessions(false, InstantSource.system());
                    try (var server = Support.serve(router(sessionSecurity(sessions)))) {
                        var login = call(server, "POST", path("/login-cached/", c));
                        assertEquals(1, LoginSupport.sessionCookies(login).size());
                        assertEquals(c.withCookie(), login.all("Cache-Control"));
                    }
                }),
                parameterized("a response that sets XSRF-TOKEN (criterion 6)", CASES, c -> {
                    try (var server = Support.serve(router(csrfSecurity()))) {
                        var first = call(server, "GET", path("/open/", c));
                        assertEquals(1, CsrfSupport.xsrfCookies(first).size());
                        assertEquals(c.withCookie(), first.all("Cache-Control"), "anonymous");
                        var loggedIn = call(server, "GET", path("/private/", c), basic());
                        assertEquals(1, CsrfSupport.xsrfCookies(loggedIn).size());
                        assertEquals(c.withCookie(), loggedIn.all("Cache-Control"), "logged in: the cookie's rule");
                    }
                }),
                parameterized("a logged-in GET whose response sets no cookie (criterion 7)", CASES, c -> {
                    var sessions = LoginExample.sessions(false, InstantSource.system());
                    try (var server = Support.serve(router(sessionSecurity(sessions)))) {
                        var id = UserSessionSupport.issued(raw(server, "POST", path("/login-cached/", CASES.getFirst()), null));
                        var session = call(server, "GET", path("/private/", c), LoginSupport.cookie(id));
                        assertEquals(List.of(), session.all("Set-Cookie"));
                        assertEquals(c.loggedIn(), session.all("Cache-Control"), "logged in with the session");
                        var mechanism = call(server, "GET", path("/private/", c), basic());
                        assertEquals(List.of(), mechanism.all("Set-Cookie"));
                        assertEquals(c.loggedIn(), mechanism.all("Cache-Control"), "logged in with HttpBasic");
                    }
                    try (var server = Support.serve(router(csrfSecurity()))) {
                        var token = CsrfSupport.madeUp();
                        var mechanism = call(server, "GET", path("/private/", c), basic(), CsrfSupport.cookie(token));
                        assertEquals(List.of(), mechanism.all("Set-Cookie"));
                        assertEquals(c.loggedIn(), mechanism.all("Cache-Control"), "with CSRF protection");
                    }
                }),
                parameterized("an anonymous response on a permitAll path without a cookie is left alone (criterion 7)",
                        CASES, c -> {
                    var sessions = LoginExample.sessions(false, InstantSource.system());
                    try (var server = Support.serve(router(sessionSecurity(sessions)))) {
                        var open = call(server, "GET", path("/open/", c));
                        assertEquals(List.of(), open.all("Set-Cookie"));
                        assertEquals(c.handler(), open.all("Cache-Control"));
                    }
                    try (var server = Support.serve(router(csrfSecurity()))) {
                        var open = call(server, "GET", path("/open/", c), CsrfSupport.cookie(CsrfSupport.madeUp()));
                        assertEquals(List.of(), open.all("Set-Cookie"));
                        assertEquals(c.handler(), open.all("Cache-Control"), "with a well-formed XSRF-TOKEN cookie");
                    }
                }),
                test("a logged-in request's 404 and mapped 500 get no-store too; an anonymous error is left alone",
                        () -> {
                    var sessions = LoginExample.sessions(false, InstantSource.system());
                    try (var server = Support.serve(router(sessionSecurity(sessions)))) {
                        var id = UserSessionSupport.issued(raw(server, "POST", path("/login-cached/", CASES.getFirst()), null));
                        var missing = raw(server, "GET", "/nowhere", null, LoginSupport.cookie(id));
                        assertEquals(404, missing.status());
                        assertEquals(NO_STORE, missing.all("Cache-Control"));
                        var failed = raw(server, "GET", "/private/fail", null, LoginSupport.cookie(id));
                        assertEquals(500, failed.status());
                        assertEquals(NO_STORE, failed.all("Cache-Control"));
                        var anonymousMissing = raw(server, "GET", "/nowhere", null);
                        assertEquals(401, anonymousMissing.status(), "the rules deny it before the router");
                        assertEquals(NO_STORE, anonymousMissing.all("Cache-Control"));
                    }
                    try (var server = Support.serve(router(csrfSecurity()))) {
                        var open = raw(server, "GET", "/nowhere", null, CsrfSupport.cookie(CsrfSupport.madeUp()));
                        assertEquals(401, open.status());
                        var anonymous = raw(server, "GET", "/open/missing", null, CsrfSupport.cookie(CsrfSupport.madeUp()));
                        assertEquals(500, anonymous.status(), "not a number: the handler fails");
                        assertTrue(anonymous.all("Cache-Control").isEmpty(), "an anonymous error is left alone");
                    }
                }));
    }
}
