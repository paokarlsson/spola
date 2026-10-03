package spola.security.web;

import static spola.security.web.Support.ADA;
import static spola.security.web.Support.ADA_PASSWORD;
import static spola.security.web.Support.BOB;
import static spola.security.web.Support.BOB_PASSWORD;
import static spola.security.web.Support.basic;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import spola.http.Response;
import spola.security.Identity;
import spola.test.Suite;
import spola.web.Router;

/** Criterion 7: the first matching rule decides, build() problems, roles, and failing matchers and rules. */
final class RuleTests {

    private RuleTests() {
    }

    /** A router with {@code security} first and one route {@code GET /x} that counts. */
    static Router router(WebSecurity security, AtomicInteger handled) {
        return Support.router()
                .filter(security.filter())
                .get("/x", request -> {
                    handled.incrementAndGet();
                    return Response.text(200, "handled");
                })
                .build();
    }

    static WebSecurity.Builder withBasic() {
        return WebSecurity.builder().authentication(HttpBasic.of(Support.authenticator(), "app"));
    }

    static Suite tests() {
        return suite("rules",
                test("the first matching rule decides: permitAll before denyAll, and the other way round", () -> {
                    var handled = new AtomicInteger();
                    var permitFirst = withBasic()
                            .rule(RequestMatcher.exactly("/x"), AccessRule.permitAll())
                            .rule(RequestMatcher.under("/"), AccessRule.denyAll())
                            .build();
                    var denyFirst = withBasic()
                            .rule(RequestMatcher.under("/"), AccessRule.denyAll())
                            .rule(RequestMatcher.exactly("/x"), AccessRule.permitAll())
                            .build();
                    try (var permit = Support.serve(router(permitFirst, handled));
                            var deny = Support.serve(router(denyFirst, handled))) {
                        assertEquals(200, Support.send(permit.port(), "GET", "/x").status());
                        assertEquals(200, Support.send(permit.port(), "GET", "/x", basic(BOB, BOB_PASSWORD)).status());
                        assertEquals(2, handled.get());
                        BasicTests.assert401(Support.send(permit.port(), "GET", "/y"));
                        BasicTests.assert401(Support.send(deny.port(), "GET", "/x"));
                        BasicTests.assert403(Support.send(deny.port(), "GET", "/x", basic(ADA, ADA_PASSWORD)));
                        assertEquals(2, handled.get());
                    }
                }),
                test("authenticated, hasRole and hasAnyRole", () -> {
                    var handled = new AtomicInteger();
                    var security = withBasic()
                            .rule(RequestMatcher.exactly("/x"), AccessRule.hasAnyRole("AUDITOR", "USER"))
                            .rule(RequestMatcher.exactly("/y"), AccessRule.hasRole("ADMIN"))
                            .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
                            .build();
                    var router = Support.router()
                            .filter(security.filter())
                            .get("/x", request -> Response.text(200, "x"))
                            .get("/y", request -> Response.text(200, "y"))
                            .get("/z", request -> Response.text(200, "z"))
                            .build();
                    try (var server = Support.serve(router)) {
                        assertEquals(200, Support.send(server.port(), "GET", "/x", basic(BOB, BOB_PASSWORD)).status());
                        BasicTests.assert403(Support.send(server.port(), "GET", "/y", basic(BOB, BOB_PASSWORD)));
                        assertEquals(200, Support.send(server.port(), "GET", "/y", basic(ADA, ADA_PASSWORD)).status());
                        assertEquals(200, Support.send(server.port(), "GET", "/z", basic(BOB, BOB_PASSWORD)).status());
                        BasicTests.assert401(Support.send(server.port(), "GET", "/x"));
                        BasicTests.assert401(Support.send(server.port(), "GET", "/z"));
                    }
                    assertEquals(0, handled.get());
                }),
                test("a rule after anyRequest(): IllegalArgumentException at build() with one line per problem", () -> {
                    var builder = WebSecurity.builder()
                            .rule(RequestMatcher.under("/a"), AccessRule.permitAll())
                            .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
                            .rule(RequestMatcher.under("/b"), AccessRule.denyAll())
                            .rule(RequestMatcher.anyRequest(), AccessRule.permitAll());
                    var e = assertThrows(IllegalArgumentException.class, builder::build);
                    assertEquals(List.of("rule 3 comes after anyRequest() in rule 2 and can never match",
                            "rule 4 comes after anyRequest() in rule 2 and can never match"),
                            List.of(e.getMessage().split("\n")));
                    // anyRequest() last, inside method(...) or nowhere at all is fine.
                    WebSecurity.builder().rule(RequestMatcher.anyRequest(), AccessRule.permitAll()).build();
                    WebSecurity.builder()
                            .rule(RequestMatcher.method("GET", RequestMatcher.anyRequest()), AccessRule.permitAll())
                            .rule(RequestMatcher.under("/b"), AccessRule.denyAll())
                            .build();
                    WebSecurity.builder().build();
                }),
                test("the builder refuses null at once; build() takes a copy", () -> {
                    var builder = WebSecurity.builder();
                    assertThrows(NullPointerException.class, () -> builder.rule(null, AccessRule.permitAll()));
                    assertThrows(NullPointerException.class, () -> builder.rule(RequestMatcher.anyRequest(), null));
                    assertThrows(NullPointerException.class, () -> builder.authentication(null));
                    builder.rule(RequestMatcher.exactly("/x"), AccessRule.permitAll());
                    var built = builder.build();
                    builder.rule(RequestMatcher.anyRequest(), AccessRule.permitAll());
                    var handled = new AtomicInteger();
                    try (var server = Support.serve(router(built, handled))) {
                        assertEquals(200, Support.send(server.port(), "GET", "/x").status());
                        assertEquals(401, Support.send(server.port(), "GET", "/y").status());
                    }
                }),
                test("invalid roles give IllegalArgumentException, exactly where Identity refuses them", () -> {
                    var candidates = new ArrayList<>(List.of("", "A B", "x".repeat(101), "räk", "A\n", "\u0000", "A\u007f",
                            " ADMIN", "ADMIN ", "A\tB"));
                    for (var role : candidates) {
                        assertThrows(IllegalArgumentException.class, ()  -> AccessRule.hasRole(role));
                        assertThrows(IllegalArgumentException.class, ()  -> AccessRule.hasAnyRole("USER", role));
                        assertThrows(IllegalArgumentException.class, ()  -> new Identity("n", Set.of(role)));
                        var message = assertThrows(IllegalArgumentException.class, () -> AccessRule.hasRole(role)).getMessage();
                        for (char c : message.toCharArray()) {
                            assertFalse(c < 0x20 || c == 0x7f, "raw control character in: " + message);
                        }
                    }
                    for (var role : List.of("A", "x".repeat(100), "ROLE_ADMIN", "a:b", "~!@#$%^&*()")) {
                        AccessRule.hasRole(role);
                        AccessRule.hasAnyRole(role);
                        new Identity("n", Set.of(role));
                    }
                    assertThrows(IllegalArgumentException.class, AccessRule::hasAnyRole);
                    assertThrows(NullPointerException.class, () -> AccessRule.hasRole(null));
                    assertThrows(NullPointerException.class, () -> AccessRule.hasAnyRole((String[]) null));
                    assertThrows(NullPointerException.class, () -> AccessRule.hasAnyRole("A", null));
                }),
                test("an exception from a matcher, a rule or a mechanism gives 500 and never reaches the handler", () -> {
                    var handled = new AtomicInteger();
                    RequestMatcher throwingMatcher = request -> {
                        throw new IllegalStateException("matcher failed");
                    };
                    AccessRule throwingRule = (identity, request) -> {
                        throw new IllegalStateException("rule failed");
                    };
                    var securities = List.of(
                            withBasic().rule(throwingMatcher, AccessRule.permitAll()).build(),
                            withBasic().rule(RequestMatcher.anyRequest(), throwingRule).build(),
                            withBasic().rule(RequestMatcher.exactly("/y"), AccessRule.permitAll())
                                    .rule(throwingMatcher, AccessRule.permitAll())
                                    .rule(RequestMatcher.anyRequest(), AccessRule.permitAll()).build(),
                            WebSecurity.builder().authentication(request -> {
                                throw new IllegalStateException("mechanism failed");
                            }).rule(RequestMatcher.anyRequest(), AccessRule.permitAll()).build(),
                            WebSecurity.builder().authentication(request -> null)
                                    .rule(RequestMatcher.anyRequest(), AccessRule.permitAll()).build());
                    for (var security : securities) {
                        try (var server = Support.serve(router(security, handled))) {
                            assertEquals(500, Support.send(server.port(), "GET", "/x").status());
                            assertEquals(500, Support.send(server.port(), "GET", "/x", basic(ADA, ADA_PASSWORD)).status());
                        }
                    }
                    assertEquals(0, handled.get());
                }),
                test("an AccessRule lambda sees the identity and the request", () -> {
                    var handled = new AtomicInteger();
                    AccessRule ownPath = (identity, request) -> identity.isPresent()
                            && request.query("owner").orElse("").equals(identity.get().name());
                    var security = withBasic().rule(RequestMatcher.anyRequest(), ownPath).build();
                    try (var server = Support.serve(router(security, handled))) {
                        assertEquals(200, Support.send(server.port(), "GET", "/x?owner=" + ADA, basic(ADA, ADA_PASSWORD)).status());
                        assertEquals(403, Support.send(server.port(), "GET", "/x?owner=" + ADA, basic(BOB, BOB_PASSWORD)).status());
                        assertEquals(401, Support.send(server.port(), "GET", "/x?owner=" + ADA).status());
                        assertEquals(1, handled.get());
                    }
                }));
    }
}
