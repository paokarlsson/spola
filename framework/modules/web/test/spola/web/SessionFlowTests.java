package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertNotEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.web.SessionSupport.COUNT;
import static spola.web.SessionSupport.call;
import static spola.web.SessionSupport.client;
import static spola.web.SessionSupport.cookie;
import static spola.web.SessionSupport.issued;
import static spola.web.SessionSupport.send;
import static spola.web.SessionSupport.setCookies;
import static spola.web.SessionSupport.text;
import static spola.web.SessionSupport.thrown;
import static spola.web.SessionSupport.values;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import spola.http.Response;
import spola.test.Suite;

/** Criteria 4 and 5: sessions through HttpClient with a CookieManager, and the lifecycle over a raw socket. */
final class SessionFlowTests {

    private SessionFlowTests() {
    }

    static final String REMOVAL = "SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax";

    static final AttributeKey<String> NAME = AttributeKey.of("name", String.class);

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void putRaw(Session session, AttributeKey key, Object value) {
        session.put(key, value);
    }

    static Suite tests() {
        return suite("session flow",
                suite("through HttpClient and a CookieManager",
                        test("a route that does not touch the session sends no Set-Cookie and creates nothing", () -> {
                            var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                            try (var server = Support.serve(SessionSupport.app(sessions)); var client = client()) {
                                for (int i = 0; i < 3; i++) {
                                    var plain = call(client, server, "GET", "/plain");
                                    assertEquals(200, plain.statusCode());
                                    assertEquals(List.of(), plain.headers().allValues("Set-Cookie"));
                                }
                                assertEquals(0, sessions.size());
                                for (var value : List.of(SessionCookieTests.unknownId(), "not-a-session", "a b",
                                        "\"" + SessionCookieTests.unknownId() + "\"", "=")) {
                                    var reply = send(server, "GET", "/plain", cookie(value));
                                    assertEquals(200, reply.status(), value);
                                    assertEquals(List.of(), setCookies(reply), value);
                                }
                                assertEquals(0, sessions.size(), "a well-formed unknown id and malformed values");
                            }
                        }),
                        test("the first count creates exactly one session, later ones send no Set-Cookie; a second"
                                + " client gets its own", () -> {
                            var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                            try (var server = Support.serve(SessionSupport.app(sessions));
                                    var first = client(); var second = client()) {
                                var one = call(first, server, "POST", "/count");
                                assertEquals("1", one.body());
                                var cookies = one.headers().allValues("Set-Cookie");
                                assertEquals(1, cookies.size(), String.valueOf(cookies));
                                assertTrue(cookies.getFirst().matches("SESSION=[A-Za-z0-9_-]{43}; Path=/; HttpOnly;"
                                        + " SameSite=Lax"), cookies.getFirst());
                                assertEquals(1, sessions.size());
                                for (var expected : List.of("2", "3")) {
                                    var next = call(first, server, "POST", "/count");
                                    assertEquals(expected, next.body());
                                    assertEquals(List.of(), next.headers().allValues("Set-Cookie"));
                                }
                                var other = call(second, server, "POST", "/count");
                                assertEquals("1", other.body());
                                assertEquals(1, other.headers().allValues("Set-Cookie").size());
                                assertNotEquals(cookies.getFirst(), other.headers().allValues("Set-Cookie").getFirst());
                                assertEquals("2", call(second, server, "POST", "/count").body());
                                assertEquals("4", call(first, server, "POST", "/count").body());
                                assertEquals("found:4", call(first, server, "GET", "/find").body());
                                assertEquals("found:2", call(second, server, "GET", "/find").body());
                                assertEquals(2, sessions.size());
                            }
                        }),
                        test("a route that only calls find never creates a session", () -> {
                            var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                            try (var server = Support.serve(SessionSupport.app(sessions)); var client = client()) {
                                for (int i = 0; i < 3; i++) {
                                    var found = call(client, server, "GET", "/find");
                                    assertEquals("none", found.body());
                                    assertEquals(List.of(), found.headers().allValues("Set-Cookie"));
                                }
                                assertEquals(0, sessions.size());
                            }
                        })),
                suite("the lifecycle over a raw socket",
                        test("renew: a new id, the old one stops at once, the attributes stay, size() is unchanged",
                                () -> {
                                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                                    try (var server = Support.serve(SessionSupport.app(sessions))) {
                                        var old = issued(send(server, "POST", "/count"));
                                        assertEquals("2", send(server, "POST", "/count", cookie(old)).body());
                                        assertEquals(1, sessions.size());
                                        var renewed = send(server, "POST", "/renew", cookie(old));
                                        assertEquals("renewed:2", renewed.body());
                                        var id = issued(renewed);
                                        assertNotEquals(old, id);
                                        assertEquals(List.of("SESSION=" + id + "; Path=/; HttpOnly; SameSite=Lax"),
                                                setCookies(renewed));
                                        assertEquals(List.of("no-store"), values(renewed, "Cache-Control"));
                                        assertEquals(1, sessions.size());
                                        var stale = send(server, "GET", "/find", cookie(old));
                                        assertEquals("none", stale.body());
                                        assertEquals(List.of(), setCookies(stale));
                                        assertEquals("found:2", send(server, "GET", "/find", cookie(id)).body());
                                        assertEquals("3", send(server, "POST", "/count", cookie(id)).body());
                                        assertEquals(1, sessions.size());
                                    }
                                }),
                        test("renew without a session creates a new, empty one", () -> {
                            var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                            try (var server = Support.serve(SessionSupport.app(sessions))) {
                                var created = send(server, "POST", "/renew");
                                assertEquals("renewed:0", created.body());
                                var id = issued(created);
                                assertEquals(1, sessions.size());
                                assertEquals("found:0", send(server, "GET", "/find", cookie(id)).body());
                                var unknown = send(server, "POST", "/renew", cookie(SessionCookieTests.unknownId()));
                                assertEquals("renewed:0", unknown.body());
                                issued(unknown);
                                assertEquals(2, sessions.size());
                            }
                        }),
                        test("invalidate: exactly the removal, the old id stops, size() goes down; without a session"
                                + " nothing", () -> {
                            var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                            try (var server = Support.serve(SessionSupport.app(sessions))) {
                                var id = issued(send(server, "POST", "/count"));
                                var kept = issued(send(server, "POST", "/count"));
                                assertEquals(2, sessions.size());
                                var removed = send(server, "POST", "/invalidate", cookie(id));
                                assertEquals(List.of(REMOVAL), setCookies(removed));
                                assertEquals(List.of("no-store"), values(removed, "Cache-Control"));
                                assertEquals(1, sessions.size());
                                assertEquals("none", send(server, "GET", "/find", cookie(id)).body());
                                assertEquals("found:1", send(server, "GET", "/find", cookie(kept)).body());
                                for (var headers : List.of(new String[0], new String[] {cookie(id)},
                                        new String[] {cookie(SessionCookieTests.unknownId())})) {
                                    var nothing = send(server, "POST", "/invalidate", headers);
                                    assertEquals("invalidated", nothing.body());
                                    assertEquals(List.of(), setCookies(nothing));
                                    assertEquals(List.of(), values(nothing, "Cache-Control"));
                                }
                                assertEquals(1, sessions.size());
                            }
                        }),
                        test("the removal has Secure, Path and Domain from the cookie function", () -> {
                            var sessions = Sessions.inMemory(SessionOptions.DEFAULT.withCookie(c -> c.secure(true)
                                    .path("/app").domain("example.com")));
                            try (var server = Support.serve(SessionSupport.app(sessions))) {
                                var created = send(server, "POST", "/count");
                                var id = issued(created);
                                assertEquals(List.of("SESSION=" + id + "; Path=/app; Domain=example.com; Secure; HttpOnly;"
                                        + " SameSite=Lax"), setCookies(created));
                                var removed = send(server, "POST", "/invalidate", cookie(id));
                                assertEquals(List.of("SESSION=; Path=/app; Domain=example.com; Max-Age=0; Secure; HttpOnly;"
                                        + " SameSite=Lax"), setCookies(removed));
                            }
                        }),
                        test("create and invalidate in one request: only the removal; invalidate and then create:"
                                + " only the new id", () -> {
                            var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                            var router = SessionSupport.routes(SessionSupport.router().filter(sessions.filter()))
                                    .post("/create-invalidate", r -> {
                                        Sessions.getOrCreate(r).put(COUNT, 7);
                                        Sessions.invalidate(r);
                                        return Response.text(200, "gone:" + Sessions.find(r).isPresent());
                                    })
                                    .post("/invalidate-create", r -> {
                                        Sessions.invalidate(r);
                                        var session = Sessions.getOrCreate(r);
                                        session.put(COUNT, session.get(COUNT).orElse(100));
                                        return Response.text(200, "new");
                                    })
                                    .post("/create-renew-invalidate", r -> {
                                        Sessions.getOrCreate(r);
                                        Sessions.renew(r);
                                        Sessions.invalidate(r);
                                        return Response.text(200, "gone");
                                    })
                                    .build();
                            try (var server = Support.serve(router)) {
                                var gone = send(server, "POST", "/create-invalidate");
                                assertEquals("gone:false", gone.body());
                                assertEquals(List.of(REMOVAL), setCookies(gone));
                                assertEquals(0, sessions.size());
                                var old = issued(send(server, "POST", "/count"));
                                var replaced = send(server, "POST", "/invalidate-create", cookie(old));
                                var id = issued(replaced);
                                assertNotEquals(old, id);
                                assertEquals(1, sessions.size());
                                assertEquals("none", send(server, "GET", "/find", cookie(old)).body());
                                assertEquals("found:100", send(server, "GET", "/find", cookie(id)).body(),
                                        "the new session is empty: the old attributes are gone");
                                var all = send(server, "POST", "/create-renew-invalidate");
                                assertEquals(List.of(REMOVAL), setCookies(all));
                                assertEquals(1, sessions.size());
                            }
                        }),
                        test("a Session after invalidate: get is empty, remove does nothing, put throws", () -> {
                            var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                            var held = new AtomicReference<Session>();
                            var router = SessionSupport.routes(SessionSupport.router().filter(sessions.filter()))
                                    .post("/same-request", r -> {
                                        var session = Sessions.getOrCreate(r);
                                        session.put(NAME, "Ada");
                                        Sessions.invalidate(r);
                                        var e = thrown(assertThrows(IllegalStateException.class,
                                                () -> session.put(NAME, "Bob")));
                                        session.remove(NAME);
                                        return Response.text(200, session.get(NAME) + " " + text(session) + " "
                                                + e.getClass().getSimpleName());
                                    })
                                    .post("/hold", r -> {
                                        var session = Sessions.getOrCreate(r);
                                        session.put(NAME, "Ada");
                                        held.set(session);
                                        return Response.text(200, "held");
                                    })
                                    .build();
                            try (var server = Support.serve(router)) {
                                var same = send(server, "POST", "/same-request");
                                assertEquals("Optional.empty Session[ended] IllegalStateException", same.body());
                                assertEquals(List.of(REMOVAL), setCookies(same));
                                var id = issued(send(server, "POST", "/hold"));
                                var session = held.get();
                                assertEquals(Optional.of("Ada"), session.get(NAME));
                                assertEquals("Session[1 attribute]", text(session));
                                session.put(NAME, "Bea");
                                assertEquals(Optional.of("Bea"), session.get(NAME), "the object works outside its request");
                                send(server, "POST", "/invalidate", cookie(id));
                                assertEquals(Optional.empty(), session.get(NAME), "invalidated by another request");
                                session.remove(NAME);
                                var e = thrown(assertThrows(IllegalStateException.class, () -> session.put(NAME, "Cy")));
                                assertTrue(e.getMessage().contains("invalidated"), e.getMessage());
                                assertEquals("Session[ended]", text(session));
                                assertEquals(0, sessions.size());
                            }
                        }),
                        test("a request with the old id loses the session when another request renews it", () -> {
                            var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                            var held = new AtomicReference<Session>();
                            var router = SessionSupport.routes(SessionSupport.router().filter(sessions.filter()))
                                    .get("/hold", r -> {
                                        held.set(Sessions.find(r).orElseThrow());
                                        return Response.text(200, "held");
                                    })
                                    .post("/renew-keep", r -> {
                                        var before = Sessions.getOrCreate(r);
                                        var after = Sessions.renew(r);
                                        before.put(NAME, "kept");
                                        return Response.text(200, (before == after) + " " + after.get(NAME).orElse("-"));
                                    })
                                    .build();
                            try (var server = Support.serve(router)) {
                                var id = issued(send(server, "POST", "/count"));
                                send(server, "GET", "/hold", cookie(id));
                                var stale = held.get();
                                assertEquals(Optional.of(1), stale.get(COUNT));
                                var renewed = issued(send(server, "POST", "/renew", cookie(id)));
                                assertEquals(Optional.empty(), stale.get(COUNT), "the old id's view ended");
                                thrown(assertThrows(IllegalStateException.class, () -> stale.put(COUNT, 99)));
                                assertEquals("found:1", send(server, "GET", "/find", cookie(renewed)).body());
                                var same = send(server, "POST", "/renew-keep", cookie(renewed));
                                assertEquals("true kept", same.body(), "renew in the same request keeps the object");
                                assertFalse(setCookies(same).isEmpty());
                            }
                        }),
                        test("put rejects a value of the wrong type (through a raw key) and null", () -> {
                            var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                            var held = new AtomicReference<Session>();
                            var router = SessionSupport.router().filter(sessions.filter())
                                    .post("/hold", r -> {
                                        held.set(Sessions.getOrCreate(r));
                                        return Response.text(200, "held");
                                    })
                                    .build();
                            try (var server = Support.serve(router)) {
                                issued(send(server, "POST", "/hold"));
                                var session = held.get();
                                session.put(COUNT, 5);
                                var wrongType = thrown(assertThrows(ClassCastException.class,
                                        () -> putRaw(session, COUNT, "five")));
                                assertTrue(wrongType.getMessage().contains("java.lang.String"), wrongType.getMessage());
                                assertFalse(wrongType.getMessage().contains("five"), "the value is not echoed");
                                thrown(assertThrows(NullPointerException.class, () -> session.put(COUNT, null)));
                                thrown(assertThrows(NullPointerException.class, () -> session.put(null, 1)));
                                thrown(assertThrows(NullPointerException.class, () -> session.get(null)));
                                thrown(assertThrows(NullPointerException.class, () -> session.remove(null)));
                                assertEquals(Optional.of(5), session.get(COUNT), "nothing changed");
                                session.remove(COUNT);
                                assertEquals(Optional.empty(), session.get(COUNT));
                            }
                        })));
    }
}
