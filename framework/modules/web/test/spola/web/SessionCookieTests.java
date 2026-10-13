package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertNotEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.web.SessionSupport.COUNT;
import static spola.web.SessionSupport.ID;
import static spola.web.SessionSupport.cookie;
import static spola.web.SessionSupport.issued;
import static spola.web.SessionSupport.send;
import static spola.web.SessionSupport.setCookies;
import static spola.web.SessionSupport.values;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import spola.http.HttpServer;
import spola.http.Response;
import spola.test.Suite;

/** Criteria 3 and 8: the session cookie out (the need's use case) and constructed cookie values in. */
final class SessionCookieTests {

    private SessionCookieTests() {
    }

    /**
     * The need's use case, with a local {@code boolean} instead of
     * {@code config.booleanValue("cookie.secure")}.
     */
    static Sessions needsUseCase(boolean secure) {
        return Sessions.inMemory(SessionOptions.DEFAULT
                .withIdleTimeout(Duration.ofMinutes(30))
                .withCookie(cookie -> cookie.secure(secure)));   // HttpOnly, SameSite=Lax
    }

    /**
     * A way to configure sessions and the attributes its cookie must get.
     *
     * @param name       what the case shows
     * @param sessions   makes the sessions
     * @param attributes the {@code Set-Cookie} after {@code SESSION=<id>}
     */
    record Setting(String name, Supplier<Sessions> sessions, String attributes) {
        @Override
        public String toString() {
            return name;
        }
    }

    static final List<Setting> SETTINGS = List.of(
            new Setting("the need's use case with secure = true", () -> needsUseCase(true),
                    "; Path=/; Secure; HttpOnly; SameSite=Lax"),
            new Setting("the need's use case with secure = false", () -> needsUseCase(false),
                    "; Path=/; HttpOnly; SameSite=Lax"),
            new Setting("SessionOptions.DEFAULT", () -> Sessions.inMemory(SessionOptions.DEFAULT),
                    "; Path=/; HttpOnly; SameSite=Lax"));

    /**
     * A constructed value of the cookie {@code SESSION}.
     *
     * @param name  what the case shows
     * @param value the value, made from the fixture's ids
     */
    record Value(String name, Function<Fixture, String> value) {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Ids made in {@code beforeAll}: a live one, an invalidated one, an expired one, one that
     * {@code renew} replaced, and one from another {@code Sessions}.
     */
    record Fixture(HttpServer server, Sessions sessions, String live, String invalidated, String expired,
                   String replaced, String other) {
    }

