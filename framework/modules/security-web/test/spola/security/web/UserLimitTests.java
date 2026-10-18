package spola.security.web;

import static spola.security.web.LoginSupport.ANNA;
import static spola.security.web.LoginSupport.BERTIL;
import static spola.security.web.LoginSupport.CECILIA;
import static spola.security.web.ReviewSupport.issued;
import static spola.security.web.ReviewSupport.login;
import static spola.security.web.ReviewSupport.me;
import static spola.security.web.ReviewSupport.named;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertNotEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import spola.test.Suite;
import spola.web.SessionOptions;
import spola.web.Sessions;

/**
 * Uppdrag 15a, G1 (criterion 3): a user has at most {@code maximumSessions} sessions. A login
 * beyond that ends the user's least recently used session, never the new login, so one account
 * that logs in again and again cannot fill {@code maxSessions} and lock other users out.
 */
final class UserLimitTests {

    private UserLimitTests() {
    }

    static Suite tests() {
        return suite("sessions per user",
                test("the README example (the default of 10): 15 logins of anna without a cookie never give her more"
                        + " than 10 sessions; the oldest get 401, the newest work, bertil can log in", () -> {
                    var sessions = LoginExample.sessions(false, InstantSource.system());
                    try (var server = Support.serve(ReviewSupport.router(LoginExample.security(sessions)))) {
                        var ids = new ArrayList<String>();
                        for (int i = 1; i <= 15; i++) {
                            var reply = login(server, ANNA, null);
                            assertEquals(200, reply.status(), reply.toString());
                            ids.add(issued(reply));
                            assertEquals(Math.min(i, 10), sessions.size(), "after login " + i);
                        }
                        for (int i = 0; i < 5; i++) {
                            assertEquals("401", me(server, ids.get(i)), "login " + (i + 1) + " has ended");
                        }
                        for (int i = 5; i < 15; i++) {
                            assertEquals(named(ANNA), me(server, ids.get(i)), "login " + (i + 1) + " works");
                        }
                        var bertil = login(server, BERTIL, null);
                        assertEquals(200, bertil.status());
                        assertEquals(named(BERTIL), me(server, issued(bertil)));
                        assertEquals(11, sessions.size());
                        assertEquals(10, WebSecurity.DEFAULT_MAXIMUM_SESSIONS);
                    }
                }),
                test("maximumSessions(3): the session used longest ago ends, not the one created first", () -> {
                    var sessions = LoginExample.sessions(false, InstantSource.system());
                    try (var server = Support.serve(ReviewSupport.router(ReviewSupport.loginSecurity(sessions, 3)))) {
                        var a = issued(login(server, ANNA, null));
                        var b = issued(login(server, ANNA, null));
                        var c = issued(login(server, ANNA, null));
                        assertEquals(named(ANNA), me(server, a)); // a is now the most recently used
                        var d = issued(login(server, ANNA, null));
                        assertEquals(3, sessions.size());
                        assertEquals("401", me(server, b), "b was used longest ago");
                        assertEquals(named(ANNA), me(server, a));
                        assertEquals(named(ANNA), me(server, c));
                        assertEquals(named(ANNA), me(server, d));
                        // the newest login is never the one that ends, also when the others were all just used
                        var e = issued(login(server, ANNA, null));
                        assertEquals(named(ANNA), me(server, e));
                        assertEquals("401", me(server, a), "a was used before c, d and e");
                        assertEquals(3, sessions.size());
                    }
                }),
                test("maxSessions 50 and maximumSessions(5): 100 logins of one account do not stop a second user", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT.withMaxSessions(50));
                    try (var server = Support.serve(ReviewSupport.router(ReviewSupport.loginSecurity(sessions, 5)))) {
                        var last = new ArrayList<String>();
                        for (int i = 0; i < 100; i++) {
                            var reply = login(server, ANNA, null);
                            assertEquals(200, reply.status(), "login " + (i + 1) + ": " + reply);
                            last.add(issued(reply));
                            assertEquals(Math.min(i + 1, 5), sessions.size());
                        }
                        var bertil = login(server, BERTIL, null);
                        assertEquals(200, bertil.status(), bertil.toString());
                        assertEquals(named(BERTIL), me(server, issued(bertil)));
                        assertEquals(6, sessions.size());
                        for (var id : last.subList(95, 100)) {
                            assertEquals(named(ANNA), me(server, id));
                        }
                        assertEquals("401", me(server, last.get(94)));
                    }
                }),
                test("a login with the user's own cookie renews that session: no other session ends", () -> {
                    var sessions = LoginExample.sessions(false, InstantSource.system());
                    try (var server = Support.serve(ReviewSupport.router(ReviewSupport.loginSecurity(sessions, 2)))) {
                        var a = issued(login(server, ANNA, null));
                        var b = issued(login(server, ANNA, null));
                        var a2 = issued(login(server, ANNA, a));
                        assertNotEquals(a, a2);
                        assertEquals(2, sessions.size());
                        assertEquals("401", me(server, a), "the old id stops working (session fixation)");
                        assertEquals(named(ANNA), me(server, a2));
                        assertEquals(named(ANNA), me(server, b));
                    }
                }),
                test("maximumSessions(1): a second login ends the first; at maxSessions a user at the limit can still"
                        + " log in, while a user without a session gets 503", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT.withMaxSessions(2));
                    try (var server = Support.serve(ReviewSupport.router(ReviewSupport.loginSecurity(sessions, 1)))) {
                        var first = issued(login(server, ANNA, null));
                        var second = issued(login(server, ANNA, null));
                        assertEquals("401", me(server, first));
                        assertEquals(named(ANNA), me(server, second));
                        var bertil = issued(login(server, BERTIL, null));
                        assertEquals(2, sessions.size(), "maxSessions is reached");
                        var again = login(server, BERTIL, null);
                        assertEquals(200, again.status(), "bertil's own session made room: " + again);
                        assertEquals("401", me(server, bertil));
                        assertEquals(named(BERTIL), me(server, issued(again)));
                        var cecilia = login(server, CECILIA, null);
                        assertEquals(503, cecilia.status(), cecilia.toString());
                        assertEquals(List.of(), cecilia.all("Set-Cookie"));
                        assertEquals("{\"type\":\"about:blank\",\"title\":\"Service Unavailable\",\"status\":503,"
                                + "\"detail\":\"The server cannot take more sessions right now.\","
                                + "\"instance\":\"/api/auth/login\"}", cecilia.body());
                        assertEquals(named(ANNA), me(server, second), "no one else's session ended");
                    }
                }),
                test("build(): maximumSessions below 1, and maximumSessions without sessions, are problems", () -> {
                    var sessions = LoginExample.sessions(false, InstantSource.system());
                    var zero = ReviewSupport.thrown(assertThrows(IllegalArgumentException.class, () -> WebSecurity.builder()
                            .sessions(sessions).maximumSessions(0).rule(RequestMatcher.anyRequest(), AccessRule.permitAll())
                            .build()));
                    assertEquals("maximumSessions(0): a user needs at least 1 session", zero.getMessage());
                    var alone = ReviewSupport.thrown(assertThrows(IllegalArgumentException.class, () -> WebSecurity.builder()
                            .maximumSessions(5).rule(RequestMatcher.anyRequest(), AccessRule.permitAll())
                            .rule(RequestMatcher.anyRequest(), AccessRule.denyAll()).build()));
                    assertEquals("rule 2 comes after anyRequest() in rule 1 and can never match\n"
                            + "maximumSessions(...) without sessions(...): it limits the sessions of WebSecurity.login;"
                            + " add sessions(sessions)", alone.getMessage());
                    var negative = ReviewSupport.thrown(assertThrows(IllegalArgumentException.class, () -> WebSecurity.builder()
                            .maximumSessions(-1).build()));
                    assertEquals("maximumSessions(-1): a user needs at least 1 session\n"
                            + "maximumSessions(...) without sessions(...): it limits the sessions of WebSecurity.login;"
                            + " add sessions(sessions)", negative.getMessage());
                    // the last call wins
                    var security = WebSecurity.builder().sessions(sessions).maximumSessions(0).maximumSessions(2)
                            .rule(RequestMatcher.anyRequest(), AccessRule.permitAll()).build();
                    assertEquals("WebSecurity[0 mechanisms, 1 rules, sessions]", ReviewSupport.text(security));
                }));
    }
}
