package spola.security.web;

import static spola.security.web.CsrfSupport.TOKEN;
import static spola.security.web.CsrfSupport.XSRF_COOKIE;
import static spola.security.web.CsrfSupport.sessionCookies;
import static spola.security.web.CsrfSupport.valueOf;
import static spola.security.web.CsrfSupport.xsrfCookies;
import static spola.security.web.LoginSupport.ANNA;
import static spola.security.web.LoginSupport.ANNA_PASSWORD;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertNotEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.http.HttpResponse;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import spola.test.Suite;

/**
 * Criterion 3: the need's whole flow ({@link CsrfExample}) through a real server, {@code HttpClient}
 * and a {@code CookieManager}, with {@code X-XSRF-TOKEN} taken from the cookie in the
 * {@code CookieManager} as Angular does, and a counting {@code UserStore}: the health check gives
 * {@code XSRF-TOKEN}, a login without the header gets 403, a login with it 200 with new cookies,
 * {@code /api/auth/me} the name, a logout with the old token 403 and with the current one 200,
 * and {@code /api/auth/me} afterwards 401.
 */
final class CsrfFlowTests {

    private CsrfFlowTests() {
    }

    /** The token of the one {@code XSRF-TOKEN} cookie a response must set, exactly as {@code Csrf.cookie()} sets it. */
    static String newToken(HttpResponse<String> response) {
        var cookies = xsrfCookies(response);
        assertEquals(1, cookies.size(), "exactly one Set-Cookie for XSRF-TOKEN");
        var token = valueOf(cookies.getFirst());
        assertTrue(TOKEN.matcher(token).matches(), "a well-formed token");
        assertEquals(XSRF_COOKIE.formatted(token), cookies.getFirst());
        return token;
    }

