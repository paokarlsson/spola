package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;
import java.util.function.Consumer;
import spola.http.Response;
import spola.test.Suite;

/** Criterion 6: build() refuses invalid routers and lists every problem in one exception. */
final class DefinitionTests {

    private DefinitionTests() {
    }

    static final WebHandler OK = request -> Response.text(200, "ok");

    /**
     * One invalid registration.
     *
     * @param what     what is wrong
     * @param register the registration
     * @param expected the problem line, exactly
     */
    record Bad(String what, Consumer<RouterBuilder> register, String expected) {
    }

    static final List<Bad> BAD = List.of(
            new Bad("pattern without a leading /", b -> b.get("orders", OK),
                    "GET \"orders\": does not start with /"),
            new Bad("empty pattern", b -> b.get("", OK), "GET \"\": does not start with /"),
            new Bad("empty variable name", b -> b.get("/a/{}", OK),
                    "GET \"/a/{}\": has an empty variable name in {}"),
            new Bad("empty catch-all name", b -> b.get("/a/{...}", OK),
                    "GET \"/a/{...}\": has an empty variable name in {...}"),
            new Bad("duplicate variable names", b -> b.get("/a/{id}/b/{id}", OK),
                    "GET \"/a/{id}/b/{id}\": has the variable name id more than once"),
            new Bad("duplicate names across a group prefix", b -> b.group("/u/{id}", g -> g.get("/{id}", OK)),
                    "GET \"/u/{id}/{id}\": has the variable name id more than once"),
            new Bad("partial segment", b -> b.get("/files/{id}.json", OK),
                    "GET \"/files/{id}.json\": has a partial segment {id}.json (a variable must be a whole segment)"),
            new Bad("partial segment before", b -> b.get("/files/x{id}", OK),
                    "GET \"/files/x{id}\": has a partial segment x{id} (a variable must be a whole segment)"),
            new Bad("catch-all not last", b -> b.get("/files/{rest...}/meta", OK),
                    "GET \"/files/{rest...}/meta\": has the catch-all {rest...} before the last segment"),
            new Bad("catch-all in a group prefix", b -> b.group("/f/{rest...}", g -> g.get("/x", OK)),
                    "GET \"/f/{rest...}/x\": has the catch-all {rest...} before the last segment"),
            new Bad("% in a pattern", b -> b.get("/a%20b", OK),
                    "GET \"/a%20b\": has % in a segment (write literals decoded; patterns are not percent-decoded)"),
            new Bad("a wildcard", b -> b.get("/static/*", OK),
                    "GET \"/static/*\": has a wildcard in * (not supported; use {name} or {name...})"),
            new Bad("a variable name that is not an identifier", b -> b.get("/a/{1x}", OK),
                    "GET \"/a/{1x}\": has a variable name that is not a Java identifier: {1x}"),
            new Bad("an empty segment", b -> b.get("/a//b", OK), "GET \"/a//b\": has an empty segment (//)"),
            new Bad("a dot segment", b -> b.get("/a/../b", OK),
                    "GET \"/a/../b\": has a . or .. segment (such paths are refused with 400)"),
            new Bad("a group prefix with a trailing slash", b -> b.group("/g/", g -> g.get("/x", OK)),
                    "group prefix \"/g/\": ends with / (a group prefix cannot)"),
            new Bad("invalid method (space)", b -> b.route("GE T", "/x", OK),
                    "method \"GE T\" for \"/x\": not a token (RFC 9110 5.6.2)"),
            new Bad("invalid method (empty)", b -> b.route("", "/x", OK),
                    "method \"\" for \"/x\": not a token (RFC 9110 5.6.2)"),
            new Bad("two routes with the same method and shape", b -> b.get("/a/{x}", OK).get("/a/{y}", OK),
                    "GET \"/a/{y}\": same method and shape as GET \"/a/{x}\""),
            new Bad("the same route twice in a group", b -> b.get("/g/x", OK).group("/g", g -> g.get("/x", OK)),
                    "GET \"/g/x\": same method and shape as GET \"/g/x\""),
            new Bad("two mappers for the same exception class",
                    b -> b.onException(IllegalStateException.class, (e, r) -> Response.status(409))
                            .onException(IllegalStateException.class, (e, r) -> Response.status(410)),
                    "two exception mappers for java.lang.IllegalStateException"),
            new Bad("a mapper for an Error", b -> b.onException(StackOverflowError.class, (e, r) -> Response.status(500)),
                    "exception mapper for java.lang.StackOverflowError: errors are not mapped (they go to the server)"));

