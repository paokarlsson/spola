package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.web.SessionSupport.cookie;
import static spola.web.SessionSupport.issued;
import static spola.web.SessionSupport.send;
import static spola.web.SessionSupport.setCookies;
import static spola.web.SessionSupport.values;

import java.util.List;
import spola.http.HttpServer;
import spola.http.Response;
import spola.test.Suite;

/**
 * Uppdrag 15a, the session cookie: a response that sets or removes it never stays
 * storable by a shared cache. The handler's {@code Cache-Control} stays only when it already has
 * {@code no-store} or an unqualified {@code private} and neither {@code public} nor
 * {@code s-maxage}; anything else becomes one {@code Cache-Control: no-store}.
 */
final class SessionCacheTests {

    private SessionCacheTests() {
    }

    /**
     * A {@code Cache-Control} the handler sets (one value per field) and what a response with the
     * session cookie must have.
     */
    record Case(String name, List<String> handler, List<String> expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    static final List<String> NO_STORE = List.of("no-store");

    /** The handler's values and the outcome when the response sets or removes the session cookie. */
    static final List<Case> CASES = List.of(
            new Case("none", List.of(), NO_STORE),
            new Case("public, max-age=60", List.of("public, max-age=60"), NO_STORE),
            new Case("s-maxage=60", List.of("s-maxage=60"), NO_STORE),
            new Case("private, max-age=60 stays", List.of("private, max-age=60"), List.of("private, max-age=60")),
            new Case("no-store stays", List.of("no-store"), List.of("no-store")),
            new Case("no-cache, no-store, max-age=0, must-revalidate (Spring Security's) stays",
                    List.of("no-cache, no-store, max-age=0, must-revalidate"),
                    List.of("no-cache, no-store, max-age=0, must-revalidate")),
            new Case("max-age=60 lets a shared cache store it", List.of("max-age=60"), NO_STORE),
            new Case("no-cache lets a shared cache store it", List.of("no-cache"), NO_STORE),
            new Case("must-revalidate", List.of("must-revalidate"), NO_STORE),
            new Case("PUBLIC in capitals", List.of("PUBLIC, MAX-AGE=60"), NO_STORE),
            new Case("Private in capitals stays", List.of("Private"), List.of("Private")),
            new Case("private with s-maxage", List.of("private, s-maxage=60"), NO_STORE),
            new Case("no-store with public", List.of("no-store, public"), NO_STORE),
            new Case("private=\"Set-Cookie\" lets a shared cache store the rest",
                    List.of("private=\"Set-Cookie\""), NO_STORE),
            new Case("a quoted public is not a directive", List.of("private, no-cache=\"public, s-maxage\""),
                    List.of("private, no-cache=\"public, s-maxage\"")),
            new Case("an escaped quote in a quoted string", List.of("no-cache=\"a\\\"b\", private"),
                    List.of("no-cache=\"a\\\"b\", private")),
            new Case("private in a second field stays", List.of("max-age=60", "private"),
                    List.of("max-age=60", "private")),
            new Case("s-maxage in a second field", List.of("private", "s-maxage=60"), NO_STORE),
            new Case("empty list elements and whitespace stay", List.of(",  private ,\t,max-age=60"),
                    List.of(",  private ,\t,max-age=60")),
            new Case("a semicolon is not a separator", List.of("private; max-age=60"), NO_STORE),
            new Case("an open quoted string", List.of("private, no-cache=\"x"), NO_STORE),
            new Case("an empty argument", List.of("private, max-age="), NO_STORE),
            new Case("an empty value", List.of(""), NO_STORE),
            new Case("a lone equals sign", List.of("=private"), NO_STORE));

    /**
     * {@code /cache/{n}} answers with the case's {@code Cache-Control} fields and an
     * {@code X-Other} field; {@code POST} creates a session first, {@code PUT} renews it,
     * {@code DELETE} invalidates it and {@code GET} leaves it alone.
     */
    static Router app(Sessions sessions) {
        return SessionSupport.router().filter(sessions.filter())
                .post("/cache/{n}", r -> {
                    Sessions.getOrCreate(r).put(SessionSupport.COUNT, 1);
                    return answer(r);
                })
                .put("/cache/{n}", r -> {
                    Sessions.renew(r);
                    return answer(r);
                })
                .delete("/cache/{n}", r -> {
                    Sessions.invalidate(r);
                    return answer(r);
                })
                .get("/cache/{n}", SessionCacheTests::answer)
                .build();
    }

    static Response answer(WebRequest request) {
        var response = Response.text(200, "cached").withHeader("X-Other", "kept");
        for (var value : CASES.get(Integer.parseInt(request.pathParam("n"))).handler()) {
            response = response.withHeader("Cache-Control", value);
        }
        return response;
    }

    static Support.Reply call(HttpServer server, String method, Case c, String... headers) throws Exception {
        var reply = send(server, method, "/cache/" + CASES.indexOf(c), headers);
        assertEquals(200, reply.status(), reply.toString());
        assertEquals(List.of("kept"), values(reply, "X-Other"), "other fields stay");
        assertEquals(List.of("text/plain; charset=utf-8"), values(reply, "Content-Type"));
        return reply;
    }

    static Suite tests() {
        return suite("Cache-Control with the session cookie (15a)",
                parameterized("a response that creates, renews or removes the session", CASES, c -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    try (var server = Support.serve(app(sessions))) {
                        var created = call(server, "POST", c);
                        var id = issued(created);
                        assertEquals(c.expected(), values(created, "Cache-Control"), "created");
                        var renewed = call(server, "PUT", c, cookie(id));
                        var renewedId = issued(renewed);
                        assertEquals(c.expected(), values(renewed, "Cache-Control"), "renewed");
                        var plain = call(server, "GET", c, cookie(renewedId));
                        assertEquals(List.of(), setCookies(plain));
                        assertEquals(c.handler(), values(plain, "Cache-Control"), "no cookie: the handler's as it is");
                        var removed = call(server, "DELETE", c, cookie(renewedId));
                        assertEquals(1, setCookies(removed).size());
                        assertEquals(c.expected(), values(removed, "Cache-Control"), "removed");
                    }
                }),
                test("no session and no cookie: the handler's Cache-Control, or none, is left alone", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    try (var server = Support.serve(app(sessions))) {
                        for (var c : CASES) {
                            var reply = call(server, "GET", c);
                            assertEquals(List.of(), setCookies(reply));
                            assertEquals(c.handler(), values(reply, "Cache-Control"), c.name());
                            var invalidated = call(server, "DELETE", c);
                            assertEquals(List.of(), setCookies(invalidated), "nothing to remove");
                            assertEquals(c.handler(), values(invalidated, "Cache-Control"), c.name());
                        }
                    }
                }));
    }
}
