package spola.security.web;

import static spola.security.web.LoginSupport.ANNA;
import static spola.security.web.LoginSupport.BERTIL;
import static spola.security.web.UserSessionSupport.issued;
import static spola.security.web.UserSessionSupport.raw;
import static spola.security.web.UserSessionSupport.send;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import spola.http.HttpServer;
import spola.http.Response;
import spola.security.Identity;
import spola.test.Suite;
import spola.web.Router;
import spola.web.Sessions;

/**
 * The limit per user and the new session for another user when requests race on one cookie
 * (15a): a login of the session's own user against {@link Sessions#renew} (which an app calls
 * when a session changes privilege), and logins of two users at once. Once the requests are done,
 * every session that holds a user's login counts towards the user's limit, and a login as another
 * user never keeps the previous user's values.
 */
final class UserLimitRaceTests {

    private UserLimitRaceTests() {
    }

    /** Rounds of each race; each round is one try at hitting the window between two requests. */
    static final int ROUNDS = 400;

    /**
     * {@code POST /login/{user}} logs the user in without a password (the race is in the session,
     * not in the password check), {@code POST /renew} calls {@link Sessions#renew}, {@code /note}
     * stores and shows a value, {@code GET /me} the name; all but the login need a login.
     */
    static Router router(Sessions sessions, int limit) {
        var security = WebSecurity.builder()
                .sessions(sessions)
                .maximumSessions(limit)
                .rule(RequestMatcher.under("/login"), AccessRule.permitAll())
                .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
                .build();
        return Support.router()
                .filter(security.filter())
                .post("/login/{user}", request -> {
                    WebSecurity.login(request, new Identity(request.pathParam("user"), Set.of("USER")));
                    return Response.text(200, "logged in");
                })
                .post("/renew", request -> {
                    Sessions.renew(request);
                    return Response.text(200, "renewed");
                })
                .post("/note", request -> {
                    Sessions.find(request).orElseThrow().put(UserSessionSupport.NOTE, "the user's note");
                    return Response.text(200, "stored");
                })
                .get("/note", request -> Response.text(200, Sessions.find(request).flatMap(s -> s.get(UserSessionSupport.NOTE))
                        .orElse("none")))
                .get("/me", request -> Response.text(200, WebSecurity.requireIdentity(request).name()))
                .build();
    }

    /** {@code POST} to {@code first} and {@code second} with the session cookie {@code id} at the same moment. */
    static List<Support.Reply> atOnce(HttpServer server, String first, String second, String id) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> {
                start.await();
                return send(server, "POST", first, id);
            });
            var b = executor.submit(() -> {
                start.await();
                return send(server, "POST", second, id);
            });
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    static String login(HttpServer server, String user) throws Exception {
        var reply = send(server, "POST", "/login/" + user, null);
        assertEquals(200, reply.status(), reply.toString());
        return issued(reply);
    }

    /** {@code GET /me} with {@code id}: the name, or the status. */
    static String me(HttpServer server, String id) throws Exception {
        var reply = send(server, "GET", "/me", id);
        return reply.status() == 200 ? reply.body() : String.valueOf(reply.status());
    }

    /** The ids among {@code ids} that are logged in as {@code user}. */
    static List<String> loggedIn(HttpServer server, List<String> ids, String user) throws Exception {
        var working = new ArrayList<String>();
        for (var id : ids) {
            if (me(server, id).equals(user)) {
                working.add(id);
            }
        }
        return working;
    }

    static Suite tests() {
        return suite("logins that race on one cookie",
                test("a login of anna and Sessions.renew on her cookie at once, " + ROUNDS + " times, with the default"
                        + " limit: she keeps at most 10 working sessions, also after three more logins, and bertil's"
                        + " login over each of them does not see her note", () -> {
                    var sessions = LoginExample.sessions(false, InstantSource.system());
                    try (var server = Support.serve(router(sessions, WebSecurity.DEFAULT_MAXIMUM_SESSIONS))) {
                        var ids = new ArrayList<String>();
                        for (int round = 0; round < ROUNDS; round++) {
                            var id = login(server, ANNA);
                            var replies = atOnce(server, "/login/" + ANNA, "/renew", id);
                            assertEquals(200, replies.get(0).status(), replies.get(0).toString());
                            ids.add(issued(replies.get(0))); // a new session if /renew renewed the id first
                            var renew = replies.get(1);
                            if (renew.status() == 401) {
                                // the login renewed the id before /renew looked it up: anonymous, no cookie
                                assertEquals(List.of(), renew.all("Set-Cookie"));
                            } else {
                                assertEquals(200, renew.status(), renew.toString());
                                ids.add(issued(renew));
                            }
                        }
                        var anna = loggedIn(server, ids, ANNA);
                        assertTrue(anna.size() <= 10, anna.size() + " working sessions of anna");
                        for (int i = 0; i < 3; i++) {
                            ids.add(login(server, ANNA));
                        }
                        anna = loggedIn(server, ids, ANNA);
                        assertEquals(10, anna.size(), "working sessions of anna after three more logins");
                        for (var id : anna) {
                            assertEquals(200, raw(server, "POST", "/note", null, LoginSupport.cookie(id)).status());
                            var bertil = issued(send(server, "POST", "/login/" + BERTIL, id));
                            assertEquals(BERTIL, me(server, bertil));
                            assertEquals("none", send(server, "GET", "/note", bertil).body(), "bertil sees anna's note");
                            assertEquals("401", me(server, id), "anna's session over which bertil logged in has ended");
                        }
                    }
                }),
                test("anna and bertil log in at once with anna's cookie, " + ROUNDS + " times: bertil never sees anna's"
                        + " note", () -> {
                    var sessions = LoginExample.sessions(false, InstantSource.system());
                    // a high limit, so that anna's sessions pile up and each login walks many of them
                    try (var server = Support.serve(router(sessions, 2 * ROUNDS))) {
                        int inherited = 0;
                        for (int round = 0; round < ROUNDS; round++) {
                            var id = login(server, ANNA);
                            assertEquals(200, raw(server, "POST", "/note", null, LoginSupport.cookie(id)).status());
                            var replies = atOnce(server, "/login/" + ANNA, "/login/" + BERTIL, id);
                            assertEquals(200, replies.get(1).status(), replies.get(1).toString());
                            var bertil = issued(replies.get(1));
                            assertEquals(BERTIL, me(server, bertil));
                            if (!send(server, "GET", "/note", bertil).body().equals("none")) {
                                inherited++;
                            }
                        }
                        assertEquals(0, inherited, "rounds in which bertil's session had anna's note");
                    }
                }));
    }
}