    static Suite tests() {
        return suite("the use case with CSRF protection",
                test("health gives XSRF-TOKEN, a login without the header 403, with it 200 and new cookies, me,"
                        + " a logout with the old token 403 and with the current one 200, then me 401", () -> {
                    var sessions = CsrfExample.sessions(false, InstantSource.system());
                    var counting = new Support.Counting(LoginSupport.USERS);
                    var security = CsrfExample.security(sessions);
                    var router = CsrfExample.router(Support.router(), security, counting.authenticator);
                    try (var server = Support.serve(router); var browser = new CsrfSupport.Browser(server)) {
                        // GET /actuator/health: 200 and an XSRF-TOKEN cookie, no session
                        var health = browser.get("/actuator/health");
                        assertEquals(200, health.statusCode());
                        assertEquals("{\"status\":\"UP\"}", health.body());
                        assertEquals(1, health.headers().allValues("Set-Cookie").size(), "only XSRF-TOKEN");
                        var first = newToken(health);
                        assertEquals(List.of(), sessionCookies(health));
                        assertEquals(List.of("no-store"), health.headers().allValues("Cache-Control"));
                        assertEquals(0, sessions.size());
                        assertEquals(Optional.of(first), browser.xsrf(), "the CookieManager has the token");
                        assertEquals(Optional.empty(), browser.session());

                        // a login without the header: 403; the handler and the Authenticator never run
                        // (the handler reads the body and asks the Authenticator, whose UserStore counts)
                        var forged = browser.postWithoutHeader("/api/auth/login",
                                LoginSupport.loginJson(ANNA, ANNA_PASSWORD));
                        assertEquals(403, forged.statusCode());
                        assertEquals(BasicTests.PROBLEM_403, forged.body());
                        assertEquals(Optional.of("application/problem+json"), forged.headers().firstValue("Content-Type"));
                        assertEquals(List.of("no-store"), forged.headers().allValues("Cache-Control"));
                        assertEquals(List.of(), forged.headers().allValues("Set-Cookie"), "no session, the token stays");
                        assertEquals(0, counting.finds.get(), "the UserStore was never asked");
                        assertEquals(0, counting.calls(), "no password was checked");
                        assertEquals(0, sessions.size(), "no session was created");

                        // a login with the header from the cookie: 200, a new session and a new token
                        var login = browser.login(ANNA, ANNA_PASSWORD);
                        assertEquals(200, login.statusCode());
                        assertEquals("{\"success\":true,\"username\":\"anna\"}", login.body());
                        assertEquals(1, counting.finds.get());
                        assertEquals(1, counting.calls());
                        assertEquals(2, login.headers().allValues("Set-Cookie").size(), "SESSION and XSRF-TOKEN");
                        var sessionCookie = sessionCookies(login);
                        assertEquals(1, sessionCookie.size());
                        var id = valueOf(sessionCookie.getFirst());
                        assertTrue(TOKEN.matcher(id).matches(), "a session id");
                        assertEquals(LoginFlowTests.SESSION_COOKIE.formatted(id), sessionCookie.getFirst());
                        var second = newToken(login);
                        assertNotEquals(first, second);
                        assertEquals(List.of("no-store"), login.headers().allValues("Cache-Control"));
                        assertEquals(1, sessions.size());
                        assertEquals(Optional.of(second), browser.xsrf(), "the CookieManager replaced the token");
                        assertEquals(Optional.of(id), browser.session());

                        var me = browser.get("/api/auth/me");
                        assertEquals(200, me.statusCode());
                        assertEquals("{\"username\":\"anna\"}", me.body());
                        assertEquals(List.of(), me.headers().allValues("Set-Cookie"));

                        // a logout with the token from before the login: 403, still logged in
                        var stale = browser.post("/api/auth/logout", null, first);
                        assertEquals(403, stale.statusCode());
                        assertEquals(BasicTests.PROBLEM_403, stale.body());
                        assertEquals(List.of(), stale.headers().allValues("Set-Cookie"));
                        assertEquals(1, sessions.size());
                        assertEquals("{\"username\":\"anna\"}", browser.get("/api/auth/me").body());

                        // a logout with the current token: 200, the session cookie removed, a new token
                        var logout = browser.post("/api/auth/logout", null);
                        assertEquals(200, logout.statusCode());
                        assertEquals("{\"success\":true}", logout.body());
                        assertEquals(List.of(LoginFlowTests.REMOVAL), sessionCookies(logout));
                        var third = newToken(logout);
                        assertNotEquals(second, third);
                        assertNotEquals(first, third);
                        assertEquals(0, sessions.size());
                        assertEquals(Optional.of(third), browser.xsrf());
                        assertEquals(Optional.empty(), browser.session());

                        // GET /api/auth/me: 401 without WWW-Authenticate (and no Set-Cookie: the token stays)
                        LoginFlowTests.assertPlain401(browser.get("/api/auth/me"));
                        assertEquals(1, counting.calls(), "only the one login checked a password");
                        CsrfSupport.text(security);
                        CsrfSupport.text(Csrf.cookie());
                    }
                }),
                test("an application whose first request is a POST: 403 with the cookie, then the same POST with"
                        + " the header logs in", () -> {
                    var sessions = CsrfExample.sessions(false, InstantSource.system());
                    var counting = new Support.Counting(LoginSupport.USERS);
                    var router = CsrfExample.router(Support.router(), CsrfExample.security(sessions),
                            counting.authenticator);
                    try (var server = Support.serve(router); var browser = new CsrfSupport.Browser(server)) {
                        assertEquals(Optional.empty(), browser.xsrf());
                        var first = browser.login(ANNA, ANNA_PASSWORD); // no cookie yet, so no header
                        assertEquals(403, first.statusCode());
                        var token = newToken(first);
                        assertEquals(List.of("no-store"), first.headers().allValues("Cache-Control"));
                        assertEquals(0, counting.calls());
                        assertEquals(0, sessions.size());
                        var again = browser.login(ANNA, ANNA_PASSWORD);
                        assertEquals(200, again.statusCode());
                        assertNotEquals(token, newToken(again));
                        assertEquals(1, sessions.size());
                        assertEquals("{\"username\":\"anna\"}", browser.get("/api/auth/me").body());
                    }
                }));
    }
}
