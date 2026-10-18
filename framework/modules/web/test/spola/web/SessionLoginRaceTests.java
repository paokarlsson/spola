package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.web.SessionSupport.cookie;
import static spola.web.SessionSupport.idOf;
import static spola.web.SessionSupport.issued;
import static spola.web.SessionSupport.send;
import static spola.web.SessionSupport.setCookies;
import static spola.web.SessionUserTests.app;
import static spola.web.SessionUserTests.login;
import static spola.web.SessionUserTests.whoami;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import spola.http.HttpServer;
import spola.http.Response;
import spola.test.Suite;

/**
 * {@link Sessions#renewFor} under requests that run at the same time (15a): a login that is
 * still being handled while another login of the same user finishes, a login of the session's
 * own user against {@link Sessions#renew} on one cookie, two users' logins with one cookie, and
 * many clients that log in, renew, log out and switch users at random. Whatever the order, a
 * login being handled is never ended by another, once the requests are done every session that
 * holds a user's login is counted among the user's sessions, so the user has at most the limit,
 * and a login as another user never keeps the previous user's values.
 */
final class SessionLoginRaceTests {

    private SessionLoginRaceTests() {
    }

    /** Rounds of the two races; each round is one try at hitting the window between two requests. */
    static final int ROUNDS = 400;

