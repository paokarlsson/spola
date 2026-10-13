package spola.security.web;

import static spola.security.web.LoginSupport.ANNA;
import static spola.security.web.LoginSupport.ANNA_PASSWORD;
import static spola.security.web.LoginSupport.BERTIL;
import static spola.security.web.LoginSupport.BERTIL_PASSWORD;
import static spola.security.web.LoginSupport.CECILIA;
import static spola.security.web.LoginSupport.CECILIA_PASSWORD;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertNotEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.time.InstantSource;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import spola.http.Response;
import spola.security.Identity;
import spola.test.Suite;
import spola.web.RouterBuilder;
import spola.web.SessionOptions;
import spola.web.Sessions;

/**
 * Criterion 7: what a wrong setup gives. {@code login} and {@code logout} without
 * {@code sessions(...)} or without the security filter, {@code sessions.filter()} registered next
 * to {@code security.filter()}, {@code sessions(...)} twice, and {@code login} at the session
 * limit.
 */
final class LoginMisuseTests {

    private LoginMisuseTests() {
    }

    static final Identity ANNA_IDENTITY = new Identity(ANNA, Set.of("USER"));

    /** {@code POST /login} and {@code POST /logout} that call the static methods and record what they throw. */
    static RouterBuilder routes(RouterBuilder builder, Queue<String> messages) {
        return builder
                .post("/login", request -> {
                    try {
                        WebSecurity.login(request, ANNA_IDENTITY);
                    } catch (IllegalStateException e) {
                        messages.add(LoginSupport.thrown(e).getMessage());
                        throw e;
                    }
                    return Response.text(200, "logged in");
                })
                .post("/logout", request -> {
                    try {
                        WebSecurity.logout(request);
                    } catch (IllegalStateException e) {
                        messages.add(LoginSupport.thrown(e).getMessage());
                        throw e;
                    }
                    return Response.text(200, "logged out");
                });
    }

    static final String NEEDS_SESSIONS = "WebSecurity.%s needs sessions: build the WebSecurity with"
            + " WebSecurity.builder().sessions(sessions) and register only security.filter()";
    static final String NEEDS_FILTER = "WebSecurity.%s needs a request that passed the security filter: register"
            + " WebSecurity.filter() on the router, first, for a WebSecurity built with sessions(...)";

    static List<String> both(String format) {
        return List.of(format.formatted("login"), format.formatted("logout"));
    }

