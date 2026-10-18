package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertNotEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.web.SessionSupport.cookie;
import static spola.web.SessionSupport.issued;
import static spola.web.SessionSupport.send;
import static spola.web.SessionSupport.setCookies;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import spola.http.HttpServer;
import spola.http.Response;
import spola.test.Suite;

/**
 * Uppdrag 15a in {@code spola.web}: {@link Sessions#renewFor} keeps at most a number
 * of sessions per user by ending the least recently used, gives another user a new, empty
 * session, and keeps an index from names to sessions that is empty once the sessions are gone.
 */
final class SessionUserTests {

    private SessionUserTests() {
    }

    /** The name a login stored in the session. */
    static final AttributeKey<String> NAME = AttributeKey.of("name", String.class);

    /**
     * {@link SessionSupport#routes} plus {@code POST /login/{user}}, which calls
     * {@code renewFor(request, user, limit)} and stores the name, {@code GET /whoami}, which shows
     * it, {@code POST /logout} and {@code POST /login-and-fail/{user}}, which logs in and then reads
     * a broken body (a {@code RequestBodyException} passes the filter, which abandons the request).
     */
    static Router app(Sessions sessions, int limit) {
        return SessionSupport.routes(SessionSupport.router().filter(sessions.filter()))
                .post("/login/{user}", r -> {
                    var user = r.pathParam("user");
                    Sessions.renewFor(r, user, limit).put(NAME, user);
                    return Response.text(200, "hello " + user);
                })
                .get("/whoami", r -> Response.text(200, Sessions.find(r).flatMap(s -> s.get(NAME)).orElse("nobody")))
                .post("/logout", r -> {
                    Sessions.invalidate(r);
                    return Response.text(200, "bye");
                })
                .post("/login-and-fail/{user}", r -> {
                    Sessions.renewFor(r, r.pathParam("user"), limit);
                    r.bytes(); // malformed chunked framing
                    return Response.text(200, "never");
                })
                .build();
    }

    /** {@code POST /login-and-fail/{user}} with a broken chunked body, and the session cookie {@code id} unless {@code null}. */
    static Support.Reply failingLogin(HttpServer server, String user, String id) throws Exception {
        var request = "POST /login-and-fail/" + user + " HTTP/1.1\r\nHost: localhost\r\n"
                + (id == null ? "" : cookie(id) + "\r\n") + "Transfer-Encoding: chunked\r\n\r\nZZ\r\n";
        var reply = Support.raw(server.port(), request.getBytes(StandardCharsets.US_ASCII), false);
        SessionSupport.record(reply);
        return reply;
    }

    static String whoami(HttpServer server, String id) throws Exception {
        return send(server, "GET", "/whoami", cookie(id)).body();
    }

    /** {@code POST /login/{user}} without a cookie: the new id. */
    static String login(HttpServer server, String user) throws Exception {
        var reply = send(server, "POST", "/login/" + user);
        assertEquals(200, reply.status(), reply.toString());
        return issued(reply);
    }

