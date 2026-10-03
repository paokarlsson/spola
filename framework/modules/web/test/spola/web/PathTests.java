package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import spola.http.HttpServer;
import spola.http.Response;
import spola.test.Suite;

/**
 * Criterion 7: malformed paths over a raw socket give 400 and never reach a handler; double
 * encoding is decoded once.
 */
final class PathTests {

    private PathTests() {
    }

    /**
     * A malformed path.
     *
     * @param target   the request-target
     * @param why      what is wrong with it
     * @param byServer whether spola.http already refuses it (before the router), so the 400 is
     *                 the server's plain text instead of the router's problem response
     */
    record Malformed(String target, String why, boolean byServer) {
    }

    static final List<Malformed> MALFORMED = List.of(
            new Malformed("/a%", "% without hex digits (end)", true),
            new Malformed("/a%4", "% with one hex digit", true),
            new Malformed("/%G0", "%G0 is not hex", true),
            new Malformed("/%C3", "incomplete UTF-8 (2-byte lead alone)", false),
            new Malformed("/%E2%82", "incomplete UTF-8 (3-byte sequence cut)", false),
            new Malformed("/%E2%82x", "incomplete UTF-8 followed by ASCII", false),
            new Malformed("/%80", "a lone continuation byte", false),
            new Malformed("/%FF", "0xFF is never UTF-8", false),
            new Malformed("/%C0%AF", "overlong encoding of /", false),
            new Malformed("/%C0%80", "overlong encoding of NUL", false),
            new Malformed("/%E0%80%AF", "overlong 3-byte encoding of /", false),
            new Malformed("/%ED%A0%80", "encoded surrogate U+D800", false),
            new Malformed("/%ED%BF%BF", "encoded surrogate U+DFFF", false),
            new Malformed("/%F4%90%80%80", "above U+10FFFF", false),
            new Malformed("/a%2Fb", "encoded slash %2F", false),
            new Malformed("/a%2fb", "encoded slash %2f", false),
            new Malformed("/a%5Cb", "encoded backslash %5C", false),
            new Malformed("/a%5cb", "encoded backslash %5c", false),
            new Malformed("/a%00b", "encoded NUL", false),
            new Malformed("/a%0Ab", "encoded LF", false),
            new Malformed("/a%0Db", "encoded CR", false),
            new Malformed("/a%09b", "encoded TAB", false),
            new Malformed("/%1Bx", "encoded ESC", false),
            new Malformed("/a%7F", "encoded DEL", false),
            new Malformed("/.", "a . segment", false),
            new Malformed("/..", "a .. segment", false),
            new Malformed("/a/./b", "a . segment in the middle", false),
            new Malformed("/a/../b", "a .. segment in the middle", false),
            new Malformed("/a/..", "a .. segment at the end", false),
            new Malformed("/%2e", "an encoded . (%2e)", false),
            new Malformed("/%2E%2e", "an encoded .. (%2E%2e)", false),
            new Malformed("/.%2e", "a half-encoded .. (.%2e)", false),
            new Malformed("/a/%2e%2E/b", "an encoded .. in the middle", false),
            new Malformed("/a//b", "an empty segment (//)", false),
            new Malformed("//", "only an empty segment", false),
            new Malformed("//a", "an empty first segment", false));

    static Suite tests() {
        var calls = new AtomicInteger();
        var server = new AtomicReference<HttpServer>();
        return suite("strict path (400 before routing)",
                parameterized("malformed paths give 400 and never reach a handler", MALFORMED,
                        m -> m.target() + " - " + m.why(), m -> {
                            int before = calls.get();
                            var reply = Support.send(server.get().port(), "GET", m.target());
                            assertEquals(400, reply.status(), reply.toString());
                            assertEquals(before, calls.get(), "a handler was called");
                            if (!m.byServer()) {
                                assertEquals("application/problem+json", reply.header("Content-Type"));
                                var json = reply.json();
                                assertEquals(400L, json.number("status").longValueExact());
                                assertEquals(m.target(), json.string("instance"), "the raw path, since it did not decode");
                            }
                        }),
                test("%252F decodes once, to the text %2F, and is one segment", () -> {
                    // Expected: /%252F is one segment whose decoded text is "%2F" (not a slash, not
                    // decoded again), so {p...} matches one segment with the value "%2F".
                    var reply = Support.send(server.get().port(), "GET", "/%252F");
                    assertEquals(200, reply.status(), reply.toString());
                    assertEquals("segments=[%2F] p=%2F count=1", reply.body());
                    // /a/%252F/b is three segments: "a", "%2F", "b"
                    var three = Support.send(server.get().port(), "GET", "/a/%252F/b");
                    assertEquals("segments=[a, %2F, b] p=a/%2F/b count=3", three.body());
                    // the variable route sees exactly one segment
                    var one = Support.send(server.get().port(), "GET", "/one/%252F");
                    assertEquals("x=%2F", one.body());
                }),
                test("valid paths still work: root, encoded space, UTF-8, plus, %25", () -> {
                    assertEquals("root", Support.send(server.get().port(), "GET", "/").body());
                    assertEquals("segments=[a b, é, a+b, 100%] p=a b/é/a+b/100% count=4",
                            Support.send(server.get().port(), "GET", "/a%20b/%C3%A9/a+b/100%25").body());
                }))
                .beforeAll(() -> server.set(Support.serve(Router.builder()
                        .get("/", r -> {
                            calls.incrementAndGet();
                            return Response.text(200, "root");
                        })
                        .get("/one/{x}", r -> {
                            calls.incrementAndGet();
                            return Response.text(200, "x=" + r.pathParam("x"));
                        })
                        .get("/{p...}", r -> {
                            calls.incrementAndGet();
                            return Response.text(200, "segments=" + r.pathSegments() + " p=" + r.pathParam("p")
                                    + " count=" + r.pathParamSegments("p").size());
                        })
                        .build())))
                .afterAll(() -> server.get().close());
    }
}
