package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import spola.http.HttpServer;
import spola.http.Response;
import spola.test.Suite;

/** Criterion 4: Accept and negotiate, table-driven, through a real server. */
final class AcceptTests {

    private AcceptTests() {
    }

    /**
     * One negotiation.
     *
     * @param accept   the Accept fields, one header line each (empty: no Accept)
     * @param offered  what the handler offers, in its order
     * @param expected the chosen media type, or {@code 406} or {@code 400}
     */
    record Case(List<String> accept, List<String> offered, String expected) {
        @Override
        public String toString() {
            return (accept.isEmpty() ? "no Accept" : String.join(" | ", accept)) + " offering " + offered + " -> "
                    + expected;
        }
    }

    static final String JSON = "application/json";
    static final String TEXT = "text/plain";
    static final String HTML = "text/html";

    static Case c(String accept, List<String> offered, String expected) {
        return new Case(accept == null ? List.of() : List.of(accept), offered, expected);
    }

    static final List<Case> CASES = List.of(
            // no Accept: everything is acceptable, the first offered wins
            c(null, List.of(JSON, TEXT), JSON),
            c(null, List.of(TEXT, JSON), TEXT),
            c("", List.of(TEXT, JSON), TEXT),
            c(" , ,", List.of(JSON), JSON),
            c("*/*", List.of(JSON, TEXT), JSON),
            c("text/plain", List.of(JSON, TEXT), TEXT),
            c("text/html", List.of(JSON), "406"),
            // q=0 excludes a type even when */* would take it, in either order
            c("application/json;q=0, */*", List.of(JSON), "406"),
            c("*/*, application/json;q=0", List.of(JSON), "406"),
            c("application/json;q=0, */*", List.of(JSON, TEXT), TEXT),
            c("*/*;q=0", List.of(JSON), "406"),
            // the most specific range decides, whatever the order
            c("text/*;q=0.3, text/plain;q=0.7, */*;q=0.5", List.of(HTML, TEXT, JSON), TEXT),
            c("text/plain;q=0.7, */*;q=0.5, text/*;q=0.3", List.of(HTML, TEXT, JSON), TEXT),
            c("*/*;q=0.5, text/*;q=0.3", List.of(HTML, JSON), JSON),
            c("text/*;q=0.3, */*;q=0.5", List.of(HTML, JSON), JSON),
            c("text/html;level=1;q=0, text/html;q=0.8", List.of("text/html;level=1", HTML), HTML),
            c("text/html;q=0.8, text/html;level=1;q=0", List.of("text/html;level=1", HTML), HTML),
            c("application/*;q=0.1, application/json;q=0", List.of(JSON, "application/vnd.x+json"),
                    "application/vnd.x+json"),
            c("application/json;q=0.5, text/html;q=0.9", List.of(JSON), JSON),
            // equal quality: the server's order wins
            c("application/json;q=0.5, text/plain;q=0.5", List.of(JSON, TEXT), JSON),
            c("application/json;q=0.5, text/plain;q=0.5", List.of(TEXT, JSON), TEXT),
            c("text/plain, application/json", List.of(JSON, TEXT), JSON),
            // several Accept fields are one list
            new Case(List.of("text/html", "application/json;q=0.2"), List.of(JSON), JSON),
            new Case(List.of("application/json;q=0", "*/*"), List.of(JSON, TEXT), TEXT),
            new Case(List.of("*/*", "application/json;q=0"), List.of(JSON), "406"),
            // a comma inside a quoted value does not split
            c("text/plain;title=\"a, b\", application/json;q=0.5", List.of("text/plain;title=\"a, b\"", JSON),
                    "text/plain;title=\"a, b\""),
            c("text/plain;title=\"a, b\", application/json;q=0.5", List.of(TEXT, JSON), JSON),
            // accept-ext after q is ignored (it is not a parameter of the range)
            c("application/json;q=0.5;ext=1;flag, text/plain;q=0.4", List.of(TEXT, JSON), JSON),
            c("text/plain;q=0.5;q=0.9, application/json;q=0.6", List.of(TEXT, JSON), JSON),
            // case, weights at the edges
            c("APPLICATION/JSON;Q=0.5, Text/Plain;q=0.499", List.of(TEXT, JSON), JSON),
            c("application/json;q=1., text/plain;q=0.999", List.of(TEXT, JSON), JSON),
            c("application/json;q=1.000, text/plain;q=0.", List.of(TEXT, JSON), JSON),
            c("application/json;q=0.001", List.of(JSON), JSON),
            // invalid Accept: 400, not 406
            c("application/json;q=1.5", List.of(JSON), "400"),
            c("application/json;q=-1", List.of(JSON), "400"),
            c("application/json;q=0.0001", List.of(JSON), "400"),
            c("application/json;q=abc", List.of(JSON), "400"),
            c("application/json;q=1.0000", List.of(JSON), "400"),
            c("application/json;q=1.001", List.of(JSON), "400"),
            c("application/json;q=\"0.5\"", List.of(JSON), "400"),
            c("application/json;q=", List.of(JSON), "400"),
            c("application/", List.of(JSON), "400"),
            c("*/json", List.of(JSON), "400"),
            c("text/plain;title=\"open, application/json", List.of(JSON), "400"),
            c(ranges(101), List.of(JSON), "400"),
            new Case(List.of(ranges(60), ranges(41)), List.of(JSON), "400"),
            c(ranges(100), List.of(JSON), JSON),
            new Case(List.of(ranges(60), ranges(40)), List.of(JSON), JSON));

