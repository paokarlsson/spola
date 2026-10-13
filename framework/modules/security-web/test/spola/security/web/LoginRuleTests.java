package spola.security.web;

import static spola.security.web.LoginSupport.ANNA;
import static spola.security.web.LoginSupport.ANNA_PASSWORD;
import static spola.security.web.LoginSupport.BERTIL;
import static spola.security.web.LoginSupport.BERTIL_PASSWORD;
import static spola.test.Assertions.assertEquals;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import spola.http.HttpServer;
import spola.http.Response;
import spola.security.Authenticator;
import spola.security.User;
import spola.security.UserStore;
import spola.test.Suite;
import spola.web.Router;
import spola.web.Sessions;

/**
 * Criterion 5: an identity from the session follows the rules ({@code hasRole}), the session
 * comes before {@link HttpBasic}, a request without a session uses Basic as in 10b, and the 401
 * has {@code WWW-Authenticate} only when {@code HttpBasic} is registered. Also the snapshot:
 * changes to the user do not reach a session that is logged in.
 */
final class LoginRuleTests {

    private LoginRuleTests() {
    }

    /** The use case plus {@code /api/admin} for ADMIN, whose handler counts its calls. */
    static Router router(Sessions sessions, Authenticator authenticator, AtomicInteger adminCalls,
            RequestAuthentication... mechanisms) {
        var builder = WebSecurity.builder().sessions(sessions);
        for (var mechanism : mechanisms) {
            builder.authentication(mechanism);
        }
        var security = builder
                .rule(RequestMatcher.exactly("/api/auth/login"), AccessRule.permitAll())
                .rule(RequestMatcher.exactly("/api/auth/logout"), AccessRule.permitAll())
                .rule(RequestMatcher.under("/api/admin"), AccessRule.hasRole("ADMIN"))
                .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
                .build();
        var routes = Support.router().get("/api/admin", request -> {
            adminCalls.incrementAndGet();
            return Response.text(200, "admin page for " + WebSecurity.requireIdentity(request).name());
        });
        return LoginExample.router(routes, security, authenticator);
    }

    /** A request with the session cookie {@code id} (or none) and an {@code Authorization} field. */
    static Support.Reply withBasic(HttpServer server, String path, String id, String authorization) throws Exception {
        Support.recordSent(authorization);
        var field = "Authorization: " + authorization;
        return id == null ? LoginSupport.raw(server, "GET", path, null, field)
                : LoginSupport.raw(server, "GET", path, null, field, LoginSupport.cookie(id));
    }