    static Suite tests() {
        return suite("build() validation",
                parameterized("one invalid registration", BAD, Bad::what, bad -> {
                    var builder = Router.builder().get("/fine", OK);
                    bad.register().accept(builder);
                    var e = assertThrows(RouterDefinitionException.class, builder::build);
                    assertEquals(List.of(bad.expected()), e.problems());
                    assertTrue(e.getMessage().contains(bad.expected()), e.getMessage());
                    assertTrue(e instanceof IllegalArgumentException);
                }),
                test("five bad patterns and two conflicts: one exception naming all seven", () -> {
                    var builder = Router.builder()
                            .get("/ok/{id}", OK)
                            .get("no-slash", OK)
                            .get("/x/{}", OK)
                            .get("/y/{a}/{a}", OK)
                            .get("/z/{id}.json", OK)
                            .get("/w/{rest...}/more", OK)
                            .get("/ok/{other}", OK)
                            .post("/p", OK)
                            .post("/p", OK);
                    var e = assertThrows(RouterDefinitionException.class, builder::build);
                    assertEquals(List.of(
                            "GET \"no-slash\": does not start with /",
                            "GET \"/x/{}\": has an empty variable name in {}",
                            "GET \"/y/{a}/{a}\": has the variable name a more than once",
                            "GET \"/z/{id}.json\": has a partial segment {id}.json (a variable must be a whole segment)",
                            "GET \"/w/{rest...}/more\": has the catch-all {rest...} before the last segment",
                            "GET \"/ok/{other}\": same method and shape as GET \"/ok/{id}\"",
                            "POST \"/p\": same method and shape as POST \"/p\""), e.problems());
                    assertTrue(e.getMessage().startsWith("the router has 7 problems:\n  - GET \"no-slash\""), e.getMessage());
                    for (var problem : e.problems()) {
                        assertTrue(e.getMessage().contains(problem), problem);
                    }
                }),
                test("every kind of problem at once, with the mappers and the method", () -> {
                    var builder = Router.builder()
                            .get("x", OK)
                            .get("/a/{}", OK)
                            .get("/b/{n}/{n}", OK)
                            .get("/c/{n}x", OK)
                            .get("/d/{r...}/e", OK)
                            .get("/e%2F", OK)
                            .route("BAD METHOD", "/f", OK)
                            .get("/g/{x}", OK)
                            .get("/g/{y}", OK)
                            .onException(RuntimeException.class, (e, r) -> Response.status(500))
                            .onException(RuntimeException.class, (e, r) -> Response.status(500));
                    var e = assertThrows(RouterDefinitionException.class, builder::build);
                    assertEquals(9, e.problems().size(), e.getMessage());
                }),
                test("valid edge cases build: root, trailing slash, catch-all, unicode literal, explicit HEAD", () -> {
                    var router = Router.builder()
                            .get("/", OK)
                            .get("/docs/", OK)
                            .get("/docs", OK)
                            .get("/files/{path...}", OK)
                            .get("/café/{_x$}", OK)
                            .route("HEAD", "/docs", OK)
                            .route("get", "/docs", OK)
                            .get("/a/{x}", OK)
                            .get("/a/{x...}", OK)
                            .group("/", g -> g.get("/inner", OK))
                            .build();
                    assertEquals("Router[10 routes, 0 filters, 0 mappers]", router.toString());
                }),
                test("a group cannot be used after its lambda", () -> {
                    var leaked = new RouteGroup[1];
                    var builder = Router.builder().group("/g", g -> leaked[0] = g);
                    assertThrows(IllegalStateException.class, () -> leaked[0].get("/x", OK));
                    builder.build();
                }),
                test("the builder can be used after build(); the router does not change", () -> {
                    var builder = Router.builder().get("/a", OK);
                    var first = builder.build();
                    builder.get("/b", OK);
                    assertEquals("Router[1 routes, 0 filters, 0 mappers]", first.toString());
                    assertEquals("Router[2 routes, 0 filters, 0 mappers]", builder.build().toString());
                }));
    }
}
