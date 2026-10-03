package spola.security.web;

import static spola.security.web.Support.ADA;
import static spola.security.web.Support.ADA_PASSWORD;
import static spola.security.web.Support.BOB;
import static spola.security.web.Support.BOB_PASSWORD;
import static spola.security.web.Support.basic;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertSame;
import static spola.test.Assertions.assertThrows;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import spola.http.Response;
import spola.test.Suite;
import spola.web.Router;

/** Criterion 6: the matchers on decoded segments, invalid prefixes, method and HEAD. */
final class MatcherTests {

    private MatcherTests() {
    }

    /**
     * A router whose only filter evaluates {@code matchers} on the request the router made (its
     * filters run also for 400 and 404) and answers with one {@code 1} or {@code 0} per matcher.
     */
    static Router evaluating(List<RequestMatcher> matchers) {
        return Support.router()
                .filter((request, next) -> {
                    var out = new StringBuilder();
                    for (var matcher : matchers) {
                        out.append(matcher.matches(request) ? '1' : '0');
                    }
                    return Response.text(200, out.toString());
                })
                .build();
    }

    static Suite tests() {
        return suite("matchers",
                test("under and exactly on decoded segments, case-sensitive, whole segments", () -> {
                    var matchers = List.of(RequestMatcher.under("/admin"), RequestMatcher.exactly("/admin"),
                            RequestMatcher.under("/admin/x"), RequestMatcher.exactly("/admin/x"), RequestMatcher.under("/"),
                            RequestMatcher.exactly("/"), RequestMatcher.under("/Admin"));
                    // under(/admin), exactly(/admin), under(/admin/x), exactly(/admin/x), under(/), exactly(/), under(/Admin)
                    var expected = new LinkedHashMap<String, String>();
                    expected.put("/admin", "1100100");
                    expected.put("/admin/", "1000100");
                    expected.put("/admin/x", "1011100");
                    expected.put("/admin/x/", "1010100");
                    expected.put("/admin/x/y", "1010100");
                    expected.put("/admin/xy", "1000100");
                    expected.put("/administrator", "0000100");
                    expected.put("/Admin", "0000101");
                    expected.put("/ADMIN", "0000100");
                    expected.put("/%61dmin", "1100100");
                    expected.put("/%61dmin/%78", "1011100");
                    expected.put("/%41dmin", "0000101");
                    expected.put("/adm%69n/", "1000100");
                    expected.put("/x/admin", "0000100");
                    expected.put("/", "0000110");
                    // Paths the router rejects with 400: empty segments, no match at all.
                    expected.put("/admin%2Fx", "0000000");
                    expected.put("/admin//x", "0000000");
                    expected.put("/%2e%2e/admin", "0000000");
                    expected.put("/admin/%2e%2e/admin", "0000000");
                    expected.put("/admin/..", "0000000");
                    expected.put("/admin%00", "0000000");
                    expected.put("/admin/%ff", "0000000");
                    try (var server = Support.serve(evaluating(matchers))) {
                        var failures = new ArrayList<String>();
                        for (Map.Entry<String, String> entry : expected.entrySet()) {
                            var reply = Support.send(server.port(), "GET", entry.getKey());
                            if (!entry.getValue().equals(reply.body())) {
                                failures.add(entry.getKey() + ": expected " + entry.getValue() + ", was " + reply);
                            }
                        }
                        assertEquals(List.of(), failures);
                    }
                }),
                test("invalid prefixes and paths give IllegalArgumentException with an escaped message", () -> {
                    for (var bad : List.of("", "admin", "/admin/", "//admin", "/admin//x", "/{id}", "/a/{x}", "/a*", "/a/**",
                            "/a?", "/a%20b", "/%61dmin", "/.", "/..", "/a/./b", "/a/../b", "/a\nb", "/a\u0000", "/a\u007fb",
                            "/a\\b", "/a}")) {
                        var under = assertThrows(IllegalArgumentException.class, ()  -> RequestMatcher.under(bad));
                        assertThrows(IllegalArgumentException.class, ()  -> RequestMatcher.exactly(bad));
                        for (char c : under.getMessage().toCharArray()) {
                            assertFalse(c < 0x20 || c == 0x7f, "raw control character in: " + under.getMessage());
                        }
                    }
                    assertThrows(NullPointerException.class, () -> RequestMatcher.under(null));
                    assertThrows(NullPointerException.class, () -> RequestMatcher.exactly(null));
                    // Valid: non-ASCII, dots inside a segment, ~, ;.
                    RequestMatcher.under("/räk/smörgås");
                    RequestMatcher.under("/v1.0/a~b;c");
                    RequestMatcher.exactly("/");
                }),
                test("method: exact and case-sensitive; GET also matches HEAD, nothing else does", () -> {
                    var get = RequestMatcher.method("GET", RequestMatcher.anyRequest());
                    var head = RequestMatcher.method("HEAD", RequestMatcher.anyRequest());
                    var post = RequestMatcher.method("POST", RequestMatcher.under("/admin"));
                    var router = Support.router()
                            .filter((request, next) -> Response.text(200, (get.matches(request) ? "1" : "0")
                                    + (head.matches(request) ? "1" : "0") + (post.matches(request) ? "1" : "0")))
                            .build();
                    try (var server = Support.serve(router)) {
                        assertEquals("100", Support.send(server.port(), "GET", "/admin").body());
                        assertEquals(200, Support.send(server.port(), "HEAD", "/admin").status());
                        assertEquals("001", Support.send(server.port(), "POST", "/admin").body());
                        assertEquals("000", Support.send(server.port(), "POST", "/other").body());
                        assertEquals("000", Support.send(server.port(), "get", "/admin").body());
                        assertEquals("000", Support.send(server.port(), "PUT", "/admin").body());
                    }
                    // HEAD responses have no body: check HEAD with a filter that answers with a header.
                    var headRouter = Support.router()
                            .filter((request, next) -> Response.status(204).withHeader("X-Match",
                                    (get.matches(request) ? "1" : "0") + (head.matches(request) ? "1" : "0")
                                            + (post.matches(request) ? "1" : "0")))
                            .build();
                    try (var server = Support.serve(headRouter)) {
                        assertEquals("110", Support.send(server.port(), "HEAD", "/admin").header("X-Match"));
                        assertEquals("100", Support.send(server.port(), "GET", "/admin").header("X-Match"));
                    }
                    for (var bad : List.of("", "G T", "GET\n", "GE/T", "(GET)")) {
                        assertThrows(IllegalArgumentException.class,
                                () -> RequestMatcher.method(bad, RequestMatcher.anyRequest()));
                    }
                    assertThrows(NullPointerException.class, () -> RequestMatcher.method(null, RequestMatcher.anyRequest()));
                    assertThrows(NullPointerException.class, () -> RequestMatcher.method("GET", null));
                    assertSame(RequestMatcher.anyRequest(), RequestMatcher.anyRequest());
                }),
                test("method(GET, under(/admin)) with hasRole(ADMIN) before anyRequest().permitAll(): 401 for GET and HEAD",
                        () -> {
                            var security = WebSecurity.builder()
                                    .authentication(HttpBasic.of(Support.authenticator(), "app"))
                                    .rule(RequestMatcher.method("GET", RequestMatcher.under("/admin")), AccessRule.hasRole("ADMIN"))
                                    .rule(RequestMatcher.anyRequest(), AccessRule.permitAll())
                                    .build();
                            var handled = new java.util.concurrent.atomic.AtomicInteger();
                            var router = Support.router()
                                    .filter(security.filter())
                                    .get("/admin", request -> {
                                        handled.incrementAndGet();
                                        return Response.text(200, "handled");
                                    })
                                    .build();
                            try (var server = Support.serve(router)) {
                                BasicTests.assert401(Support.send(server.port(), "GET", "/admin"));
                                var head = Support.send(server.port(), "HEAD", "/admin");
                                assertEquals(401, head.status());
                                assertEquals(List.of(BasicTests.CHALLENGE), head.all("WWW-Authenticate"));
                                assertEquals(401, Support.send(server.port(), "GET", "/%61dmin").status());
                                assertEquals(0, handled.get());
                                assertEquals(403, Support.send(server.port(), "HEAD", "/admin", basic(BOB, BOB_PASSWORD)).status());
                                assertEquals(200, Support.send(server.port(), "HEAD", "/admin", basic(ADA, ADA_PASSWORD)).status());
                                assertEquals(200, Support.send(server.port(), "GET", "/admin", basic(ADA, ADA_PASSWORD)).status());
                                assertEquals(2, handled.get());
                                // POST is not covered by the GET rule: permitAll, and the router answers 405.
                                assertEquals(405, Support.send(server.port(), "POST", "/admin").status());
                            }
                        }));
    }
}
