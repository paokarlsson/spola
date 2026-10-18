package spola.security.web;

import static spola.security.web.LoginSupport.ANNA;
import static spola.security.web.LoginSupport.ANNA_PASSWORD;
import static spola.security.web.LoginSupport.BERTIL_PASSWORD;
import static spola.security.web.LoginSupport.idOf;
import static spola.security.web.LoginSupport.sessionCookies;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertNotEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.http.HttpResponse;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import spola.http.Response;
import spola.security.Identity;
import spola.test.Suite;
import spola.web.WebException;

/**
 * Criterion 3: the use case ({@link LoginExample}) through a real server, {@code HttpClient} and
 * a {@code CookieManager}: health check, 401 without {@code WWW-Authenticate}, failed logins,
 * login, the logged-in user, logout and the old id afterwards. Also that {@code login} and
 * {@code logout} leave the request's own identity as it was.
 */
final class LoginFlowTests {

    private LoginFlowTests() {
    }

    static final String SESSION_COOKIE = "SESSION=%s; Path=/; HttpOnly; SameSite=Lax";
    static final String REMOVAL = "SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax";

    static void assertNoSetCookie(HttpResponse<String> response) {
        assertEquals(List.of(), response.headers().allValues("Set-Cookie"));
    }

    /** The filter's 401 of a security without {@code HttpBasic}: no {@code WWW-Authenticate}. */
    static void assertPlain401(HttpResponse<String> response) {
        assertEquals(401, response.statusCode());
        assertEquals(Optional.of("application/problem+json"), response.headers().firstValue("Content-Type"));
        assertEquals(List.of("no-store"), response.headers().allValues("Cache-Control"));
        assertEquals(List.of(), response.headers().allValues("WWW-Authenticate"));
        assertEquals(BasicTests.PROBLEM_401, response.body());
        assertNoSetCookie(response);
    }

    /** The same over a raw socket, where every header line is visible. */
    static void assertPlain401(Support.Reply reply) {
        assertEquals(401, reply.status(), String.valueOf(reply));
        assertEquals("application/problem+json", reply.header("Content-Type"));
        assertEquals(List.of("no-store"), reply.all("Cache-Control"));
        assertEquals(List.of(), reply.all("WWW-Authenticate"));
        assertEquals(List.of(), reply.all("Set-Cookie"));
        assertEquals(BasicTests.PROBLEM_401, reply.body());
    }

