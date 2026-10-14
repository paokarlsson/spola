package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertNotEquals;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.web.SessionSupport.cookie;
import static spola.web.SessionSupport.idOf;
import static spola.web.SessionSupport.issued;
import static spola.web.SessionSupport.send;
import static spola.web.SessionSupport.setCookies;
import static spola.web.SessionSupport.values;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.UnaryOperator;
import spola.http.HttpServer;
import spola.http.Response;
import spola.test.Suite;

/**
 * 13b in {@code spola.web} (criteria 5 and 6): with only {@link Sessions} and
 * {@code sessions.filter()} after {@link ForwardedHeaders}, the session cookie and its removal get
 * {@code Secure} when the request's scheme is {@code https}, and only then; {@code secure(true)}
 * gives {@code Secure} over {@code http} as before; nothing else in the line changes; and
 * {@code ForwardedHeaders} after {@code sessions.filter()} comes too late. Over a raw socket,
 * where every header line is visible; every reply is recorded for {@link SessionLeakTests}.
 */
final class SessionSchemeTests {

    private SessionSchemeTests() {
    }

    /** The session cookie of 11a, with the id for {@code %s}. */
    static final String COOKIE = "SESSION=%s; Path=/; HttpOnly; SameSite=Lax";
    /** The same with {@code Secure}, as {@code withCookie(c -> c.secure(true))} gives it. */
    static final String SECURE_COOKIE = "SESSION=%s; Path=/; Secure; HttpOnly; SameSite=Lax";
    /** The removal of 11a. */
    static final String REMOVAL = "SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax";
    /** The same with {@code Secure}. */
    static final String SECURE_REMOVAL = "SESSION=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax";

    static final String HTTPS = "X-Forwarded-Proto: https";
    static final String HTTP = "X-Forwarded-Proto: http";

    /** A way to send a request through the proxy, and whether its session cookie must be {@code Secure}. */
    record Scheme(String name, boolean secure, String... headers) {
        @Override
        public String toString() {
            return name;
        }
    }

    static final List<Scheme> SCHEMES = List.of(
            new Scheme("X-Forwarded-Proto: https gives Secure", true, HTTPS, "X-Forwarded-For: 203.0.113.7"),
            new Scheme("X-Forwarded-Proto: HTTPS (any case) gives Secure", true, "X-Forwarded-Proto: HTTPS"),
            new Scheme("X-Forwarded-Proto: http gives no Secure", false, HTTP, "X-Forwarded-For: 203.0.113.7"),
            new Scheme("no X-Forwarded-Proto gives no Secure", false));

    /** {@link SessionSupport#routes} and {@code /create-then-invalidate}, on {@code builder}. */
    static Router routes(RouterBuilder builder) {
        return SessionSupport.routes(builder)
                .post("/create-then-invalidate", r -> {
                    Sessions.getOrCreate(r).put(SessionSupport.COUNT, 1);
                    Sessions.invalidate(r);
                    return Response.text(200, "gone");
                })
                .get("/scheme", r -> Response.text(200, r.scheme()))
                .post("/scheme-count", r -> {
                    Sessions.getOrCreate(r).put(SessionSupport.COUNT, 1);
                    return Response.text(200, r.scheme());
                })
                .build();
    }

    /** {@code ForwardedHeaders} trusting {@code proxies} first, then {@code sessions.filter()}, then the routes. */
    static Router behind(TrustedProxies proxies, Sessions sessions) {
        return routes(SessionSupport.router().filter(ForwardedHeaders.trusting(proxies)).filter(sessions.filter()));
    }

    /** {@code template} with the id of the reply's one {@code Set-Cookie}; fails unless the line is exactly that. */
    static String assertCookie(String template, Support.Reply reply) {
        var id = issued(reply);
        assertEquals(List.of(template.formatted(id)), setCookies(reply));
        assertEquals(List.of("no-store"), values(reply, "Cache-Control"));
        return id;
    }

    /** {@code headers} with {@code more} after them. */
    static String[] with(String[] headers, String... more) {
        var all = new ArrayList<>(List.of(headers));
        all.addAll(List.of(more));
        return all.toArray(String[]::new);
    }

