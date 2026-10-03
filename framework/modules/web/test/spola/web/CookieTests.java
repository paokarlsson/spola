package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import spola.http.HttpServer;
import spola.http.Response;
import spola.test.Suite;

/** Criteria 3 and 4: parsing incoming cookies (RFC 6265 §4.2.1, §5.4), at most once per request. */
final class CookieTests {

    private CookieTests() {
    }

    /**
     * One {@code Cookie} header.
     *
     * @param name     what the case shows
     * @param fields   the values of the {@code Cookie} fields, in order
     * @param expected every name=value pair in the parsed map's order (names by first appearance,
     *                 then values in order)
     */
    record Case(String name, List<String> fields, List<String> expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Case of(String name, String field, String... expected) {
        return new Case(name, List.of(field), List.of(expected));
    }

    static final List<Case> CASES = List.of(
            of("one cookie", "a=1", "a=1"),
            of("several cookies", "a=1; b=2; c=3", "a=1", "b=2", "c=3"),
            of("no space after ;", "a=1;b=2", "a=1", "b=2"),
            of("the same name twice, in order", "a=1; a=2", "a=1", "a=2"),
            of("the same name spread out keeps its values in order", "a=1; b=3; a=2; a=4", "a=1", "a=2", "a=4", "b=3"),
            of("spaces and tabs around pairs", "  a=1 ;\tb=2\t;  \t c=3", "a=1", "b=2", "c=3"),
            of("a quoted value keeps its quotes", "a=\"quoted\"; b=\"\"", "a=\"quoted\"", "b=\"\""),
            of("an empty value", "a=; b=2", "a=", "b=2"),
            of("= inside the value", "a=b=c; d==", "a=b=c", "d=="),
            new Case("two Cookie fields are joined", List.of("a=1; b=2", "c=3; a=4"), List.of("a=1", "a=4", "b=2", "c=3")),
            of("percent-encoding is not decoded", "a=%41%20b; b=x+y", "a=%41%20b", "b=x+y"),
            of("names are case-sensitive", "A=1; a=2", "A=1", "a=2"),
            of("all cookie-octets are allowed", "a=!#$%&'()*+-./:<=>?@[]^_`{|}~09AZaz", "a=!#$%&'()*+-./:<=>?@[]^_`{|}~09AZaz"),
            of("a pair without = is skipped", "a=1; novalue; b=2", "a=1", "b=2"),
            of("an empty name is skipped", "a=1; =x; b=2", "a=1", "b=2"),
            of("a name with ( is skipped", "a=1; b(=x; c=2", "a=1", "c=2"),
            of("a name with a space is skipped", "a=1; b c=x; c=2", "a=1", "c=2"),
            of("a name with quotes is skipped", "a=1; \"b\"=x; c=2", "a=1", "c=2"),
            of("space before = makes the name invalid", "a=1; b =x; c=2", "a=1", "c=2"),
            of("a value with a space is skipped", "a=1; b=x y; c=2", "a=1", "c=2"),
            of("a value with a tab inside is skipped", "a=1; b=x\ty; c=2", "a=1", "c=2"),
            of("a value with a comma is skipped", "a=1; b=x,y; c=2", "a=1", "c=2"),
            of("a value with a backslash is skipped", "a=1; b=x\\y; c=2", "a=1", "c=2"),
            of("a value with a control character is skipped", "a=1; b=x\u0001y; c=2", "a=1", "c=2"),
            of("a value with DEL is skipped", "a=1; b=x\u007fy; c=2", "a=1", "c=2"),
            of("a value with ESC is skipped", "a=1; b=\u001b[31m; c=2", "a=1", "c=2"),
            of("a value with non-ASCII (obs-text) is skipped", "a=1; b=xåy; c=2", "a=1", "c=2"),
            of("a value with a quote in the middle is skipped", "a=1; b=x\"y; c=2", "a=1", "c=2"),
            of("a value with only an opening quote is skipped", "a=1; b=\"x; c=2", "a=1", "c=2"),
            of("a lone quote is skipped", "a=1; b=\"; c=2", "a=1", "c=2"),
            of("a quoted value with a space inside is skipped", "a=1; b=\"x y\"; c=2", "a=1", "c=2"),
            of("a trailing ; is ignored", "a=1;", "a=1"),
            of("empty pieces are ignored", ";; a=1 ;;", "a=1"),
            of("an empty header gives nothing", ""),
            of("only malformed pairs give nothing", "x; =y; z w=1"),
            of("prefixed names are plain names here", "__Host-id=1; __Secure-x=2", "__Host-id=1", "__Secure-x=2"));

    static List<String> flatten(Map<String, List<String>> cookies) {
        var pairs = new ArrayList<String>();
        cookies.forEach((name, values) -> values.forEach(value -> pairs.add(name + "=" + value)));
        return pairs;
    }

    static String many(int count) {
        var header = new StringBuilder();
        for (int i = 0; i < count; i++) {
            header.append(i == 0 ? "" : "; ").append("c").append(i).append("=SECRET").append(i);
        }
        return header.toString();
    }

    static Suite tests() {
        var server = new AtomicReference<HttpServer>();
        return suite("cookies in",
                parameterized("Cookie header parsing (" + CASES.size() + " cases)", CASES, c -> {
                    var cookies = CookieState.parse(c.fields());
                    assertEquals(c.expected(), flatten(cookies));
                    assertThrows(UnsupportedOperationException.class, () -> cookies.put("x", List.of()));
                }),
                test("cookie gives the first value, cookieAll every value in order, cookies() all of them", () -> {
                    var reply = Support.send(server.get().port(), "GET", "/show?name=a", "Cookie: a=1; b=2; a=\"3\"",
                            "Cookie: a=4");
                    assertEquals(200, reply.status(), reply.toString());
                    assertEquals("first=1 all=[1, \"3\", 4] map={a=[1, \"3\", 4], b=[2]}", reply.body());
                }),
                test("a malformed pair is skipped on a real request, not 400", () -> {
                    var reply = Support.send(server.get().port(), "GET", "/show?name=b",
                            "Cookie: x; a=\"q; b=2; =3; c=x,y; d=å");
                    assertEquals(200, reply.status(), reply.toString());
                    assertEquals("first=2 all=[2] map={b=[2]}", reply.body());
                }),
                test("no Cookie header: empty results", () -> {
                    var reply = Support.send(server.get().port(), "GET", "/show?name=a");
                    assertEquals("first=- all=[] map={}", reply.body());
                    assertEquals(Map.of(), CookieState.parse(List.of()));
                }),
                test("200 cookies are accepted, 201 give 400 without names or values in the detail", () -> {
                    assertEquals(200, CookieState.parse(List.of(many(200))).size());
                    var e = assertThrows(WebException.class, () -> CookieState.parse(List.of(many(201))));
                    assertEquals(400, e.status());
                    var ok = Support.send(server.get().port(), "GET", "/show?name=c199", "Cookie: " + many(200));
                    assertEquals(200, ok.status(), ok.toString());
                    assertTrue(ok.body().startsWith("first=SECRET199 "), ok.body());
                    var reply = Support.send(server.get().port(), "GET", "/show?name=c1", "Cookie: " + many(201));
                    assertEquals(400, reply.status(), reply.toString());
                    assertEquals("The request has more than 200 cookies.", reply.json().string("detail"));
                    assertFalse(reply.body().contains("SECRET"), reply.body());
                    assertFalse(reply.body().contains("c1"), reply.body());
                    var split = Support.send(server.get().port(), "GET", "/show?name=c1", "Cookie: " + many(150),
                            "Cookie: " + many(51));
                    assertEquals(400, split.status(), "the fields are counted together");
                    var skipped = Support.send(server.get().port(), "GET", "/show?name=c1",
                            "Cookie: " + many(200) + "; bad cookie=1; x");
                    assertEquals(200, skipped.status(), "skipped pairs do not count");
                }),
                test("cookies are parsed at most once, across withAttribute copies and threads", () -> {
                    var reply = Support.send(server.get().port(), "GET", "/once", "Cookie: a=1; b=2", "Cookie: a=3");
                    assertEquals(200, reply.status(), reply.toString());
                    assertEquals("before=0 after=1 values=24", reply.body());
                    var failed = Support.send(server.get().port(), "GET", "/once", "Cookie: " + many(201));
                    assertEquals(200, failed.status(), failed.toString());
                    assertEquals("before=0 after=1 values=0", failed.body(), "a failed parse is kept, not repeated");
                }),
                test("cookie(name, Param.INT): 400 with the name and the value escaped and shortened", () -> {
                    var ok = Support.send(server.get().port(), "GET", "/int", "Cookie: x=42");
                    assertEquals("42", ok.body());
                    var absent = Support.send(server.get().port(), "GET", "/int", "Cookie: y=1");
                    assertEquals("none", absent.body());
                    var reply = Support.send(server.get().port(), "GET", "/int", "Cookie: x=abc");
                    assertEquals(400, reply.status());
                    assertEquals("Invalid cookie \"x\": expected int, got \"abc\".", reply.json().string("detail"));
                    var longValue = "9".repeat(30) + "%0A%1B" + "z".repeat(60);
                    var cut = Support.send(server.get().port(), "GET", "/int", "Cookie: x=" + longValue);
                    assertEquals(400, cut.status());
                    assertEquals("Invalid cookie \"x\": expected int, got \"" + longValue.substring(0, 40) + "...\".",
                            cut.json().string("detail"));
                    var quoted = Support.send(server.get().port(), "GET", "/int", "Cookie: x=\"5\"");
                    assertEquals(400, quoted.status(), "the quotes are part of the value");
                    assertTrue(quoted.json().string("detail").endsWith("got \"\\\"5\\\"\"."), quoted.toString());
                }),
                test("cookies() is unmodifiable and the same map for every copy", () -> {
                    var reply = Support.send(server.get().port(), "GET", "/same", "Cookie: a=1");
                    assertEquals("same=true unmodifiable=true", reply.body());
                }))
                .beforeAll(() -> server.set(Support.serve(Router.builder()
                        .get("/show", r -> {
                            var name = r.requiredQuery("name", Param.STRING);
                            return Response.text(200, "first=" + r.cookie(name).orElse("-") + " all=" + r.cookieAll(name)
                                    + " map=" + r.cookies());
                        })
                        .get("/once", r -> {
                            int before = r.cookieParses();
                            var copies = List.of(r, r.withAttribute(ScaleTests.CLIENT, "a"),
                                    r.withAttribute(ScaleTests.CLIENT, "b").withAttribute(ScaleTests.CLIENT, "c"));
                            int values = 0;
                            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                                var start = new CountDownLatch(1);
                                var futures = new ArrayList<Future<Integer>>();
                                for (int i = 0; i < 24; i++) {
                                    var copy = copies.get(i % copies.size());
                                    futures.add(executor.submit(() -> {
                                        start.await();
                                        try {
                                            return copy.cookieAll("a").size() == 2 && copy.cookie("b").isPresent() ? 1 : -1;
                                        } catch (WebException e) {
                                            return 0;
                                        }
                                    }));
                                }
                                start.countDown();
                                for (var future : futures) {
                                    values += future.get();
                                }
                            }
                            int after = 0;
                            for (var copy : copies) {
                                after = Math.max(after, copy.cookieParses());
                            }
                            return Response.text(200, "before=" + before + " after=" + after + " values=" + values);
                        })
                        .get("/int", r -> Response.text(200, r.cookie("x", Param.INT).map(String::valueOf).orElse("none")))
                        .get("/same", r -> {
                            var copy = r.withAttribute(ScaleTests.CLIENT, "x");
                            boolean unmodifiable;
                            try {
                                r.cookies().put("b", List.of());
                                unmodifiable = false;
                            } catch (UnsupportedOperationException e) {
                                unmodifiable = true;
                            }
                            return Response.text(200, "same=" + (r.cookies() == copy.cookies()) + " unmodifiable="
                                    + unmodifiable);
                        })
                        .build())))
                .afterAll(() -> server.get().close());
    }
}