    static Suite tests() {
        return suite("the use case",
                test("health, 401 without WWW-Authenticate, failed logins, login, me, logout and the old id", () -> {
                    var sessions = LoginExample.sessions(false, InstantSource.system());
                    var security = LoginExample.security(sessions);
                    // requireIdentity throws a 401 WebException if the /api/auth/me handler runs
                    // for an anonymous request; this mapper counts it (and answers as without it)
                    var handlerFailures = new AtomicInteger();
                    var otherHandler = new AtomicInteger();
                    var routes = Support.router()
                            .onException(WebException.class, (e, request) -> {
                                handlerFailures.incrementAndGet();
                                return e.toResponse(request);
                            })
                            .get("/api/other", request -> {
                                otherHandler.incrementAndGet();
                                return Response.text(200, "other");
                            });
                    var router = LoginExample.router(routes, security, LoginSupport.authenticator());
                    try (var server = Support.serve(router); var client = LoginSupport.client()) {
                        var health = LoginSupport.get(client, server, "/actuator/health");
                        assertEquals(200, health.statusCode());
                        assertEquals("{\"status\":\"UP\"}", health.body());
                        assertNoSetCookie(health);
                        assertEquals(0, sessions.size());

                        assertPlain401(LoginSupport.get(client, server, "/api/auth/me"));
                        assertEquals(0, handlerFailures.get(), "the /api/auth/me handler did not run");
                        assertPlain401(LoginSupport.get(client, server, "/api/other"));
                        assertEquals(0, otherHandler.get(), "no handler runs for a denied request");
                        assertEquals(0, sessions.size());

                        var wrong = LoginSupport.login(client, server, ANNA, BERTIL_PASSWORD);
                        assertEquals(401, wrong.statusCode());
                        assertEquals("{\"success\":false}", wrong.body());
                        assertEquals(Optional.of("application/json"), wrong.headers().firstValue("Content-Type"));
                        assertNoSetCookie(wrong);
                        var unknown = LoginSupport.login(client, server, "nobody", ANNA_PASSWORD);
                        assertEquals(401, unknown.statusCode());
                        assertEquals("{\"success\":false}", unknown.body());
                        assertNoSetCookie(unknown);
                        assertEquals(0, sessions.size());

                        var login = LoginSupport.login(client, server, ANNA, ANNA_PASSWORD);
                        assertEquals(200, login.statusCode());
                        assertEquals("{\"success\":true,\"username\":\"anna\"}", login.body());
                        var setCookies = login.headers().allValues("Set-Cookie");
                        assertEquals(1, setCookies.size(), "exactly one Set-Cookie");
                        var id = idOf(setCookies.getFirst());
                        assertTrue(LoginSupport.ID.matcher(id).matches(), "a session id");
                        assertEquals(SESSION_COOKIE.formatted(id), setCookies.getFirst());
                        assertEquals(List.of("no-store"), login.headers().allValues("Cache-Control"));
                        assertEquals(1, sessions.size());

                        var me = LoginSupport.get(client, server, "/api/auth/me");
                        assertEquals(200, me.statusCode());
                        assertEquals("{\"username\":\"anna\"}", me.body());
                        assertNoSetCookie(me);
                        var other = LoginSupport.get(client, server, "/api/other");
                        assertEquals("other", other.body());
                        assertEquals(1, otherHandler.get());

                        var logout = LoginSupport.post(client, server, "/api/auth/logout", null);
                        assertEquals(200, logout.statusCode());
                        assertEquals("{\"success\":true}", logout.body());
                        assertEquals(List.of(REMOVAL), logout.headers().allValues("Set-Cookie"));
                        assertEquals(0, sessions.size());

                        assertPlain401(LoginSupport.get(client, server, "/api/auth/me"));
                        var old = LoginSupport.send(server, "GET", "/api/auth/me", id);
                        assertPlain401(old);
                        assertEquals(0, handlerFailures.get(), "the /api/auth/me handler never ran anonymously");
                        assertEquals(0, sessions.size());

                        // a logout without a session does nothing, also no Set-Cookie
                        var again = LoginSupport.post(client, server, "/api/auth/logout", null);
                        assertEquals("{\"success\":true}", again.body());
                        assertNoSetCookie(again);
                        LoginSupport.text(security);
                        LoginSupport.text(sessions);
                    }
                }),
                test("login and logout leave the request's own identity as the filter found it", () -> {
                    var sessions = LoginExample.sessions(false, InstantSource.system());
                    var security = WebSecurity.builder()
                            .sessions(sessions)
                            .rule(RequestMatcher.exactly("/login-and-tell"), AccessRule.permitAll())
                            .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
                            .build();
                    var anna = new Identity(ANNA, Set.of("USER"));
                    var router = Support.router()
                            .filter(security.filter())
                            .post("/login-and-tell", request -> {
                                WebSecurity.login(request, anna);
                                return Response.text(200, "identity " + WebSecurity.identity(request).map(Identity::name)
                                        .orElse("none"));
                            })
                            .post("/logout-and-tell", request -> {
                                WebSecurity.logout(request);
                                return Response.text(200, "identity " + WebSecurity.requireIdentity(request).name());
                            })
                            .get("/me", request -> Response.text(200, WebSecurity.requireIdentity(request).name()))
                            .build();
                    try (var server = Support.serve(router)) {
                        var login = LoginSupport.send(server, "POST", "/login-and-tell", null);
                        assertEquals("identity none", login.body(), "still anonymous in the request that logged in");
                        var id = LoginSupport.issued(login);
                        assertEquals(ANNA, LoginSupport.send(server, "GET", "/me", id).body(), "the next request");
                        var logout = LoginSupport.send(server, "POST", "/logout-and-tell", id);
                        assertEquals("identity anna", logout.body(), "still anna in the request that logged out");
                        assertEquals(List.of(REMOVAL), sessionCookies(logout));
                        assertEquals(401, LoginSupport.send(server, "GET", "/me", id).status(), "the next request");
                        assertEquals(0, sessions.size());

                        // a second login in one session: a new id again, the identity replaced
                        var first = LoginSupport.issued(LoginSupport.send(server, "POST", "/login-and-tell", null));
                        var second = LoginSupport.send(server, "POST", "/login-and-tell", first);
                        assertEquals("identity anna", second.body(), "the filter found anna in the session");
                        var renewed = LoginSupport.issued(second);
                        assertNotEquals(first, renewed);
                        assertEquals(401, LoginSupport.send(server, "GET", "/me", first).status());
                        assertEquals(ANNA, LoginSupport.send(server, "GET", "/me", renewed).body());
                        assertEquals(1, sessions.size());
                        assertFalse(LoginSupport.text(security).contains(renewed));
                    }
                }));
    }
}
