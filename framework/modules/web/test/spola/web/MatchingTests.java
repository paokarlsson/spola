package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import spola.http.HttpServer;
import spola.http.Response;
import spola.test.Suite;

/**
 * Criterion 4: table-driven matching. Every case runs against the same routes registered in
 * order and in reverse order; the outcomes must be the same.
 */
final class MatchingTests {

    private MatchingTests() {
    }

    /** A handler that answers "METHOD pattern name=value ...". */
    static WebHandler show(String... names) {
        return request -> {
            var text = new StringBuilder(request.method()).append(' ').append(request.pattern().orElseThrow());
            for (var name : names) {
                text.append(' ').append(name).append('=').append(request.pathParam(name));
            }
            return Response.text(200, text.toString());
        };
    }

    /** The registrations, each one step on the builder (a group is one step). */
    static final List<Consumer<RouterBuilder>> REGISTRATIONS = List.of(
            b -> b.get("/", show()),
            b -> b.get("/orders", show()),
            b -> b.get("/orders/new", show()),
            b -> b.get("/orders/{id}", show("id")),
            b -> b.get("/orders/{id}/lines", show("id")),
            b -> b.get("/docs/", show()),
            b -> b.get("/files/readme", show()),
            b -> b.get("/files/{path...}", show("path")),
            b -> b.get("/a/{x}/c", show("x")),
            b -> b.get("/a/b/{y}", show("y")),
            b -> b.get("/only/{x}/c", show("x")),
            b -> b.get("/mixed/{a}", show("a")),
            b -> b.get("/mixed/{a...}", show("a")),
            b -> b.get("/items/{id}", show("id")),
            b -> b.post("/items/{id}", show("id")),
            b -> b.get("/m/{v}", show("v")),
            b -> b.post("/m/lit", show()),
            b -> b.group("/users/{userId}", user -> user
                    .get("/", show("userId"))
                    .group("/orders/{orderId}", order -> order
                            .get("/lines/{line}", show("userId", "orderId", "line")))));

    /**
     * One case.
     *
     * @param method the method
     * @param target the request-target as sent
     * @param status the expected status
     * @param body   the expected body (for 200), or null
     * @param why    what the case shows
     */
    record Case(String method, String target, int status, String body, String why) {
    }

    static final List<Case> CASES = List.of(
            new Case("GET", "/", 200, "GET /", "the root"),
            new Case("GET", "/orders", 200, "GET /orders", "a literal"),
            new Case("GET", "/orders/new", 200, "GET /orders/new", "literal before variable"),
            new Case("GET", "/orders/%6Eew", 200, "GET /orders/new", "literals match the decoded segment"),
            new Case("GET", "/orders/7", 200, "GET /orders/{id} id=7", "a variable"),
            new Case("GET", "/orders/caf%C3%A9", 200, "GET /orders/{id} id=café", "a decoded UTF-8 variable"),
            new Case("GET", "/orders/7/lines", 200, "GET /orders/{id}/lines id=7", "a variable in the middle"),
            new Case("GET", "/orders/", 404, null, "strict trailing slash: /orders/ is not /orders"),
            new Case("GET", "/docs/", 200, "GET /docs/", "a pattern with a trailing slash"),
            new Case("GET", "/docs", 404, null, "strict trailing slash: /docs is not /docs/"),
            new Case("GET", "/Orders", 404, null, "literals are case-sensitive"),
            new Case("GET", "/files/readme", 200, "GET /files/readme", "literal before catch-all"),
            new Case("GET", "/files/a", 200, "GET /files/{path...} path=a", "catch-all with one segment"),
            new Case("GET", "/files/a/b/c", 200, "GET /files/{path...} path=a/b/c", "catch-all with several segments"),
            new Case("GET", "/files/readme/x", 200, "GET /files/{path...} path=readme/x",
                    "backtracking from a literal to the catch-all"),
            new Case("GET", "/files", 404, null, "a catch-all needs at least one segment"),
            new Case("GET", "/files/", 404, null, "a catch-all does not match an empty segment"),
            new Case("GET", "/a/b/c", 200, "GET /a/b/{y} y=c", "literal b wins over {x}: /a/b/{y}"),
            new Case("GET", "/a/z/c", 200, "GET /a/{x}/c x=z", "only /a/{x}/c matches"),
            new Case("GET", "/only/b/c", 200, "GET /only/{x}/c x=b", "a lone /a/{x}/c-style route matches"),
            new Case("GET", "/mixed/one", 200, "GET /mixed/{a} a=one", "variable before catch-all"),
            new Case("GET", "/mixed/one/two", 200, "GET /mixed/{a...} a=one/two", "catch-all when the variable fails"),
            new Case("GET", "/items/5", 200, "GET /items/{id} id=5", "the method selects the route (GET)"),
            new Case("POST", "/items/5", 200, "POST /items/{id} id=5", "the method selects the route (POST)"),
            new Case("POST", "/m/lit", 200, "POST /m/lit", "POST takes the literal"),
            new Case("GET", "/m/lit", 200, "GET /m/{v} v=lit", "GET backtracks to the variable (the literal is POST only)"),
            new Case("GET", "/users/u1", 200, "GET /users/{userId} userId=u1", "a group's / is its prefix"),
            new Case("GET", "/users/u1/orders/o2/lines/3", 200,
                    "GET /users/{userId}/orders/{orderId}/lines/{line} userId=u1 orderId=o2 line=3",
                    "nested groups with variables in the prefixes"),
            new Case("GET", "/users/u1/", 404, null, "a group route is strict about trailing slashes too"),
            new Case("GET", "/a%2Fb/c", 400, null, "%2F stays in its segment and is refused"),
            new Case("GET", "/files/a%2Fb", 400, null, "%2F is refused even where a catch-all would match"),
            new Case("GET", "/nothing/here", 404, null, "no route"));

    static Suite tests() {
        return suite("matching (table-driven)",
                against("registered in order", REGISTRATIONS),
                against("registered in reverse order", REGISTRATIONS.reversed()),
                test("the backtracking example on its own: only /a/{x}/c, then both routes", () -> {
                    try (var server = Support.serve(Router.builder().get("/a/{x}/c", show("x")).build())) {
                        assertEquals("GET /a/{x}/c x=b", Support.send(server.port(), "GET", "/a/b/c").body());
                    }
                    try (var server = Support.serve(Router.builder().get("/a/b/{y}", show("y")).get("/a/{x}/c", show("x"))
                            .build())) {
                        assertEquals("GET /a/b/{y} y=c", Support.send(server.port(), "GET", "/a/b/c").body());
                        assertEquals("GET /a/{x}/c x=q", Support.send(server.port(), "GET", "/a/q/c").body());
                        assertEquals("GET /a/b/{y} y=d", Support.send(server.port(), "GET", "/a/b/d").body());
                    }
                }));
    }

    private static Suite against(String name, List<Consumer<RouterBuilder>> registrations) {
        var server = new AtomicReference<HttpServer>();
        return parameterized(name, CASES, c -> c.method() + " " + c.target() + " - " + c.why(), c -> {
            var reply = Support.send(server.get().port(), c.method(), c.target());
            assertEquals(c.status(), reply.status(), reply.toString());
            if (c.body() != null) {
                assertEquals(c.body(), reply.body());
            } else {
                assertEquals("application/problem+json", reply.header("Content-Type"));
            }
        }).beforeAll(() -> {
            var builder = Router.builder();
            var copy = new ArrayList<>(registrations);
            copy.forEach(r -> r.accept(builder));
            server.set(Support.serve(builder.build()));
        }).afterAll(() -> server.get().close());
    }
}