    /**
     * {@code getOrCreate}, a use, {@code renew}, {@code invalidate}, {@code renew} without a
     * session, created-then-invalidated and a route that never touches the session, each with
     * {@code headers}; every session cookie must be exactly {@code cookie} and every removal
     * exactly {@code removal}.
     */
    static void lifecycle(HttpServer server, String[] headers, String cookie, String removal) throws Exception {
        var created = send(server, "POST", "/count", headers);
        assertEquals(200, created.status(), created.toString());
        assertEquals("1", created.body());
        var id = assertCookie(cookie, created);

        var used = send(server, "POST", "/count", with(headers, cookie(id)));
        assertEquals("2", used.body());
        assertEquals(List.of(), setCookies(used), "a use sets nothing");

        var renewed = send(server, "POST", "/renew", with(headers, cookie(id)));
        assertEquals("renewed:2", renewed.body());
        var newId = assertCookie(cookie, renewed);
        assertNotEquals(id, newId);

        var invalidated = send(server, "POST", "/invalidate", with(headers, cookie(newId)));
        assertEquals("invalidated", invalidated.body());
        assertEquals(List.of(removal), setCookies(invalidated));
        assertEquals(List.of("no-store"), values(invalidated, "Cache-Control"));

        var fresh = send(server, "POST", "/renew", headers);
        assertEquals("renewed:0", fresh.body());
        assertCookie(cookie, fresh);

        var gone = send(server, "POST", "/create-then-invalidate", headers);
        assertEquals("gone", gone.body());
        assertEquals(List.of(removal), setCookies(gone), "only the final outcome");

        var plain = send(server, "GET", "/plain", headers);
        assertEquals(List.of(), setCookies(plain));
    }