    static Suite tests() {
        return suite("sessions per user (15a)",
                test("logins of one user beyond the limit end the user's least recently used sessions, never the"
                        + " new one; the index counts the user's sessions", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    try (var server = Support.serve(app(sessions, 3))) {
                        var ids = new ArrayList<String>();
                        for (int i = 1; i <= 6; i++) {
                            ids.add(login(server, "anna"));
                            assertEquals(Math.min(i, 3), sessions.size(), "after login " + i);
                            assertEquals(Math.min(i, 3), sessions.sessionsOf("anna"));
                        }
                        for (int i = 0; i < 3; i++) {
                            assertEquals("nobody", whoami(server, ids.get(i)), "login " + (i + 1) + " ended");
                        }
                        for (int i = 3; i < 6; i++) {
                            assertEquals("anna", whoami(server, ids.get(i)));
                        }
                        // the least recently used, not the oldest: after the 5th, the 6th and then the 4th
                        // are used, the 5th is the one used longest ago, though the 4th is older
                        assertEquals("anna", whoami(server, ids.get(4)));
                        assertEquals("anna", whoami(server, ids.get(5)));
                        assertEquals("anna", whoami(server, ids.get(3)));
                        var seventh = login(server, "anna");
                        assertEquals("nobody", whoami(server, ids.get(4)), "the 5th was used longest ago");
                        assertEquals("anna", whoami(server, ids.get(3)));
                        assertEquals("anna", whoami(server, ids.get(5)));
                        assertEquals("anna", whoami(server, seventh));
                        assertEquals(3, sessions.size());
                        // another user is not affected and has a limit of their own
                        var bertil = login(server, "bertil");
                        assertEquals("bertil", whoami(server, bertil));
                        assertEquals(4, sessions.size());
                        assertEquals(2, sessions.users());
                        assertEquals(1, sessions.sessionsOf("bertil"));
                        assertEquals(0, sessions.sessionsOf("Anna"), "names are compared exactly");
                    }
                }),
                test("a login with the user's own cookie renews that session: same count, values kept, the old id"
                        + " dead", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    try (var server = Support.serve(app(sessions, 2))) {
                        var a = login(server, "anna");
                        var b = login(server, "anna");
                        assertEquals("1", send(server, "POST", "/count", cookie(a)).body());
                        var again = send(server, "POST", "/login/anna", cookie(a));
                        var a2 = issued(again);
                        assertNotEquals(a, a2);
                        assertEquals(2, sessions.size(), "renewed, not a new session");
                        assertEquals(2, sessions.sessionsOf("anna"));
                        assertEquals("found:1", send(server, "GET", "/find", cookie(a2)).body(), "the value stays");
                        assertEquals("none", send(server, "GET", "/find", cookie(a)).body());
                        assertEquals("anna", whoami(server, b), "the other session was not ended");
                    }
                }),
                test("a session of another user ends and the login gets a new, empty one; an anonymous session keeps"
                        + " its values", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    try (var server = Support.serve(app(sessions, 10))) {
                        var anonymous = issued(send(server, "POST", "/count"));
                        var anna = issued(send(server, "POST", "/login/anna", cookie(anonymous)));
                        assertEquals("found:1", send(server, "GET", "/find", cookie(anna)).body(),
                                "an anonymous session is renewed with its values");
                        assertEquals("2", send(server, "POST", "/count", cookie(anna)).body());
                        var bertil = send(server, "POST", "/login/bertil", cookie(anna));
                        assertEquals(1, setCookies(bertil).size(), "only the new id, no removal as well");
                        var bertilId = issued(bertil);
                        assertNotEquals(anna, bertilId);
                        assertEquals("found:0", send(server, "GET", "/find", cookie(bertilId)).body(),
                                "bertil does not see anna's count");
                        assertEquals("bertil", whoami(server, bertilId));
                        assertEquals("none", send(server, "GET", "/find", cookie(anna)).body(), "anna's id is dead");
                        assertEquals(1, sessions.size());
                        assertEquals(0, sessions.sessionsOf("anna"));
                        assertEquals(1, sessions.sessionsOf("bertil"));
                        // the same user again keeps the values
                        assertEquals("1", send(server, "POST", "/count", cookie(bertilId)).body());
                        var again = issued(send(server, "POST", "/login/bertil", cookie(bertilId)));
                        assertEquals("found:1", send(server, "GET", "/find", cookie(again)).body());
                    }
                }),
                test("a login with an anonymous session (a cart) when the user is at the limit keeps the cart's values"
                        + " and ends the user's least recently used session", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    try (var server = Support.serve(app(sessions, 2))) {
                        var a1 = login(server, "anna");
                        var a2 = login(server, "anna");
                        assertEquals("anna", whoami(server, a1)); // a2 is now the least recently used
                        var cart = issued(send(server, "POST", "/count"));
                        var renewed = issued(send(server, "POST", "/login/anna", cookie(cart)));
                        assertEquals("found:1", send(server, "GET", "/find", cookie(renewed)).body());
                        assertEquals(2, sessions.sessionsOf("anna"));
                        assertEquals(2, sessions.size());
                        assertEquals("nobody", whoami(server, a2), "the least recently used ended");
                        assertEquals("anna", whoami(server, a1));
                        assertEquals("anna", whoami(server, renewed));
                    }
                }),
                test("another user's session ends before the new one is created, so the switch works at maxSessions;"
                        + " a user at the limit can also log in there", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT.withMaxSessions(2));
                    try (var server = Support.serve(app(sessions, 2))) {
                        var a1 = login(server, "anna");
                        var a2 = login(server, "anna");
                        assertEquals(2, sessions.size());
                        assertEquals(503, send(server, "POST", "/count").status(), "maxSessions is reached");
                        var a3 = login(server, "anna");
                        assertEquals("nobody", whoami(server, a1), "anna's oldest made room for her new login");
                        assertEquals("anna", whoami(server, a2));
                        assertEquals("anna", whoami(server, a3));
                        var bertil = issued(send(server, "POST", "/login/bertil", cookie(a2)));
                        assertEquals("bertil", whoami(server, bertil));
                        assertEquals(2, sessions.size());
                        var full = send(server, "POST", "/login/cecilia");
                        assertEquals(503, full.status(), "cecilia has no session to give up");
                        assertEquals(List.of(), setCookies(full));
                    }
                }),
                test("the index is empty once every session is gone: expired and looked up, logged out, removed by"
                        + " the cleanup, abandoned, or ended by the limit", () -> {
                    var clock = new SessionSupport.TestClock();
                    var idle = Duration.ofSeconds(10); // shorter than the minute between cleanups
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT.withIdleTimeout(idle), clock);
                    try (var server = Support.serve(app(sessions, 2))) {
                        var expired = login(server, "anna");
                        var loggedOut = login(server, "bertil");
                        var swept = login(server, "cecilia");
                        login(server, "david");
                        login(server, "david");
                        login(server, "david"); // the first of david's ended by the limit
                        assertEquals(4, sessions.users());
                        assertEquals(2, sessions.sessionsOf("david"));
                        assertEquals(400, failingLogin(server, "erik", null).status());
                        assertEquals(0, sessions.sessionsOf("erik"), "the abandoned login's session left the index");
                        assertEquals(5, sessions.size(), "and its session is gone");
                        assertEquals("bye", send(server, "POST", "/logout", cookie(loggedOut)).body());
                        assertEquals(0, sessions.sessionsOf("bertil"));
                        assertEquals(3, sessions.users());
                        // a re-login with a cookie that then fails: the renewed session is abandoned
                        var erik = login(server, "erik");
                        assertEquals(1, sessions.sessionsOf("erik"));
                        assertEquals(400, failingLogin(server, "erik", erik).status());
                        assertEquals(0, sessions.sessionsOf("erik"));
                        assertEquals("nobody", whoami(server, erik));
                        clock.advance(idle);
                        assertEquals("nobody", whoami(server, expired), "expired, removed when looked up");
                        assertEquals(0, sessions.sessionsOf("anna"));
                        assertEquals(2, sessions.users(), "cecilia and david: expired but not yet removed");
                        clock.advance(Sessions.SWEEP_INTERVAL);
                        send(server, "GET", "/plain"); // the cleanup once a minute
                        assertEquals(0, sessions.size());
                        assertEquals(0, sessions.users(), "nothing left in the index");
                        assertEquals("nobody", whoami(server, swept));
                    }
                }),
                test("bad arguments, a request without the filter and a call after the response", () -> {
                    var saved = new AtomicReference<WebRequest>();
                    var failures = new HashMap<String, String>();
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    var router = SessionSupport.router().filter(sessions.filter())
                            .post("/bad", r -> {
                                saved.set(r);
                                for (var call : Map.<String, Runnable>of(
                                        "null user", () -> Sessions.renewFor(r, null, 1),
                                        "empty user", () -> Sessions.renewFor(r, "", 1),
                                        "limit 0", () -> Sessions.renewFor(r, "anna", 0)).entrySet()) {
                                    try {
                                        call.getValue().run();
                                        failures.put(call.getKey(), "nothing");
                                    } catch (RuntimeException e) {
                                        failures.put(call.getKey(), SessionSupport.thrown(e).getClass().getSimpleName()
                                                + ": " + e.getMessage());
                                    }
                                }
                                return Response.text(200, "checked");
                            })
                            .build();
                    try (var server = Support.serve(router)) {
                        var reply = send(server, "POST", "/bad");
                        assertEquals("checked", reply.body());
                        assertEquals(List.of(), setCookies(reply), "nothing was created");
                        assertEquals(Map.of("null user", "NullPointerException: user",
                                "empty user", "IllegalArgumentException: user must not be empty",
                                "limit 0", "IllegalArgumentException: maxPerUser must be at least 1, was 0"), failures);
                        var late = assertThrows(IllegalStateException.class,
                                () -> Sessions.renewFor(saved.get(), "anna", 1));
                        assertTrue(SessionSupport.thrown(late).getMessage().startsWith("Sessions.renewFor was called after"
                                + " the response"), late.getMessage());
                        assertEquals(0, sessions.size());
                    }
                    var without = SessionSupport.router().post("/x", r -> {
                        Sessions.renewFor(r, "anna", 1);
                        return Response.text(200, "never");
                    }).build();
                    try (var server = Support.serve(without)) {
                        assertEquals(500, send(server, "POST", "/x").status());
                    }
                }),
                test("200 virtual threads log in five users at once with a limit of 3: each user ends with exactly 3"
                        + " sessions and size() is 15", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    var names = List.of("anna", "bertil", "cecilia", "david", "erik");
                    var start = new CountDownLatch(1);
                    try (var server = Support.serve(app(sessions, 3));
                            var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        var futures = new ArrayList<Future<String>>();
                        for (int t = 0; t < 200; t++) {
                            var name = names.get(t % names.size());
                            futures.add(executor.submit(() -> {
                                start.await();
                                var reply = send(server, "POST", "/login/" + name);
                                return reply.status() + " " + reply.body() + " " + setCookies(reply).size();
                            }));
                        }
                        start.countDown();
                        for (int t = 0; t < 200; t++) {
                            assertEquals("200 hello " + names.get(t % names.size()) + " 1",
                                    futures.get(t).get(60, TimeUnit.SECONDS));
                        }
                        for (var name : names) {
                            assertEquals(3, sessions.sessionsOf(name), name);
                        }
                        assertEquals(15, sessions.size());
                        assertEquals(5, sessions.users());
                    }
                }),
                test("50 logins of one user at once with a limit of 1 (a double-click, many times over): no login"
                        + " fails, and one session is left", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    var start = new CountDownLatch(1);
                    try (var server = Support.serve(app(sessions, 1));
                            var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        var shared = login(server, "anna");
                        var futures = new ArrayList<Future<String>>();
                        for (int t = 0; t < 50; t++) {
                            int thread = t;
                            futures.add(executor.submit(() -> {
                                start.await();
                                // half of them with the cookie of the session they all share
                                var reply = thread % 2 == 0 ? send(server, "POST", "/login/anna", cookie(shared))
                                        : send(server, "POST", "/login/anna");
                                return reply.status() + " " + reply.body();
                            }));
                        }
                        start.countDown();
                        for (var future : futures) {
                            assertEquals("200 hello anna", future.get(60, TimeUnit.SECONDS));
                        }
                        assertEquals(1, sessions.size());
                        assertEquals(1, sessions.sessionsOf("anna"));
                        assertEquals("none", send(server, "GET", "/find", cookie(shared)).body(),
                                "the shared id was renewed or ended");
                    }
                }));
    }
}