    static String unknownId() {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static final List<Value> VALUES = List.of(
            new Value("empty", f -> ""),
            new Value("42 characters (a valid id without its last one)", f -> f.live().substring(0, 42)),
            new Value("44 characters", f -> "B".repeat(44)),
            new Value("43 characters with + from standard Base64", f -> "+" + f.live().substring(1)),
            new Value("43 characters with / from standard Base64", f -> "/" + f.live().substring(1)),
            new Value("43 characters ending with = padding", f -> f.live().substring(0, 42) + "="),
            new Value("a valid id in quotes", f -> "\"" + f.live() + "\""),
            new Value("a valid id with one more character", f -> f.live() + "A"),
            new Value("a well-formed unknown id", f -> unknownId()),
            new Value("an invalidated id", Fixture::invalidated),
            new Value("an expired id", Fixture::expired),
            new Value("an id that renew replaced", Fixture::replaced),
            new Value("an id from another Sessions", Fixture::other),
            new Value("a valid id with its last character changed",
                    f -> f.live().substring(0, 42) + (f.live().charAt(42) == 'A' ? 'B' : 'A')),
            new Value("a valid id with a space inside", f -> f.live().substring(0, 20) + " " + f.live().substring(20)),
            new Value("a valid id percent-encoded in part", f -> "%" + Integer.toHexString(f.live().charAt(0))
                    + f.live().substring(1)),
            new Value("a valid id, a comma and the id again", f -> f.live() + "," + f.live()),
            new Value("43 dots", f -> ".".repeat(43)),
            new Value("43 non-ASCII characters", f -> "å".repeat(43)),
            new Value("4 000 characters", f -> "A".repeat(4_000)));

    static Suite tests() {
        var fixture = new AtomicReference<Fixture>();
        var otherServer = new AtomicReference<HttpServer>();
        return suite("session cookies",
                parameterized("the session cookie out (the need's use case)", SETTINGS, setting -> {
                    var sessions = setting.sessions().get();
                    var router = SessionSupport.routes(SessionSupport.router().filter(sessions.filter()))
                            .post("/cached", r -> {
                                Sessions.getOrCreate(r).put(COUNT, 1);
                                return Response.text(200, "cached").withHeader("Cache-Control", "private, max-age=60");
                            })
                            .build();
                    try (var server = Support.serve(router)) {
                        var created = send(server, "POST", "/count");
                        assertEquals(200, created.status(), created.toString());
                        var id = issued(created);
                        assertTrue(ID.matcher(id).matches(), id);
                        assertEquals(List.of("SESSION=" + id + setting.attributes()), setCookies(created));
                        assertEquals(List.of("no-store"), values(created, "Cache-Control"));
                        var again = send(server, "POST", "/count", cookie(id));
                        assertEquals("2", again.body());
                        assertEquals(List.of(), setCookies(again), "no Set-Cookie for a session that exists");
                        assertEquals(List.of(), values(again, "Cache-Control"), "Cache-Control only with the cookie");
                        var cached = send(server, "POST", "/cached");
                        assertEquals(1, setCookies(cached).size(), cached.toString());
                        assertEquals(List.of("private, max-age=60"), values(cached, "Cache-Control"),
                                "the handler's Cache-Control stays");
                        assertEquals(2, sessions.size());
                    }
                }),
                test("withCookieName(\"__Host-SESSION\") with secure(true) works", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT.withCookieName("__Host-SESSION")
                            .withCookie(cookie -> cookie.secure(true)));
                    try (var server = Support.serve(SessionSupport.app(sessions))) {
                        var created = send(server, "POST", "/count");
                        var id = issued(created);
                        assertEquals(List.of("__Host-SESSION=" + id + "; Path=/; Secure; HttpOnly; SameSite=Lax"),
                                setCookies(created));
                        assertEquals("found:1", send(server, "GET", "/find", "Cookie: __Host-SESSION=" + id).body());
                        assertEquals("2", send(server, "POST", "/count", "Cookie: __Host-SESSION=" + id).body());
                        assertEquals("none", send(server, "GET", "/find", cookie(id)).body(),
                                "another cookie name is another cookie");
                    }
                }),
                parameterized("constructed values of the cookie SESSION: 200, no session, no Set-Cookie ("
                        + VALUES.size() + " values)", VALUES, value -> {
                    var f = fixture.get();
                    int before = f.sessions().size();
                    var reply = send(f.server(), "GET", "/find", cookie(value.value().apply(f)));
                    assertEquals(200, reply.status(), reply.toString());
                    assertEquals("none", reply.body());
                    assertEquals(List.of(), setCookies(reply));
                    assertEquals(List.of(), values(reply, "Cache-Control"));
                    assertTrue(f.sessions().size() <= before, "no session was created");
                }),
                test("two SESSION values: the first live one is used, in any order", () -> {
                    var f = fixture.get();
                    assertEquals("found:1", send(f.server(), "GET", "/find", cookie(f.live())).body(),
                            "the live id still works after every constructed value");
                    var unknown = unknownId();
                    for (var header : List.of(cookie(unknown) + "; SESSION=" + f.live(),
                            cookie(f.invalidated()) + "; SESSION=" + f.live(),
                            cookie("\"" + f.live() + "\"") + "; SESSION=" + f.live(),
                            cookie(f.live()) + "; SESSION=" + unknown)) {
                        var reply = send(f.server(), "GET", "/find", header);
                        assertEquals("found:1", reply.body(), header);
                        assertEquals(List.of(), setCookies(reply));
                    }
                    var split = send(f.server(), "GET", "/find", cookie(unknown), cookie(f.live()));
                    assertEquals("found:1", split.body(), "two Cookie fields count together");
                    var none = send(f.server(), "GET", "/find", cookie(unknown) + "; SESSION=" + f.invalidated());
                    assertEquals("none", none.body());
                }))
                .beforeAll(() -> {
                    var clock = new SessionSupport.TestClock();
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT.withIdleTimeout(Duration.ofSeconds(10)), clock);
                    var server = Support.serve(SessionSupport.app(sessions));
                    var expired = issued(send(server, "POST", "/count"));
                    clock.advance(Duration.ofSeconds(10));
                    var live = issued(send(server, "POST", "/count"));
                    var invalidated = issued(send(server, "POST", "/count"));
                    assertEquals(1, setCookies(send(server, "POST", "/invalidate", cookie(invalidated))).size());
                    var replaced = issued(send(server, "POST", "/count"));
                    var renewed = issued(send(server, "POST", "/renew", cookie(replaced)));
                    assertNotEquals(replaced, renewed);
                    var other = Support.serve(SessionSupport.app(Sessions.inMemory(SessionOptions.DEFAULT)));
                    otherServer.set(other);
                    var otherId = issued(send(other, "POST", "/count"));
                    assertEquals("found:1", send(other, "GET", "/find", cookie(otherId)).body());
                    assertEquals(3, sessions.size(), "live, renewed and the expired one (not looked up yet)");
                    fixture.set(new Fixture(server, sessions, live, invalidated, expired, replaced, otherId));
                })
                .afterAll(() -> {
                    if (fixture.get() != null) {
                        fixture.get().server().close();
                    }
                    if (otherServer.get() != null) {
                        otherServer.get().close();
                    }
                });
    }
}
