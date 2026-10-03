package spola.security.web;

import static spola.security.web.Support.ADA;
import static spola.security.web.Support.ADA_IDENTITY;
import static spola.security.web.Support.ADA_PASSWORD;
import static spola.security.web.Support.BOB;
import static spola.security.web.Support.BOB_PASSWORD;
import static spola.security.web.Support.basic;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import spola.http.Response;
import spola.test.Suite;

/** Criterion 8: security.require(rule) on a group, requireIdentity, and a missing central filter. */
final class RequireTests {

    private RequireTests() {
    }

    static WebSecurity open() {
        return WebSecurity.builder()
                .authentication(HttpBasic.of(Support.authenticator(), "app"))
                .rule(RequestMatcher.anyRequest(), AccessRule.permitAll())
                .build();
    }

    static Suite tests() {
        return suite("require, identity and requireIdentity",
                test("require(hasRole(ADMIN)) on a group: 401 with the same challenge, 403 without the role, the handler"
                        + " with it", () -> {
                            var security = open();
                            var handled = new AtomicInteger();
                            var router = Support.router()
                                    .filter(security.filter())
                                    .group("/admin", g -> g.filter(security.require(AccessRule.hasRole("ADMIN")))
                                            .get("/users", request -> {
                                                handled.incrementAndGet();
                                                assertEquals(ADA_IDENTITY, WebSecurity.requireIdentity(request));
                                                return Response.text(200, "handled");
                                            }))
                                    .get("/public", request -> Response.text(200, "public"))
                                    .build();
                            try (var server = Support.serve(router)) {
                                var central = WebSecurity.builder()
                                        .authentication(HttpBasic.of(Support.authenticator(), "app"))
                                        .build();
                                try (var reference = Support.serve(Support.router().filter(central.filter()).build())) {
                                    var expected = Support.send(reference.port(), "GET", "/admin/users").withoutDate();
                                    var anonymous = Support.send(server.port(), "GET", "/admin/users");
                                    BasicTests.assert401(anonymous);
                                    assertEquals(expected, anonymous.withoutDate());
                                }
                                BasicTests.assert403(Support.send(server.port(), "GET", "/admin/users", basic(BOB, BOB_PASSWORD)));
                                assertEquals(0, handled.get());
                                var ok = Support.send(server.port(), "GET", "/admin/users", basic(ADA, ADA_PASSWORD));
                                assertEquals(200, ok.status(), String.valueOf(ok));
                                assertEquals(1, handled.get());
                                assertEquals(200, Support.send(server.port(), "GET", "/public").status());
                            }
                        }),
                test("requireIdentity on an anonymous request: the security's own 401", () -> {
                    var security = open();
                    var router = Support.router()
                            .filter(security.filter())
                            .get("/me", request -> Response.text(200, "you are " + WebSecurity.requireIdentity(request).roles()))
                            .build();
                    try (var server = Support.serve(router)) {
                        BasicTests.assert401(Support.send(server.port(), "GET", "/me"));
                        var ok = Support.send(server.port(), "GET", "/me", basic(ADA, ADA_PASSWORD));
                        assertEquals("you are [ADMIN, USER]", ok.body());
                    }
                    // A mapper for WebException sees status 401 and the challenge in headers().
                    var mapped = Support.router()
                            .filter(security.filter())
                            .get("/me", request -> Response.text(200, WebSecurity.requireIdentity(request).name()))
                            .onException(spola.web.WebException.class, (e, request) -> Response.text(e.status(),
                                    "mapped " + e.headers().all("WWW-Authenticate")))
                            .build();
                    try (var server = Support.serve(mapped)) {
                        var reply = Support.send(server.port(), "GET", "/me");
                        assertEquals(401, reply.status());
                        assertEquals("mapped [" + BasicTests.CHALLENGE + "]", reply.body());
                    }
                }),
                test("without the central filter, require, identity and requireIdentity give 500, never anonymous access",
                        () -> {
                            var security = open();
                            var handled = new AtomicInteger();
                            var router = Support.router()
                                    .group("/admin", g -> g.filter(security.require(AccessRule.permitAll()))
                                            .get("/users", request -> {
                                                handled.incrementAndGet();
                                                return Response.text(200, "handled");
                                            }))
                                    .get("/identity", request -> {
                                        handled.incrementAndGet();
                                        return Response.text(200, "anonymous=" + WebSecurity.identity(request).isEmpty());
                                    })
                                    .get("/require", request -> {
                                        handled.incrementAndGet();
                                        return Response.text(200, "name=" + WebSecurity.requireIdentity(request).name());
                                    })
                                    .build();
                            var before = Support.LOG.records().size();
                            try (var server = Support.serve(router)) {
                                for (var path : List.of("/admin/users", "/identity", "/require")) {
                                    assertEquals(500, Support.send(server.port(), "GET", path).status(), path);
                                    assertEquals(500, Support.send(server.port(), "GET", path, basic(ADA, ADA_PASSWORD)).status(),
                                            path);
                                }
                            }
                            assertEquals(4, handled.get()); // /identity and /require ran (twice each), but threw
                            var lines = Support.LOG.records().subList(before, Support.LOG.records().size());
                            assertEquals(6, lines.size(), String.valueOf(lines));
                            for (var line : lines) {
                                assertTrue(line.contains("IllegalStateException: the request has no SecurityContext: register"
                                        + " WebSecurity.filter() on the router, first"), line);
                            }
                        }),
                test("the security filter registered twice for one request: 500", () -> {
                    var security = open();
                    var handled = new AtomicInteger();
                    var router = Support.router()
                            .filter(security.filter())
                            .group("/g", g -> g.filter(security.filter()).get("/x", request -> {
                                handled.incrementAndGet();
                                return Response.text(200, "handled");
                            }))
                            .build();
                    try (var server = Support.serve(router)) {
                        assertEquals(500, Support.send(server.port(), "GET", "/g/x").status());
                        assertEquals(500, Support.send(server.port(), "GET", "/g/x", basic(ADA, ADA_PASSWORD)).status());
                    }
                    assertEquals(0, handled.get());
                }),
                test("identity, requireIdentity and require refuse null", () -> {
                    assertThrows(NullPointerException.class, () -> WebSecurity.identity(null));
                    assertThrows(NullPointerException.class, () -> WebSecurity.requireIdentity(null));
                    assertThrows(NullPointerException.class, () -> open().require(null));
                }));
    }
}