    static Suite tests() {
        return suite("a wrong setup",
                test("login and logout with a WebSecurity without sessions(...): 500 (IllegalStateException)", () -> {
                    var messages = new ConcurrentLinkedQueue<String>();
                    var security = WebSecurity.builder().rule(RequestMatcher.anyRequest(), AccessRule.permitAll()).build();
                    try (var server = Support.serve(routes(Support.router().filter(security.filter()), messages).build())) {
                        var expected = both(NEEDS_SESSIONS);
                        for (var path : List.of("/login", "/logout")) {
                            var reply = LoginSupport.send(server, "POST", path, null);
                            assertEquals(500, reply.status(), path);
                            assertEquals(List.of(), reply.all("Set-Cookie"));
                        }
                        assertEquals(expected, List.copyOf(messages));
                        messages.clear();
                    }
                    // also when the application registered sessions.filter() itself: the security does not
                    // read those sessions, so a login there would be lost
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    var router = routes(Support.router().filter(sessions.filter()).filter(security.filter()), messages)
                            .build();
                    try (var server = Support.serve(router)) {
                        for (var path : List.of("/login", "/logout")) {
                            assertEquals(500, LoginSupport.send(server, "POST", path, null).status(), path);
                        }
                        assertEquals(both(NEEDS_SESSIONS), List.copyOf(messages));
                        assertEquals(0, sessions.size());
                    }
                }),
                test("login and logout without the security filter: 500 (IllegalStateException)", () -> {
                    var messages = new ConcurrentLinkedQueue<String>();
                    try (var server = Support.serve(routes(Support.router(), messages).build())) {
                        for (var path : List.of("/login", "/logout")) {
                            assertEquals(500, LoginSupport.send(server, "POST", path, null).status(), path);
                        }
                        assertEquals(both(NEEDS_FILTER), List.copyOf(messages));
                        messages.clear();
                    }
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    try (var server = Support.serve(routes(Support.router().filter(sessions.filter()), messages).build())) {
                        for (var path : List.of("/login", "/logout")) {
                            assertEquals(500, LoginSupport.send(server, "POST", path, null).status(), path);
                        }
                        assertEquals(both(NEEDS_FILTER), List.copyOf(messages));
                        assertEquals(0, sessions.size());
                    }
                }),
                test("sessions.filter() registered next to security.filter(): 500", () -> {
                    var sessions = LoginExample.sessions(false, InstantSource.system());
                    var security = LoginExample.security(sessions);
                    // before the security filter: every request
                    var before = LoginExample.router(Support.router().filter(sessions.filter()), security,
                            LoginSupport.authenticator());
                    int logged = Support.LOG.records().size();
                    try (var server = Support.serve(before)) {
                        assertEquals(500, LoginSupport.send(server, "GET", "/actuator/health", null).status());
                        assertEquals(500, LoginSupport.send(server, "GET", "/api/auth/me", null).status());
                        var login = LoginSupport.login(server, ANNA, ANNA_PASSWORD, null);
                        assertEquals(500, login.status());
                        assertEquals(List.of(), login.all("Set-Cookie"));
                        assertEquals(0, sessions.size());
                    }
                    var lines = Support.LOG.records().subList(logged, Support.LOG.records().size());
                    assertEquals(3, lines.size(), String.valueOf(lines));
                    for (var line : lines) {
                        assertTrue(line.contains("with WebSecurity.builder().sessions(...) the security filter runs it"
                                + " itself, so register only security.filter(), not sessions.filter()"), line);
                    }
                    // after the security filter: every request the rules let through
                    var after = Support.router()
                            .filter(security.filter())
                            .filter(sessions.filter())
                            .post("/api/auth/login", request -> {
                                WebSecurity.login(request, ANNA_IDENTITY);
                                return Response.text(200, "logged in");
                            })
                            .get("/actuator/health", request -> Response.text(200, "UP"))
                            .get("/api/auth/me", request -> Response.text(200, WebSecurity.requireIdentity(request).name()))
                            .build();
                    try (var server = Support.serve(after)) {
                        assertEquals(500, LoginSupport.send(server, "GET", "/actuator/health", null).status());
                        var login = LoginSupport.send(server, "POST", "/api/auth/login", null);
                        assertEquals(500, login.status());
                        assertEquals(List.of(), login.all("Set-Cookie"));
                        LoginFlowTests.assertPlain401(LoginSupport.send(server, "GET", "/api/auth/me", null));
                        assertEquals(0, sessions.size());
                    }
                }),
                test("sessions(...) twice: IllegalArgumentException from build() with a line for it", () -> {
                    var first = Sessions.inMemory(SessionOptions.DEFAULT);
                    var second = Sessions.inMemory(SessionOptions.DEFAULT);
                    var twice = "sessions(...) is called 2 times; call it once, with the one Sessions of the router";
                    var e = assertThrows(IllegalArgumentException.class, () -> WebSecurity.builder()
                            .sessions(first).sessions(second)
                            .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
                            .build());
                    assertEquals(twice, LoginSupport.thrown(e).getMessage());
                    var same = assertThrows(IllegalArgumentException.class, () -> WebSecurity.builder()
                            .sessions(first).sessions(first).build());
                    assertEquals(twice, LoginSupport.thrown(same).getMessage(), "also the same instance twice");
                    var lines = assertThrows(IllegalArgumentException.class, () -> WebSecurity.builder()
                            .sessions(first)
                            .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
                            .rule(RequestMatcher.exactly("/x"), AccessRule.permitAll())
                            .sessions(second)
                            .sessions(first)
                            .build());
                    assertEquals("rule 2 comes after anyRequest() in rule 1 and can never match\n"
                            + "sessions(...) is called 3 times; call it once, with the one Sessions of the router",
                            LoginSupport.thrown(lines).getMessage(), "one line per problem");
                    assertThrows(NullPointerException.class, () -> WebSecurity.builder().sessions(null));
                    var once = WebSecurity.builder().sessions(first).build();
                    assertEquals("WebSecurity[0 mechanisms, 0 rules, sessions]", LoginSupport.text(once));
                    assertEquals("WebSecurity[0 mechanisms, 0 rules]", LoginSupport.text(WebSecurity.builder().build()));
                }),
                test("null arguments: NullPointerException (500)", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    var security = WebSecurity.builder()
                            .sessions(sessions)
                            .rule(RequestMatcher.anyRequest(), AccessRule.permitAll())
                            .build();
                    var router = Support.router()
                            .filter(security.filter())
                            .post("/nulls", request -> {
                                assertThrows(NullPointerException.class, () -> WebSecurity.login(request, null));
                                assertThrows(NullPointerException.class, () -> WebSecurity.login(null, ANNA_IDENTITY));
                                assertThrows(NullPointerException.class, () -> WebSecurity.logout(null));
                                return Response.text(200, "checked");
                            })
                            .build();
                    try (var server = Support.serve(router)) {
                        var reply = LoginSupport.send(server, "POST", "/nulls", null);
                        assertEquals("checked", reply.body());
                        assertEquals(List.of(), reply.all("Set-Cookie"));
                        assertEquals(0, sessions.size());
                    }
                }),
                test("login at the session limit: 503 and no login; renewing a session there still works", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT.withMaxSessions(2));
                    var router = LoginExample.router(Support.router(), LoginExample.security(sessions),
                            LoginSupport.authenticator());
                    try (var server = Support.serve(router)) {
                        var anna = LoginSupport.issued(LoginSupport.login(server, ANNA, ANNA_PASSWORD, null));
                        var bertil = LoginSupport.issued(LoginSupport.login(server, BERTIL, BERTIL_PASSWORD, null));
                        assertEquals(2, sessions.size());

                        var full = LoginSupport.login(server, CECILIA, CECILIA_PASSWORD, null);
                        assertEquals(503, full.status(), String.valueOf(full));
                        assertEquals("application/problem+json", full.header("Content-Type"));
                        assertEquals(List.of(), full.all("Set-Cookie"));
                        assertFalse(full.body().contains("success"), "the handler's success answer is never built");
                        assertTrue(full.body().contains("The server cannot take more sessions right now."), full.body());
                        assertEquals(2, sessions.size());
                        // with a made-up cookie too: it never becomes a session
                        var madeUp = LoginSupport.madeUp();
                        assertEquals(503, LoginSupport.login(server, CECILIA, CECILIA_PASSWORD, madeUp).status());
                        LoginFlowTests.assertPlain401(LoginSupport.send(server, "GET", "/api/auth/me", madeUp));

                        // a login with a live session renews it, which does not count as a new session
                        var renewed = LoginSupport.login(server, ANNA, ANNA_PASSWORD, anna);
                        assertEquals(200, renewed.status());
                        var annaAgain = LoginSupport.issued(renewed);
                        assertNotEquals(anna, annaAgain);
                        assertEquals(2, sessions.size());

                        assertEquals(200, LoginSupport.send(server, "POST", "/api/auth/logout", bertil).status());
                        assertEquals(1, sessions.size());
                        var cecilia = LoginSupport.issued(LoginSupport.login(server, CECILIA, CECILIA_PASSWORD, null));
                        assertEquals("{\"username\":\"cecilia\"}",
                                LoginSupport.send(server, "GET", "/api/auth/me", cecilia).body());
                        assertEquals("{\"username\":\"anna\"}",
                                LoginSupport.send(server, "GET", "/api/auth/me", annaAgain).body());
                        assertEquals(2, sessions.size());
                    }
                }));
    }
}
