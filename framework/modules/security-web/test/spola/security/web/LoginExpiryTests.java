package spola.security.web;

import static spola.security.web.LoginSupport.ANNA;
import static spola.security.web.LoginSupport.ANNA_PASSWORD;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertNotEquals;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.time.Duration;
import java.util.List;
import spola.test.Suite;

/**
 * Criterion 6: with a controlled clock, the login lasts 30 minutes after the last request (one
 * second less works, exactly 30 minutes does not), and a login with an expired session cookie
 * on the open login path works and gives a new session instead of 401.
 */
final class LoginExpiryTests {

    private LoginExpiryTests() {
    }

    static final Duration IDLE = Duration.ofMinutes(30);
    static final Duration SECOND = Duration.ofSeconds(1);

    static Suite tests() {
        return suite("expiry",
                test("/api/auth/me works 30 minutes minus a second after the last request and gives 401 at 30 minutes;"
                        + " a login with the expired cookie gives a new session", () -> {
                    var clock = new LoginSupport.TestClock();
                    var sessions = LoginExample.sessions(false, clock);
                    var router = LoginExample.router(Support.router(), LoginExample.security(sessions),
                            LoginSupport.authenticator());
                    try (var server = Support.serve(router)) {
                        var id = LoginSupport.issued(LoginSupport.login(server, ANNA, ANNA_PASSWORD, null));
                        var me = "{\"username\":\"anna\"}";

                        clock.advance(IDLE.minus(SECOND));
                        assertEquals(me, LoginSupport.send(server, "GET", "/api/auth/me", id).body(),
                                "30 minutes minus a second after the login");
                        clock.advance(IDLE.minus(SECOND));
                        assertEquals(me, LoginSupport.send(server, "GET", "/api/auth/me", id).body(),
                                "30 minutes minus a second after the last request: each request counts as a use");
                        assertEquals(1, sessions.size());

                        clock.advance(IDLE);
                        LoginFlowTests.assertPlain401(LoginSupport.send(server, "GET", "/api/auth/me", id));
                        assertEquals(0, sessions.size(), "the expired session is removed");

                        var login = LoginSupport.login(server, ANNA, ANNA_PASSWORD, id);
                        assertEquals(200, login.status(), "an expired cookie never gives 401 on the open login path");
                        assertEquals("{\"success\":true,\"username\":\"anna\"}", login.body());
                        var renewed = LoginSupport.issued(login);
                        assertNotEquals(id, renewed);
                        assertEquals(1, sessions.size());
                        assertEquals(me, LoginSupport.send(server, "GET", "/api/auth/me", renewed).body());
                        LoginFlowTests.assertPlain401(LoginSupport.send(server, "GET", "/api/auth/me", id));

                        // the health check with the expired cookie is open and sets nothing
                        var health = LoginSupport.send(server, "GET", "/actuator/health", id);
                        assertEquals(200, health.status());
                        assertEquals(List.of(), health.all("Set-Cookie"));

                        // exactly 30 minutes after the last request, also for a fresh login
                        clock.advance(IDLE);
                        LoginFlowTests.assertPlain401(LoginSupport.send(server, "GET", "/api/auth/me", renewed));
                        assertEquals(0, sessions.size());
                    }
                }));
    }
}