    static Suite tests() {
        return suite("rules, HttpBasic and the snapshot",
                test("hasRole(ADMIN) on /api/admin: 403 for a logged-in user without the role, the handler with it", () -> {
                    var sessions = LoginExample.sessions(false, InstantSource.system());
                    var adminCalls = new AtomicInteger();
                    try (var server = Support.serve(router(sessions, LoginSupport.authenticator(), adminCalls))) {
                        LoginFlowTests.assertPlain401(LoginSupport.send(server, "GET", "/api/admin", null));
                        var anna = LoginSupport.issued(LoginSupport.login(server, ANNA, ANNA_PASSWORD, null));
                        var forbidden = LoginSupport.send(server, "GET", "/api/admin", anna);
                        BasicTests.assert403(forbidden);
                        assertEquals(List.of(), forbidden.all("Set-Cookie"));
                        assertEquals(0, adminCalls.get(), "the handler does not run for a 403");
                        assertEquals("{\"username\":\"anna\"}", LoginSupport.send(server, "GET", "/api/auth/me", anna).body());

                        var bertil = LoginSupport.issued(LoginSupport.login(server, BERTIL, BERTIL_PASSWORD, null));
                        var allowed = LoginSupport.send(server, "GET", "/api/admin", bertil);
                        assertEquals(200, allowed.status(), String.valueOf(allowed));
                        assertEquals("admin page for bertil", allowed.body());
                        assertEquals(1, adminCalls.get());
                        BasicTests.assert403(LoginSupport.send(server, "GET", "/api/admin", anna));
                        assertEquals(1, adminCalls.get());
                    }
                }),
                test("HttpBasic next to the sessions: the session first, Basic without a session as in 10b, 401 with"
                        + " WWW-Authenticate", () -> {
                    var sessions = LoginExample.sessions(false, InstantSource.system());
                    var counting = new Support.Counting(LoginSupport.USERS);
                    var adminCalls = new AtomicInteger();
                    var router = router(sessions, LoginSupport.authenticator(), adminCalls,
                            HttpBasic.of(counting.authenticator, "app"));
                    var bertilBasic = Support.basic(BERTIL, BERTIL_PASSWORD);
                    var wrongBasic = Support.basic(BERTIL, ANNA_PASSWORD);
                    try (var server = Support.serve(router)) {
                        BasicTests.assert401(LoginSupport.send(server, "GET", "/api/auth/me", null));
                        BasicTests.assert401(LoginSupport.send(server, "GET", "/api/admin", null));
                        var anna = LoginSupport.issued(LoginSupport.login(server, ANNA, ANNA_PASSWORD, null));
                        assertEquals(0, counting.calls());

                        // the session's identity comes first: Basic is not tried, right or wrong
                        assertEquals("{\"username\":\"anna\"}", withBasic(server, "/api/auth/me", anna, bertilBasic).body());
                        var wrongWithSession = withBasic(server, "/api/auth/me", anna, wrongBasic);
                        assertEquals(200, wrongWithSession.status(), String.valueOf(wrongWithSession));
                        assertEquals("{\"username\":\"anna\"}", wrongWithSession.body());
                        BasicTests.assert403(withBasic(server, "/api/admin", anna, bertilBasic));
                        assertEquals(0, counting.calls(), "no Basic check while the session has an identity");
                        assertEquals(0, adminCalls.get());

                        // without a session, Basic as in 10b
                        var basic = withBasic(server, "/api/auth/me", null, bertilBasic);
                        assertEquals("{\"username\":\"bertil\"}", basic.body());
                        assertEquals(List.of(), basic.all("Set-Cookie"), "Basic never creates a session");
                        assertEquals("admin page for bertil", withBasic(server, "/api/admin", null, bertilBasic).body());
                        BasicTests.assert401(withBasic(server, "/api/auth/me", null, wrongBasic));
                        // wrong Basic credentials are rejected also on an open path
                        BasicTests.assert401(withBasic(server, "/api/auth/logout", null, wrongBasic));
                        assertEquals(4, counting.calls());

                        // an unknown session cookie is no rejection: Basic decides, or the request is anonymous
                        var unknown = LoginSupport.madeUp();
                        assertEquals("{\"username\":\"bertil\"}", withBasic(server, "/api/auth/me", unknown, bertilBasic).body());
                        BasicTests.assert401(LoginSupport.send(server, "GET", "/api/auth/me", unknown));
                        assertEquals(5, counting.calls());

                        // after the logout the old cookie is just unknown
                        var logout = LoginSupport.send(server, "POST", "/api/auth/logout", anna);
                        assertEquals(200, logout.status());
                        assertEquals("{\"username\":\"bertil\"}", withBasic(server, "/api/auth/me", anna, bertilBasic).body());
                        BasicTests.assert401(LoginSupport.send(server, "GET", "/api/auth/me", anna));
                        assertEquals(6, counting.calls());
                        assertEquals(1, adminCalls.get());
                        assertEquals(0, sessions.size());
                    }
                }),
                test("the session holds a snapshot: a changed or disabled user stays logged in until the logout", () -> {
                    var hash = Support.HASHER.hash(ANNA_PASSWORD);
                    var current = new AtomicReference<>(new User(ANNA, hash, Set.of("USER", "ADMIN"), true));
                    UserStore store = username -> username.equals(ANNA) ? Optional.of(current.get()) : Optional.empty();
                    var sessions = LoginExample.sessions(false, InstantSource.system());
                    var adminCalls = new AtomicInteger();
                    var router = router(sessions, Authenticator.of(store, Support.HASHER), adminCalls);
                    try (var server = Support.serve(router)) {
                        var id = LoginSupport.issued(LoginSupport.login(server, ANNA, ANNA_PASSWORD, null));
                        assertEquals("admin page for anna", LoginSupport.send(server, "GET", "/api/admin", id).body());

                        current.set(new User(ANNA, hash, Set.of("USER"), false));
                        assertEquals("admin page for anna", LoginSupport.send(server, "GET", "/api/admin", id).body(),
                                "the roles of the login, not of the store");
                        var again = LoginSupport.login(server, ANNA, ANNA_PASSWORD, id);
                        assertEquals(401, again.status(), "a disabled account cannot log in again");
                        assertEquals("{\"success\":false}", again.body());
                        assertEquals(List.of(), again.all("Set-Cookie"));
                        assertEquals("admin page for anna", LoginSupport.send(server, "GET", "/api/admin", id).body(),
                                "a failed login leaves the session as it was");

                        LoginSupport.send(server, "POST", "/api/auth/logout", id);
                        LoginFlowTests.assertPlain401(LoginSupport.send(server, "GET", "/api/admin", id));
                        assertEquals(3, adminCalls.get());
                    }
                }));
    }
}
