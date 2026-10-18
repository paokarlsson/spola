package spola.security.web;

import static spola.security.web.LoginSupport.ANNA;
import static spola.security.web.LoginSupport.ANNA_PASSWORD;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertNotEquals;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.time.InstantSource;
import java.util.List;
import spola.http.HttpServer;
import spola.http.Response;
import spola.test.Suite;
import spola.web.AttributeKey;
import spola.web.Router;
import spola.web.Sessions;

/**
 * Criterion 4: session fixation. A session that existed before the login, and an id the client
 * made up, never carry the login: the login gives a new id, the old one stops working, and the
 * values stored before the login stay with the new id.
 */
final class FixationTests {

    private FixationTests() {
    }

    static final AttributeKey<String> VISIT = AttributeKey.of("visit", String.class);

    /**
     * The use case plus an open path {@code /visit}: {@code POST} stores a value in the session
     * with {@code getOrCreate}, {@code GET} shows what {@code find} sees.
     */
    static Router router(Sessions sessions) {
        var security = WebSecurity.builder()
                .sessions(sessions)
                .rule(RequestMatcher.exactly("/api/auth/login"), AccessRule.permitAll())
                .rule(RequestMatcher.exactly("/api/auth/logout"), AccessRule.permitAll())
                .rule(RequestMatcher.exactly("/visit"), AccessRule.permitAll())
                .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
                .build();
        var routes = Support.router()
                .post("/visit", request -> {
                    Sessions.getOrCreate(request).put(VISIT, "stored before the login");
                    return Response.text(200, "stored");
                })
                .get("/visit", request -> Response.text(200, Sessions.find(request)
                        .map(session -> session.get(VISIT).orElse("no value")).orElse("no session")));
        return LoginExample.router(routes, security, LoginSupport.authenticator());
    }

    static String visit(HttpServer server, String id) throws Exception {
        return LoginSupport.send(server, "GET", "/visit", id).body();
    }

    static Suite tests() {
        return suite("session fixation",
                test("a session from before the login gets a new id; the old id is anonymous, the new one is anna and"
                        + " keeps the value", () -> {
                    var sessions = LoginExample.sessions(false, InstantSource.system());
                    try (var server = Support.serve(router(sessions))) {
                        var before = LoginSupport.issued(LoginSupport.send(server, "POST", "/visit", null));
                        assertEquals(1, sessions.size());
                        assertEquals("stored before the login", visit(server, before));
                        // a session without an identity is anonymous, and never rejected
                        LoginFlowTests.assertPlain401(LoginSupport.send(server, "GET", "/api/auth/me", before));

                        var login = LoginSupport.login(server, ANNA, ANNA_PASSWORD, before);
                        assertEquals(200, login.status(), String.valueOf(login));
                        var after = LoginSupport.issued(login);
                        assertNotEquals(before, after);
                        assertEquals(1, sessions.size(), "the same session, renewed");

                        LoginFlowTests.assertPlain401(LoginSupport.send(server, "GET", "/api/auth/me", before));
                        assertEquals("no session", visit(server, before), "the open path sees no session for the old id");
                        assertEquals("{\"username\":\"anna\"}", LoginSupport.send(server, "GET", "/api/auth/me", after).body());
                        assertEquals("stored before the login", visit(server, after), "the value stays with the new id");
                        // the old id stays dead, also after the new one has been used
                        LoginFlowTests.assertPlain401(LoginSupport.send(server, "GET", "/api/auth/me", before));
                        assertEquals(1, sessions.size());
                    }
                }),
                test("an id the client made up never becomes the session: the login gives another id, the made-up one"
                        + " stays unknown", () -> {
                    var sessions = LoginExample.sessions(false, InstantSource.system());
                    try (var server = Support.serve(router(sessions))) {
                        var madeUp = LoginSupport.madeUp();
                        assertEquals("no session", visit(server, madeUp));
                        var login = LoginSupport.login(server, ANNA, ANNA_PASSWORD, madeUp);
                        assertEquals(200, login.status(), String.valueOf(login));
                        var issued = LoginSupport.issued(login);
                        assertNotEquals(madeUp, issued);
                        assertEquals(1, sessions.size());
                        LoginFlowTests.assertPlain401(LoginSupport.send(server, "GET", "/api/auth/me", madeUp));
                        assertEquals("no session", visit(server, madeUp));
                        assertEquals("{\"username\":\"anna\"}", LoginSupport.send(server, "GET", "/api/auth/me", issued).body());
                        assertEquals("no value", visit(server, issued), "a new, empty session with the identity");

                        // storing with the made-up id creates a session with a server id, not the made-up one
                        var stored = LoginSupport.send(server, "POST", "/visit", madeUp);
                        assertNotEquals(madeUp, LoginSupport.issued(stored));
                        assertEquals("no session", visit(server, madeUp));
                        assertEquals(2, sessions.size());
                        assertEquals(List.of(), LoginSupport.send(server, "GET", "/api/auth/me", madeUp).all("Set-Cookie"));
                    }
                }));
    }
}
