package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.ArrayList;
import java.util.List;
import spola.http.Response;
import spola.test.Suite;

/** Criterion 10: filter order, short-circuit, response changes, attributes, scope. */
final class FilterTests {

    private FilterTests() {
    }

    static final AttributeKey<String> USER = AttributeKey.of("user", String.class);

    /** A filter that records "name>" before and "<name" after the rest of the chain. */
    static Filter recording(String name, List<String> log) {
        return (request, next) -> {
            synchronized (log) {
                log.add(name + ">");
            }
            var response = next.proceed(request);
            synchronized (log) {
                log.add("<" + name);
            }
            return response;
        };
    }

    static Suite tests() {
        return suite("filters",
                test("order: registration order, first outermost, router filters outside group filters", () -> {
                    var log = new ArrayList<String>();
                    var router = Router.builder()
                            .filter(recording("r1", log))
                            .group("/g", g -> g
                                    .filter(recording("g1", log))
                                    .group("/h", h -> h
                                            .filter(recording("h1", log))
                                            .get("/x", r -> {
                                                synchronized (log) {
                                                    log.add("handler");
                                                }
                                                return Response.text(200, "x");
                                            }))
                                    .filter(recording("g2", log)))
                            .filter(recording("r2", log))
                            .build();
                    try (var server = Support.serve(router)) {
                        assertEquals(200, Support.send(server.port(), "GET", "/g/h/x").status());
                    }
                    assertEquals(List.of("r1>", "r2>", "g1>", "g2>", "h1>", "handler", "<h1", "<g2", "<g1", "<r2", "<r1"),
                            log);
                }),
                test("a filter can answer without calling next", () -> {
                    var called = new ArrayList<String>();
                    var router = Router.builder()
                            .filter((request, next) -> request.headers().first("X-Token").isPresent()
                                    ? next.proceed(request) : Problem.of(401).toResponse())
                            .get("/secret", r -> {
                                called.add("handler");
                                return Response.text(200, "secret");
                            })
                            .build();
                    try (var server = Support.serve(router)) {
                        var denied = Support.send(server.port(), "GET", "/secret");
                        assertEquals(401, denied.status());
                        assertEquals(List.of(), called);
                        var allowed = Support.send(server.port(), "GET", "/secret", "X-Token: t");
                        assertEquals("secret", allowed.body());
                    }
                }),
                test("a filter can change the response (add a header) and pass an attribute to the handler", () -> {
                    var router = Router.builder()
                            .filter((request, next) -> next.proceed(request).withHeader("X-Filtered", "yes"))
                            .filter((request, next) -> next.proceed(
                                    request.withAttribute(USER, request.headers().first("X-User").orElse("anonymous"))))
                            .get("/me", r -> Response.text(200, "user=" + r.attribute(USER).orElseThrow()))
                            .get("/plain", r -> Response.text(200, "attribute=" + r.attribute(AttributeKey.of("user",
                                    String.class))))
                            .build();
                    try (var server = Support.serve(router)) {
                        var reply = Support.send(server.port(), "GET", "/me", "X-User: ada");
                        assertEquals("user=ada", reply.body());
                        assertEquals("yes", reply.header("X-Filtered"));
                        var other = Support.send(server.port(), "GET", "/plain");
                        assertEquals("attribute=Optional.empty", other.body(), "keys compare by identity, not by name");
                    }
                }),
                test("router filters run for 404, 405, automatic OPTIONS, 400 from the path check and OPTIONS *", () -> {
                    var seen = new ArrayList<String>();
                    var router = Router.builder()
                            .filter((request, next) -> {
                                var response = next.proceed(request);
                                synchronized (seen) {
                                    seen.add(request.method() + " " + response.status());
                                }
                                return response.withHeader("X-Router-Filter", "ran");
                            })
                            .get("/items", r -> Response.text(200, "items"))
                            .build();
                    try (var server = Support.serve(router)) {
                        for (var request : List.of(new String[] {"GET", "/missing"}, new String[] {"POST", "/items"},
                                new String[] {"OPTIONS", "/items"}, new String[] {"GET", "/a%2Fb"},
                                new String[] {"OPTIONS", "*"}, new String[] {"GET", "/items"})) {
                            var reply = Support.send(server.port(), request[0], request[1]);
                            assertEquals("ran", reply.header("X-Router-Filter"), request[0] + " " + request[1]);
                        }
                    }
                    assertEquals(List.of("GET 404", "POST 405", "OPTIONS 204", "GET 400", "OPTIONS 404", "GET 200"), seen);
                }),
                test("a group's filters run only for the group's routes", () -> {
                    var log = new ArrayList<String>();
                    var router = Router.builder()
                            .filter(recording("router", log))
                            .get("/outside", r -> Response.text(200, "outside"))
                            .get("/admin", r -> Response.text(200, "same text as the prefix, outside the group"))
                            .group("/admin", g -> g
                                    .filter(recording("admin", log))
                                    .get("/users", r -> Response.text(200, "users")))
                            .build();
                    try (var server = Support.serve(router)) {
                        Support.send(server.port(), "GET", "/outside");
                        Support.send(server.port(), "GET", "/admin");
                        Support.send(server.port(), "GET", "/admin/missing");
                        Support.send(server.port(), "POST", "/admin/users");
                        assertEquals(List.of("router>", "<router", "router>", "<router", "router>", "<router", "router>",
                                "<router"), log);
                        log.clear();
                        Support.send(server.port(), "GET", "/admin/users");
                        Support.send(server.port(), "HEAD", "/admin/users");
                        assertEquals(List.of("router>", "admin>", "<admin", "<router", "router>", "admin>", "<admin",
                                "<router"), log);
                    }
                }),
                test("the request a filter passes on is the one the handler gets; the original does not change", () -> {
                    var key = AttributeKey.of("n", Integer.class);
                    var router = Router.builder()
                            .filter((request, next) -> {
                                var with = request.withAttribute(key, 1);
                                var response = next.proceed(with.withAttribute(key, 2));
                                return response.withHeader("X-Original", String.valueOf(request.attribute(key)));
                            })
                            .get("/n", r -> Response.text(200, "n=" + r.attribute(key).orElseThrow()))
                            .build();
                    try (var server = Support.serve(router)) {
                        var reply = Support.send(server.port(), "GET", "/n");
                        assertEquals("n=2", reply.body());
                        assertEquals("Optional.empty", reply.header("X-Original"));
                    }
                }));
    }
}