    /** {@code n} ranges, the last one {@code *}{@code /*}. */
    static String ranges(int n) {
        var out = new ArrayList<String>();
        for (int i = 1; i < n; i++) {
            out.add("x/y" + i);
        }
        out.add("*/*");
        return String.join(", ", out);
    }

    static Suite tests() {
        var server = new AtomicReference<HttpServer>();
        return suite("Accept and negotiate",
                parameterized("negotiation through a real server", CASES, c -> {
                    var target = new StringBuilder("/negotiate?");
                    for (var offer : c.offered()) {
                        target.append("offer=").append(URLEncoder.encode(offer, StandardCharsets.UTF_8)).append('&');
                    }
                    var lines = c.accept().stream().map(a -> "Accept: " + a).toArray(String[]::new);
                    var reply = Support.send(server.get().port(), "GET", target.toString(), lines);
                    switch (c.expected()) {
                        case "406" -> {
                            assertEquals(406, reply.status(), reply.toString());
                            assertEquals("application/problem+json", reply.header("Content-Type"));
                        }
                        case "400" -> {
                            assertEquals(400, reply.status(), reply.toString());
                            assertTrue(reply.json().string("detail").startsWith("Invalid Accept header: "),
                                    reply.toString());
                        }
                        default -> {
                            assertEquals(200, reply.status(), reply.toString());
                            assertEquals(c.expected(), reply.body());
                        }
                    }
                }),
                test("the table has at least 20 cases", () -> assertTrue(CASES.size() >= 20)),
                test("quality: the most specific range, highest weight among equally specific ones", () -> {
                    var accept = AcceptHeader.parse("text/*;q=0.2, text/plain;q=0.4, text/plain;q=0.6, */*;q=0.1");
                    assertEquals(600, accept.quality(MediaType.TEXT_PLAIN));
                    assertEquals(200, accept.quality(MediaType.parse("text/html")));
                    assertEquals(100, accept.quality(MediaType.APPLICATION_JSON));
                    assertEquals(0, AcceptHeader.parse("text/plain").quality(MediaType.APPLICATION_JSON));
                    assertEquals(1_000, AcceptHeader.ANY.quality(MediaType.APPLICATION_JSON));
                    assertEquals(List.of(), AcceptHeader.parse(List.of()).ranges());
                }),
                test("ranges keep their parameters before q and their weight", () -> {
                    var accept = AcceptHeader.parse("text/html;level=1;q=0.5;ext=\"a,b\", */*");
                    assertEquals(List.of(new AcceptHeader.Range(MediaType.parse("text/html;level=1"), 500),
                            new AcceptHeader.Range(MediaType.ALL, 1_000)), accept.ranges());
                    assertEquals("text/html;level=1;q=0.500, */*", accept.toString());
                }),
                test("negotiate: wildcards or nothing offered is a programming error", () -> {
                    assertThrows(IllegalArgumentException.class,
                            () -> AcceptHeader.ANY.negotiate(List.of(MediaType.parse("text/*"))));
                    assertThrows(IllegalArgumentException.class, () -> AcceptHeader.ANY.negotiate(List.of()));
                    assertEquals(Optional.of(MediaType.TEXT_PLAIN),
                            AcceptHeader.ANY.negotiate(List.of(MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON)));
                }),
                test("an invalid Accept message is short and escaped", () -> {
                    var e = assertThrows(IllegalArgumentException.class,
                            () -> AcceptHeader.parse("text/plain;q=\u001b" + "9".repeat(100)));
                    assertTrue(e.getMessage().length() < 200, e.getMessage());
                    for (char ch : e.getMessage().toCharArray()) {
                        assertTrue(ch >= 0x20 && ch < 0x7F, "printable ASCII: " + e.getMessage());
                    }
                }))
                .beforeAll(() -> server.set(Support.serve(Router.builder()
                        .get("/negotiate", r -> {
                            var offered = r.queryAll("offer").stream().map(MediaType::parse).toArray(MediaType[]::new);
                            return Response.text(200, r.negotiate(offered).toString());
                        })
                        .log(new Support.CapturedLog())
                        .build())))
                .afterAll(() -> server.get().close());
    }
}