    /** Two requests sent at the same moment; both replies. */
    static List<Support.Reply> atOnce(HttpServer server, String first, String second, String id) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> {
                start.await();
                return send(server, "POST", first, cookie(id));
            });
            var b = executor.submit(() -> {
                start.await();
                return send(server, "POST", second, cookie(id));
            });
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    /** The ids among {@code ids} whose session holds {@code user}'s login. */
    static List<String> loggedIn(HttpServer server, List<String> ids, String user) throws Exception {
        var working = new ArrayList<String>();
        for (var id : ids) {
            if (whoami(server, id).equals(user)) {
                working.add(id);
            }
        }
        return working;
    }

    static Suite tests() {
        return suite("logins at the same time (15a)",
                test("with a limit of 1, a login that is still being handled is not ended by a login of the same user"
                        + " that finishes first; once it is done, the limit ends the other one", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    var inside = new CountDownLatch(1);
                    var proceed = new CountDownLatch(1);
                    var router = SessionSupport.routes(SessionSupport.router().filter(sessions.filter()))
                            .post("/login/{user}", r -> {
                                Sessions.renewFor(r, r.pathParam("user"), 1).put(SessionUserTests.NAME, r.pathParam("user"));
                                return Response.text(200, "hello");
                            })
                            .post("/slow-login/{user}", r -> {
                                var session = Sessions.renewFor(r, r.pathParam("user"), 1);
                                inside.countDown();
                                proceed.await(); // the other login runs to its end meanwhile
                                session.put(SessionUserTests.NAME, r.pathParam("user"));
                                return Response.text(200, "hello slowly");
                            })
                            .get("/whoami", r -> Response.text(200, Sessions.find(r)
                                    .flatMap(s -> s.get(SessionUserTests.NAME)).orElse("nobody")))
                            .build();
                    try (var server = Support.serve(router); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        var first = issued(send(server, "POST", "/login/anna"));
                        var slow = executor.submit(() -> send(server, "POST", "/slow-login/anna"));
                        String quick;
                        try {
                            assertTrue(inside.await(20, TimeUnit.SECONDS), "the slow login is inside its handler");
                            assertEquals("nobody", whoami(server, first), "the slow login made room: limit 1");
                            quick = issued(send(server, "POST", "/login/anna"));
                            assertEquals("anna", whoami(server, quick));
                            assertEquals(2, sessions.sessionsOf("anna"), "the slow login's session is held, not ended");
                        } finally {
                            proceed.countDown(); // also when an assertion failed: the handler must not wait forever
                        }
                        var reply = slow.get(20, TimeUnit.SECONDS);
                        assertEquals("200 hello slowly", reply.status() + " " + reply.body(), "its put found its session");
                        assertEquals("anna", whoami(server, issued(reply)));
                        assertEquals("nobody", whoami(server, quick), "the limit applied when the slow login was done");
                        assertEquals(1, sessions.sessionsOf("anna"));
                        assertEquals(1, sessions.size());
                    }
                }),
                test("a login of anna and Sessions.renew on her cookie at once, " + ROUNDS + " times: she keeps at most"
                        + " the limit of working sessions, the index counts exactly those, and bertil's login over each"
                        + " gets an empty session", () -> {
                    int limit = 3;
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    try (var server = Support.serve(app(sessions, limit))) {
                        var ids = new ArrayList<String>();
                        for (int round = 0; round < ROUNDS; round++) {
                            var id = login(server, "anna");
                            for (var reply : atOnce(server, "/login/anna", "/renew", id)) {
                                assertEquals(200, reply.status(), reply.toString());
                                ids.add(issued(reply)); // the loser of the two gets a new session
                            }
                        }
                        var anna = loggedIn(server, ids, "anna");
                        assertTrue(anna.size() <= limit, anna.size() + " working sessions of anna, limit " + limit);
                        assertEquals(anna.size(), sessions.sessionsOf("anna"), "every session with her login is counted");
                        for (var id : anna) {
                            assertEquals("1", send(server, "POST", "/count", cookie(id)).body());
                            var bertil = issued(send(server, "POST", "/login/bertil", cookie(id)));
                            assertEquals("found:0", send(server, "GET", "/find", cookie(bertil)).body(),
                                    "bertil does not see anna's value");
                            assertEquals("bertil", whoami(server, bertil));
                        }
                        assertEquals(0, sessions.sessionsOf("anna"));
                    }
                }),
                test("anna and bertil log in at once with anna's cookie, " + ROUNDS + " times: bertil never gets anna's"
                        + " values", () -> {
                    // a high limit, so that anna's sessions pile up and each login walks many of them
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    try (var server = Support.serve(app(sessions, 2 * ROUNDS))) {
                        int inherited = 0;
                        for (int round = 0; round < ROUNDS; round++) {
                            var id = login(server, "anna");
                            assertEquals("1", send(server, "POST", "/count", cookie(id)).body());
                            var replies = atOnce(server, "/login/anna", "/login/bertil", id);
                            assertEquals(200, replies.get(1).status(), replies.get(1).toString());
                            var bertil = issued(replies.get(1));
                            assertEquals("bertil", whoami(server, bertil));
                            if (!send(server, "GET", "/find", cookie(bertil)).body().equals("found:0")) {
                                inherited++;
                            }
                        }
                        assertEquals(0, inherited, "rounds in which bertil's session had anna's value");
                    }
                }),
                test("100 clients log in as three users, renew, log out and switch users at random: afterwards each"
                        + " user's working sessions are those the index counts, at most the limit, and size() is the"
                        + " number of live sessions", () -> {
                    int limit = 2;
                    var names = List.of("anna", "bertil", "cecilia");
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    var issuedIds = ConcurrentHashMap.<String>newKeySet();
                    var start = new CountDownLatch(1);
                    try (var server = Support.serve(app(sessions, limit));
                            var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        var clients = new ArrayList<Future<String>>();
                        for (int c = 0; c < 100; c++) {
                            var random = new Random(c);
                            clients.add(executor.submit(() -> {
                                start.await();
                                return client(server, random, names, issuedIds);
                            }));
                        }
                        start.countDown();
                        for (var client : clients) {
                            assertEquals("ok", client.get(60, TimeUnit.SECONDS));
                        }
                        var working = new HashMap<String, Integer>();
                        int live = 0;
                        for (var id : issuedIds) {
                            if (send(server, "GET", "/find", cookie(id)).body().equals("none")) {
                                continue;
                            }
                            live++;
                            working.merge(whoami(server, id), 1, Integer::sum);
                        }
                        assertEquals(live, sessions.size(), "the live sessions are all in memory, nothing else");
                        int users = 0;
                        for (var name : names) {
                            int n = working.getOrDefault(name, 0);
                            assertTrue(n <= limit, name + " has " + n + " working sessions, limit " + limit);
                            assertEquals(n, sessions.sessionsOf(name), name + ": the index counts exactly those");
                            users += n > 0 ? 1 : 0;
                        }
                        assertEquals(users, sessions.users());
                    }
                }));
    }

    /**
     * One client with its own cookie: 20 requests chosen at random (a login as one of
     * {@code names}, {@code /renew}, {@code /logout}, {@code /count}), keeping the cookie the
     * replies set; every id it gets goes to {@code issuedIds}.
     */
    static String client(HttpServer server, Random random, List<String> names, Set<String> issuedIds)
            throws Exception {
        String id = null;
        for (int i = 0; i < 20; i++) {
            var target = switch (random.nextInt(5)) {
                case 0, 1 -> "/login/" + names.get(random.nextInt(names.size()));
                case 2 -> "/renew";
                case 3 -> "/logout";
                default -> "/count";
            };
            var reply = id == null ? send(server, "POST", target) : send(server, "POST", target, cookie(id));
            if (reply.status() != 200) {
                return target + " gave " + reply.status();
            }
            for (var setCookie : setCookies(reply)) {
                var value = idOf(setCookie);
                id = value.isEmpty() ? null : value;
                if (id != null) {
                    issuedIds.add(id);
                }
            }
        }
        return "ok";
    }
}