    static Suite tests() {
        return suite("Secure from the scheme (13b)",
                parameterized("getOrCreate, renew and invalidate with ForwardedHeaders before sessions.filter()",
                        SCHEMES, scheme -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    try (var server = Support.serve(behind(TrustedProxies.privateNetworks(), sessions))) {
                        if (scheme.secure()) {
                            lifecycle(server, scheme.headers(), SECURE_COOKIE, SECURE_REMOVAL);
                        } else {
                            lifecycle(server, scheme.headers(), COOKIE, REMOVAL);
                        }
                    }
                }),
                test("X-Forwarded-Proto: https from a sender that is not trusted gives no Secure", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    try (var server = Support.serve(behind(TrustedProxies.of("10.0.0.0/8"), sessions))) {
                        assertEquals("http", send(server, "GET", "/scheme", HTTPS).body());
                        lifecycle(server, new String[] {HTTPS, "X-Forwarded-For: 203.0.113.7"}, COOKIE, REMOVAL);
                    }
                }),
                test("withCookie(c -> c.secure(true)) gives Secure over http as before, and one Secure over https",
                        () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT.withCookie(c -> c.secure(true)));
                    try (var server = Support.serve(behind(TrustedProxies.privateNetworks(), sessions))) {
                        for (var scheme : SCHEMES) {
                            lifecycle(server, scheme.headers(), SECURE_COOKIE, SECURE_REMOVAL);
                        }
                    }
                }),
                test("nothing but Secure differs: a function with Path, Domain, Max-Age and SameSite=Strict gives over"
                        + " https exactly the lines of the same function with secure(true) over http", () -> {
                    UnaryOperator<ResponseCookie.Builder> function = c -> c.path("/").domain("example.com")
                            .maxAge(Duration.ofHours(1)).sameSite(ResponseCookie.SameSite.STRICT);
                    var plain = Sessions.inMemory(SessionOptions.DEFAULT.withCookie(function));
                    var secure = Sessions.inMemory(SessionOptions.DEFAULT.withCookie(c -> function.apply(c).secure(true)));
                    var cookie = "SESSION=%s; Path=/; Domain=example.com; Max-Age=3600; HttpOnly; SameSite=Strict";
                    var secureCookie = "SESSION=%s; Path=/; Domain=example.com; Max-Age=3600; Secure; HttpOnly;"
                            + " SameSite=Strict";
                    var removal = "SESSION=; Path=/; Domain=example.com; Max-Age=0; HttpOnly; SameSite=Strict";
                    var secureRemoval = "SESSION=; Path=/; Domain=example.com; Max-Age=0; Secure; HttpOnly;"
                            + " SameSite=Strict";
                    try (var plainServer = Support.serve(behind(TrustedProxies.privateNetworks(), plain));
                            var secureServer = Support.serve(behind(TrustedProxies.privateNetworks(), secure))) {
                        lifecycle(plainServer, new String[] {HTTPS}, secureCookie, secureRemoval);
                        lifecycle(plainServer, new String[] {HTTP}, cookie, removal);
                        lifecycle(plainServer, new String[0], cookie, removal);
                        lifecycle(secureServer, new String[] {HTTP}, secureCookie, secureRemoval);
                        lifecycle(secureServer, new String[] {HTTPS}, secureCookie, secureRemoval);
                    }
                }),
                test("the scheme is decided per request: a cookie set over https and removed over http gets a removal"
                        + " without Secure, and the other way round", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    try (var server = Support.serve(behind(TrustedProxies.privateNetworks(), sessions))) {
                        var overHttps = assertCookie(SECURE_COOKIE, send(server, "POST", "/count", HTTPS));
                        // the known limitation: the removal follows the request that removes, not the cookie
                        assertEquals(List.of(REMOVAL), setCookies(send(server, "POST", "/invalidate", HTTP,
                                cookie(overHttps))));
                        var overHttp = assertCookie(COOKIE, send(server, "POST", "/count", HTTP));
                        var renewed = assertCookie(SECURE_COOKIE, send(server, "POST", "/renew", HTTPS, cookie(overHttp)));
                        assertEquals(List.of(SECURE_REMOVAL), setCookies(send(server, "POST", "/invalidate", HTTPS,
                                cookie(renewed))));
                        assertEquals(0, sessions.size());
                    }
                }),
                test("ForwardedHeaders registered after sessions.filter() comes too late: the handler sees https, the"
                        + " cookie gets no Secure", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    var router = routes(SessionSupport.router().filter(sessions.filter())
                            .filter(ForwardedHeaders.trusting(TrustedProxies.privateNetworks())));
                    try (var server = Support.serve(router)) {
                        var reply = send(server, "POST", "/scheme-count", HTTPS);
                        assertEquals("https", reply.body(), "ForwardedHeaders ran");
                        var id = assertCookie(COOKIE, reply);
                        var renewed = send(server, "POST", "/renew", HTTPS, cookie(id));
                        assertCookie(COOKIE, renewed);
                        assertEquals(List.of(REMOVAL), setCookies(send(server, "POST", "/invalidate", HTTPS,
                                cookie(idOf(setCookies(renewed).getFirst())))));
                    }
                    // the documented order, for contrast: the same request gets Secure
                    var ordered = Sessions.inMemory(SessionOptions.DEFAULT);
                    try (var server = Support.serve(behind(TrustedProxies.privateNetworks(), ordered))) {
                        var reply = send(server, "POST", "/scheme-count", HTTPS);
                        assertEquals("https", reply.body());
                        assertCookie(SECURE_COOKIE, reply);
                    }
                }),
                test("200 virtual threads mixing https and http on one Sessions: Secure exactly on the https"
                        + " responses", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    var start = new CountDownLatch(1);
                    try (var server = Support.serve(behind(TrustedProxies.privateNetworks(), sessions));
                            var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        var futures = new ArrayList<Future<String>>();
                        for (int t = 0; t < 200; t++) {
                            boolean https = t % 2 == 0;
                            futures.add(executor.submit(() -> {
                                start.await();
                                var proto = https ? HTTPS : HTTP;
                                var id = assertCookie(https ? SECURE_COOKIE : COOKIE, send(server, "POST", "/count", proto));
                                // the next request of this client uses the other scheme
                                var other = https ? HTTP : HTTPS;
                                var renewed = send(server, "POST", "/renew", other, cookie(id));
                                assertCookie(https ? COOKIE : SECURE_COOKIE, renewed);
                                var removed = send(server, "POST", "/invalidate", proto,
                                        cookie(idOf(setCookies(renewed).getFirst())));
                                assertEquals(List.of(https ? SECURE_REMOVAL : REMOVAL), setCookies(removed));
                                return "ok";
                            }));
                        }
                        start.countDown();
                        for (var future : futures) {
                            assertEquals("ok", future.get());
                        }
                    }
                    assertEquals(0, sessions.size());
                }));
    }
}
