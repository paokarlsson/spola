package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import spola.http.HttpServer;
import spola.http.Response;
import spola.test.Suite;

/** Criterion 8: query parameters after the form-urlencoded rules, the 1 000-pair limit, lazy parsing. */
final class QueryTests {

    private QueryTests() {
    }

    /**
     * One query.
     *
     * @param query    the raw query (after ?)
     * @param expected the parameters as the handler prints them, or null for 400
     * @param why      what the case shows
     */
    record Case(String query, String expected, String why) {
    }

    static final List<Case> CASES = List.of(
            new Case("a=1&a=2&b=3&a=4", "{a=[1, 2, 4], b=[3]}", "several values keep their order"),
            new Case("q=hello+world", "{q=[hello world]}", "+ is a space"),
            new Case("q=1%2B1", "{q=[1+1]}", "%2B is +"),
            new Case("flag", "{flag=[]}", "a name without = has an empty value"),
            new Case("flag&x=1", "{flag=[], x=[1]}", "a name without = among others"),
            new Case("empty=", "{empty=[]}", "an empty value"),
            new Case("a=1&&b=2&", "{a=[1], b=[2]}", "empty pairs (&&, trailing &) are skipped"),
            new Case("&&&", "{}", "only empty pairs"),
            new Case("", "{}", "an empty query"),
            new Case("eq=a=b=c", "{eq=[a=b=c]}", "= inside the value"),
            new Case("=v", "{=[v]}", "an empty name"),
            new Case("n%C3%A4me=v%C3%A4rde", "{näme=[värde]}", "UTF-8 in names and values"),
            new Case("x=%0A%1B", "{x=[\\x0a\\x1b]}", "decoded control characters are data in values"),
            new Case("x=%2F%3F%26", "{x=[/?&]}", "encoded delimiters are data"),
            new Case("x=%FF", null, "invalid UTF-8 gives 400"),
            new Case("x=%C0%AF", null, "an overlong encoding gives 400"),
            new Case("%ED%A0%80=1", null, "an encoded surrogate in a name gives 400"));

    static String show(WebRequest request) {
        var out = new StringBuilder("{");
        for (var entry : request.queryParams().entrySet()) {
            if (out.length() > 1) {
                out.append(", ");
            }
            out.append(entry.getKey()).append("=[");
            var values = entry.getValue();
            for (int i = 0; i < values.size(); i++) {
                out.append(i == 0 ? "" : ", ").append(controls(values.get(i)));
            }
            out.append(']');
        }
        return out.append('}').toString();
    }

    /** Control characters as \\xHH, everything else as is. */
    static String controls(String text) {
        var out = new StringBuilder();
        for (char c : text.toCharArray()) {
            out.append(c < 0x20 ? String.format("\\x%02x", (int) c) : String.valueOf(c));
        }
        return out.toString();
    }

    static Suite tests() {
        var server = new AtomicReference<HttpServer>();
        return suite("query parameters",
                parameterized("form-urlencoded rules", CASES, c -> "?" + c.query() + " - " + c.why(), c -> {
                    var reply = Support.send(server.get().port(), "GET", "/q?" + c.query());
                    if (c.expected() == null) {
                        assertEquals(400, reply.status(), reply.toString());
                        assertEquals("application/problem+json", reply.header("Content-Type"));
                    } else {
                        assertEquals(200, reply.status(), reply.toString());
                        assertEquals(c.expected(), reply.body());
                    }
                }),
                test("first value, all values, absent parameter, no query at all", () -> {
                    var reply = Support.send(server.get().port(), "GET", "/first?a=1&a=2");
                    assertEquals("a=1 all=[1, 2] missing=Optional.empty none=[]", reply.body());
                    assertEquals("{}", Support.send(server.get().port(), "GET", "/q").body());
                }),
                test("exactly 1 000 pairs: 200; 1 001 pairs: 400", () -> {
                    var thousand = query(1_000);
                    var ok = Support.send(server.get().port(), "GET", "/count?" + thousand);
                    assertEquals(200, ok.status(), ok.toString());
                    assertEquals("1000", ok.body());
                    var tooMany = Support.send(server.get().port(), "GET", "/count?" + thousand + "&z=1");
                    assertEquals(400, tooMany.status());
                    assertEquals("The query has more than 1000 parameters.", tooMany.json().string("detail"));
                    // empty pairs do not count
                    var withEmpty = Support.send(server.get().port(), "GET", "/count?" + thousand + "&&&");
                    assertEquals(200, withEmpty.status());
                }),
                test("the query is parsed at most once, however many parameters the handler asks for", () -> {
                    var reply = Support.send(server.get().port(), "GET", "/lazy?a=1&b=2&c=3");
                    assertEquals("before=0 after=1", reply.body());
                    var none = Support.send(server.get().port(), "GET", "/untouched?x=%FF");
                    assertEquals(200, none.status(), "an invalid query that nobody reads is never parsed");
                    assertEquals("parses=0", none.body());
                    var failing = Support.send(server.get().port(), "GET", "/lazy-fail?x=%FF");
                    assertEquals("parses=1 failures=3", failing.body());
                }))
                .beforeAll(() -> server.set(Support.serve(Router.builder()
                        .get("/q", r -> Response.text(200, show(r)))
                        .get("/first", r -> Response.text(200, "a=" + r.query("a").orElseThrow() + " all=" + r.queryAll("a")
                                + " missing=" + r.query("missing") + " none=" + r.queryAll("none")))
                        .get("/count", r -> Response.text(200, String.valueOf(
                                r.queryParams().values().stream().mapToInt(List::size).sum())))
                        .get("/lazy", r -> {
                            int before = r.queryParses();
                            var derived = r.withAttribute(AttributeKey.of("k", String.class), "v");
                            for (int i = 0; i < 100; i++) {
                                r.query("a");
                                derived.queryAll("b");
                                r.query("c", Param.INT);
                                derived.queryParams();
                            }
                            return Response.text(200, "before=" + before + " after=" + derived.queryParses());
                        })
                        .get("/untouched", r -> Response.text(200, "parses=" + r.queryParses()))
                        .get("/lazy-fail", r -> {
                            int failures = 0;
                            for (int i = 0; i < 3; i++) {
                                try {
                                    r.query("x");
                                } catch (WebException e) {
                                    failures++;
                                }
                            }
                            return Response.text(200, "parses=" + r.queryParses() + " failures=" + failures);
                        })
                        .build())))
                .afterAll(() -> server.get().close());
    }

    /** {@code n} pairs {@code x=1&x=1&...} (short, so that the request line stays within 8 KiB). */
    static String query(int n) {
        var out = new StringBuilder();
        for (int i = 0; i < n; i++) {
            out.append(i == 0 ? "" : "&").append("x=1");
        }
        return out.toString();
    }
}
