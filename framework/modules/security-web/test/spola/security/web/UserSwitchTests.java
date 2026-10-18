package spola.security.web;

import static spola.security.web.LoginSupport.ANNA;
import static spola.security.web.LoginSupport.BERTIL;
import static spola.security.web.ReviewSupport.issued;
import static spola.security.web.ReviewSupport.login;
import static spola.security.web.ReviewSupport.me;
import static spola.security.web.ReviewSupport.named;
import static spola.security.web.ReviewSupport.raw;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertNotEquals;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.time.InstantSource;
import java.util.List;
import spola.http.HttpServer;
import spola.test.Suite;
import spola.web.SessionOptions;
import spola.web.Sessions;

/**
 * Uppdrag 15a, G2 (criterion 5): a login as another user over a session that holds a login
 * (a shared computer without a logout) gets a new, empty session; the same user keeps the
 * session's values.
 */
final class UserSwitchTests {

    private UserSwitchTests() {
    }

    static String note(HttpServer server, String id) throws Exception {
        var reply = ReviewSupport.send(server, "GET", "/note", id);
        return reply.status() == 200 ? reply.body() : String.valueOf(reply.status());
    }

    static void store(HttpServer server, String id, String note) throws Exception {
        var reply = raw(server, "POST", "/note", note, LoginSupport.cookie(id));
        assertEquals(200, reply.status(), reply.toString());
    }

    static Suite tests() {
        return suite("another user gets a new session",
                test("anna stores a value; bertil logs in from the same client without a logout and does not see"
                        + " it; anna's old id stops working; a login of the same user keeps the values", () -> {
                    var sessions = LoginExample.sessions(false, InstantSource.system());
                    try (var server = Support.serve(ReviewSupport.router(LoginExample.security(sessions)))) {
                        var anna = issued(login(server, ANNA, null));
                        store(server, anna, "anna's private note");
                        assertEquals("anna's private note", note(server, anna));

                        var switched = login(server, BERTIL, anna); // the same browser: it sends anna's cookie
                        assertEquals(200, switched.status(), switched.toString());
                        assertEquals(1, switched.all("Set-Cookie").size(), "only the new id, no removal as well");
                        var bertil = issued(switched);
                        assertNotEquals(anna, bertil);
                        assertEquals("none", note(server, bertil), "bertil starts with an empty session");
                        assertEquals(named(BERTIL), me(server, bertil));
                        assertEquals("401", me(server, anna), "anna's old id stops working");
                        assertEquals("401", note(server, anna));
                        assertEquals(1, sessions.size(), "anna's session was removed, not kept beside bertil's");

                        // the same user logging in again (from the same browser) keeps the values
                        store(server, bertil, "bertil's note");
                        var again = issued(login(server, BERTIL, bertil));
                        assertNotEquals(bertil, again); // a new id, as at every login
                        assertEquals("bertil's note", note(server, again));
                        assertEquals(named(BERTIL), me(server, again));
                        assertEquals("401", me(server, bertil));
                        assertEquals(1, sessions.size());

                        // and anna, back on her own client, starts over without bertil's value
                        var annaAgain = issued(login(server, ANNA, again));
                        assertEquals("none", note(server, annaAgain));
                        assertEquals("401", me(server, again));
                    }
                }),
                test("the switch frees the previous user's session first, so it works at maxSessions, and a failed"
                        + " login leaves the session as it was", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT.withMaxSessions(1));
                    try (var server = Support.serve(ReviewSupport.router(LoginExample.security(sessions)))) {
                        var anna = issued(login(server, ANNA, null));
                        store(server, anna, "anna's note");
                        var wrong = raw(server, "POST", "/api/auth/login",
                                LoginSupport.loginJson(BERTIL, "zPwdWrong-password"), "Content-Type: application/json",
                                LoginSupport.cookie(anna));
                        assertEquals(401, wrong.status());
                        assertEquals(List.of(), wrong.all("Set-Cookie"));
                        assertEquals("anna's note", note(server, anna), "a wrong password changes nothing");
                        var bertil = login(server, BERTIL, anna);
                        assertEquals(200, bertil.status(), "maxSessions is 1, and anna's session made room: " + bertil);
                        assertEquals("none", note(server, issued(bertil)));
                        assertEquals(1, sessions.size());
                    }
                }));
    }
}
