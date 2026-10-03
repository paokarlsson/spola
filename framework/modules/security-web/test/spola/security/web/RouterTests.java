package spola.security.web;

import static spola.security.web.Support.ADA;
import static spola.security.web.Support.ADA_PASSWORD;
import static spola.security.web.Support.basic;
import static spola.test.Assertions.assertEquals;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import spola.http.Response;
import spola.test.Suite;
import spola.web.Router;

/** Criterion 9: the filter is outermost and sees the router's 404, 405, OPTIONS and 400. */
final class RouterTests {

    private RouterTests() {
    }

    static Router router(WebSecurity security, AtomicInteger handled) {
        return Support.router()
                .filter(security.filter())
                .get("/public/x", request -> {
                    handled.incrementAndGet();
                    return Response.text(200, "public");
                })
                .get("/admin/users", request -> {
                    handled.incrementAndGet();
                    return Response.text(200, "users");
                })
                .group("/admin/group", g -> g.get("/x", request -> {
                    handled.incrementAndGet();
                    return Response.text(200, "group");
                }))
                .build();
    }

    static WebSecurity.Builder rules() {
        return WebSecurity.builder()
                .authentication(HttpBasic.of(Support.authenticator(), "app"))
                .rule(RequestMatcher.under("/public"), AccessRule.permitAll())
                .rule(RequestMatcher.under("/admin"), AccessRule.hasRole("ADMIN"));
    }

    static Suite tests() {
        return suite("the router's own answers",
                test("404, 405 and automatic OPTIONS under a protected rule: 401 anonymous, the router's answer with the"
                        + " role", () -> {
                            var handled = new AtomicInteger();
                            try (var server = Support.serve(router(rules().build(), handled))) {
                                int port = server.port();
                                BasicTests.assert401(Support.send(port, "GET", "/admin/unknown"));
                                BasicTests.assert401(Support.send(port, "POST", "/admin/users"));
                                BasicTests.assert401(Support.send(port, "OPTIONS", "/admin/users"));
                                BasicTests.assert401(Support.send(port, "DELETE", "/admin/group/x"));
                                var admin = basic(ADA, ADA_PASSWORD);
                                assertEquals(404, Support.send(port, "GET", "/admin/unknown", admin).status());
                                var notAllowed = Support.send(port, "POST", "/admin/users", admin);
                                assertEquals(405, notAllowed.status());
                                assertEquals("GET, HEAD, OPTIONS", notAllowed.header("Allow"));
                                var options = Support.send(port, "OPTIONS", "/admin/users", admin);
                                assertEquals(204, options.status());
                                assertEquals("GET, HEAD, OPTIONS", options.header("Allow"));
                                // Under permitAll the router answers as usual.
                                assertEquals(404, Support.send(port, "GET", "/public/nope").status());
                                assertEquals(405, Support.send(port, "POST", "/public/x").status());
                                assertEquals(204, Support.send(port, "OPTIONS", "/public/x").status());
                                // No rule matches: denied, even where the router would say 404.
                                BasicTests.assert401(Support.send(port, "GET", "/nothing"));
                                BasicTests.assert401(Support.send(port, "OPTIONS", "*"));
                                assertEquals(0, handled.get());
                            }
                        }),
                test("a path the router rejects with 400 never reaches a handler", () -> {
                    var handled = new AtomicInteger();
                    var paths = List.of("/%2e%2e/admin/users", "/%2e%2e/public/x", "/public/%2e%2e/admin/users",
                            "/public/..%2Fadmin/users", "/public%2Fx", "/public//x", "/public/%00", "/admin/%2E/users");
                    var withFallback = rules().rule(RequestMatcher.anyRequest(), AccessRule.permitAll()).build();
                    try (var strict = Support.serve(router(rules().build(), handled));
                            var lenient = Support.serve(router(withFallback, handled))) {
                        for (var path : paths) {
                            // No rule matches a rejected path: denied before the router's 400.
                            assertEquals(401, Support.send(strict.port(), "GET", path).status(), path);
                            assertEquals(403, Support.send(strict.port(), "GET", path, basic(ADA, ADA_PASSWORD)).status(), path);
                            // With anyRequest().permitAll() the router answers 400.
                            assertEquals(400, Support.send(lenient.port(), "GET", path).status(), path);
                            assertEquals(400, Support.send(lenient.port(), "GET", path, basic(ADA, ADA_PASSWORD)).status(), path);
                        }
                    }
                    assertEquals(0, handled.get());
                }),
                test("the filter registered first is outside every other filter: a denied request reaches none", () -> {
                    var security = rules().build();
                    var inner = new AtomicInteger();
                    var router = Support.router()
                            .filter(security.filter())
                            .filter((request, next) -> {
                                inner.incrementAndGet();
                                return next.proceed(request);
                            })
                            .get("/admin/users", request -> Response.text(200, "users"))
                            .build();
                    try (var server = Support.serve(router)) {
                        assertEquals(401, Support.send(server.port(), "GET", "/admin/users").status());
                        assertEquals(401, Support.send(server.port(), "GET", "/admin/nope").status());
                        assertEquals(0, inner.get());
                        assertEquals(200, Support.send(server.port(), "GET", "/admin/users", basic(ADA, ADA_PASSWORD)).status());
                        assertEquals(1, inner.get());
                    }
                }));
    }
}
