package spola.security.web;

import static spola.security.web.Support.ADA;
import static spola.security.web.Support.ADA_PASSWORD;
import static spola.security.web.Support.BOB;
import static spola.security.web.Support.BOB_PASSWORD;
import static spola.security.web.Support.EVE;
import static spola.security.web.Support.EVE_PASSWORD;
import static spola.security.web.Support.basic;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import spola.http.Response;
import spola.security.Authenticator;
import spola.security.UserStore;
import spola.test.Suite;
import spola.web.Router;

/** Criterion 5: one Authenticator call per request with credentials, none without, and store failures. */
final class CountingTests {

    private CountingTests() {
    }

    /** {@code /open} for everyone, {@code /closed} for authenticated users; the handler counts. */
    static Router router(Authenticator authenticator, AtomicInteger handled) {
        var security = WebSecurity.builder()
                .authentication(HttpBasic.of(authenticator, "app"))
                .rule(RequestMatcher.exactly("/open"), AccessRule.permitAll())
                .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
                .build();
        return Support.router()
                .filter(security.filter())
                .get("/open", request -> {
                    handled.incrementAndGet();
                    return Response.text(200, "handled");
                })
                .get("/closed", request -> {
                    handled.incrementAndGet();
                    return Response.text(200, "handled");
                })
                .build();
    }

    static Suite tests() {
        return suite("Authenticator calls",
                test("exactly one call per request with Basic credentials, none for an anonymous request", () -> {
                    var counting = new Support.Counting();
                    var handled = new AtomicInteger();
                    try (var server = Support.serve(router(counting.authenticator, handled))) {
                        int expected = 0;
                        for (var path : List.of("/open", "/closed", "/missing")) {
                            for (var authorization : List.of(basic(ADA, ADA_PASSWORD), basic(BOB, BOB_PASSWORD),
                                    basic(ADA, "zPwd-wrong"), basic("zUsrNobody", "zPwd-x"), basic(EVE, EVE_PASSWORD),
                                    basic(ADA, ""))) {
                                Support.send(server.port(), "GET", path, authorization);
                                expected++;
                                assertEquals(expected, counting.calls(), path + " " + authorization);
                            }
                            Support.send(server.port(), "GET", path);
                            Support.send(server.port(), "HEAD", path);
                            Support.send(server.port(), "OPTIONS", path);
                            assertEquals(expected, counting.calls(), "anonymous " + path);
                        }
                        // The store is asked once per call with a valid username.
                        assertEquals(expected, counting.finds.get());
                    }
                }),
                test("a RuntimeException from the user store gives 500 and never access, also on permitAll", () -> {
                    var lookups = new AtomicInteger();
                    UserStore broken = username -> {
                        lookups.incrementAndGet();
                        throw new IllegalStateException("the user database is down");
                    };
                    var counting = new Support.Counting(broken);
                    var handled = new AtomicInteger();
                    try (var server = Support.serve(router(counting.authenticator, handled))) {
                        for (var path : List.of("/open", "/closed")) {
                            var reply = Support.send(server.port(), "GET", path, basic(ADA, ADA_PASSWORD));
                            assertEquals(500, reply.status(), String.valueOf(reply));
                            assertEquals("application/problem+json", reply.header("Content-Type"));
                            assertFalse(reply.body().contains("database"), reply.body());
                        }
                        assertEquals(0, handled.get());
                        assertEquals(2, lookups.get());
                        // Without credentials the store is not asked, so the open path still works.
                        assertEquals(200, Support.send(server.port(), "GET", "/open").status());
                        assertEquals(1, handled.get());
                        assertEquals(2, lookups.get());
                    }
                }));
    }
}
