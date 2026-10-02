package spola.http;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertNull;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import spola.test.Suite;

/** The response's validation (no splitting, reserved headers, statuses) and what the server adds. */
final class ResponseTests {

    private ResponseTests() {
    }

    /** IMF-fixdate (RFC 9110 5.6.7). */
    static final Pattern IMF_FIXDATE = Pattern.compile(
            "(Mon|Tue|Wed|Thu|Fri|Sat|Sun), \\d{2} (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \\d{4}"
                    + " \\d{2}:\\d{2}:\\d{2} GMT");

    static Suite tests() {
        return suite("responses",
                test("CR, LF or NUL in a header name or value is refused (no response splitting)", () -> {
                    var base = Response.text(200, "x");
                    for (var value : List.of("a\r\nSet-Cookie: x=1", "a\rb", "a\nb", "a\0b", "a\u001bb", "a\u007fb",
                            "Ā", " lead", "trail\t")) {
                        assertThrows(IllegalArgumentException.class, () -> base.withHeader("X-Value", value));
                        assertThrows(IllegalArgumentException.class, () -> Headers.of("X-Value", value));
                    }
                    for (var name : List.of("X\r\nY", "X\nY", "X\0", "X Y", "X:", "", "Ä")) {
                        assertThrows(IllegalArgumentException.class, () -> base.withHeader(name, "v"));
                    }
                    assertThrows(IllegalArgumentException.class,
                            () -> Response.bytes(200, "text/plain\r\nX: y", new byte[0]));
                    assertThrows(IllegalArgumentException.class, () -> Headers.of("odd"));
                    // allowed: HTAB and SP inside, visible ASCII, obs-text
                    assertEquals(Optional.of("a\tb c ÿ"), base.withHeader("X", "a\tb c ÿ").headers().first("x"));
                }),
                test("the headers the server sets are refused, in any case", () -> {
                    for (var name : List.of("Content-Length", "content-length", "Transfer-Encoding", "TRANSFER-ENCODING",
                            "Connection", "connection", "Date", "dAtE")) {
                        var e = assertThrows(IllegalArgumentException.class,
                                () -> Response.text(200, "x").withHeader(name, "1"));
                        assertTrue(e.getMessage().contains("set by the server"), e.getMessage());
                        assertThrows(IllegalArgumentException.class,
                                () -> new Response(200, Headers.of(name, "1"), ResponseBody.EMPTY));
                    }
                }),
                test("1xx, out-of-range statuses and bodies on 204/205/304 are refused", () -> {
                    for (int status : new int[] {100, 101, 103, 199, 0, 99, 600, 999, -1}) {
                        assertThrows(IllegalArgumentException.class, () -> Response.status(status));
                    }
                    assertThrows(IllegalArgumentException.class, () -> Response.text(204, "x"));
                    assertThrows(IllegalArgumentException.class, () -> Response.text(304, ""));
                    assertThrows(IllegalArgumentException.class, () -> Response.stream(205, "text/plain", out -> { }));
                    assertEquals(204, Response.status(204).status());
                }),
                test("204 and 304 go out without a body and without Content-Length", () -> {
                    Handler handler = request -> switch (request.path()) {
                        case "/204" -> Response.status(204).withHeader("X-A", "1");
                        case "/304" -> Response.status(304).withHeader("ETag", "\"v1\"");
                        case "/205" -> Response.status(205);
                        default -> Response.status(200);
                    };
                    try (var server = Support.start(handler); var c = new Support.Client(server.port())) {
                        // pipelined: a body or a length on the first would break the second
                        c.send(Support.get("/204") + Support.get("/304") + Support.get("/205") + Support.get("/200"));
                        var noContent = c.head();
                        assertEquals("HTTP/1.1 204 No Content", noContent.statusLine());
                        assertNull(noContent.header("Content-Length"));
                        assertNull(noContent.header("Transfer-Encoding"));
                        var notModified = c.head();
                        assertEquals("HTTP/1.1 304 Not Modified", notModified.statusLine());
                        assertNull(notModified.header("Content-Length"));
                        assertEquals("\"v1\"", notModified.header("ETag"));
                        var reset = c.head();
                        assertEquals("HTTP/1.1 205 Reset Content", reset.statusLine());
                        assertEquals("0", reset.header("Content-Length"));
                        var ok = c.read();
                        assertEquals("HTTP/1.1 200 OK", ok.statusLine());
                        assertEquals("0", ok.header("Content-Length"));
                        assertTrue(c.silentFor(200), "nothing more was sent");
                    }
                }),
                test("every response has Date in IMF-fixdate and no Server header", () -> {
                    try (var server = Support.start(ErrorTests.failing()); var c = new Support.Client(server.port())) {
                        var replies = new java.util.ArrayList<Support.Reply>();
                        replies.add(c.send(Support.get("/")).read());
                        replies.add(c.send("HEAD / HTTP/1.1\r\nHost: a\r\n\r\n").read(true));
                        replies.add(c.send(Support.get("/runtime")).read());
                        try (var c2 = new Support.Client(server.port())) {
                            replies.add(c2.send("GET / HTTP/9.9\r\n\r\n").read());
                        }
                        try (var c3 = new Support.Client(server.port())) {
                            replies.add(c3.send("GET /x HTTP/1.1\r\n\r\n").read());
                        }
                        for (var reply : replies) {
                            var date = reply.header("Date");
                            assertTrue(date != null && IMF_FIXDATE.matcher(date).matches(), "Date: " + date);
                            var parsed = ZonedDateTime.parse(date, ResponseWriter.IMF_FIXDATE);
                            var skew = Duration.between(parsed, ZonedDateTime.now()).abs();
                            assertTrue(skew.toSeconds() < 60, "Date is now: " + date);
                            assertEquals(1, reply.headerValues("Date").size());
                            assertNull(reply.header("Server"));
                        }
                        assertEquals(List.of(200, 200, 500, 505, 400), replies.stream().map(Support.Reply::status).toList());
                    }
                }),
                test("a single-digit day is written with two digits", () -> {
                    var date = ZonedDateTime.of(1994, 11, 6, 8, 49, 37, 0, java.time.ZoneOffset.UTC);
                    assertEquals("Sun, 06 Nov 1994 08:49:37 GMT", ResponseWriter.IMF_FIXDATE.format(date));
                }),
                test("headers are case-insensitive, ordered and multi-valued", () -> {
                    var headers = Headers.of("Set-Cookie", "a=1", "X-One", "1", "set-cookie", "b=2");
                    assertEquals(List.of("a=1", "b=2"), headers.all("SET-COOKIE"));
                    assertEquals(Optional.of("a=1"), headers.first("set-cookie"));
                    assertTrue(headers.contains("x-one"));
                    assertEquals(Optional.empty(), headers.first("missing"));
                    assertEquals(List.of(), headers.all("missing"));
                    assertEquals(3, headers.size());
                    assertEquals("X-One", headers.fields().get(1).name());
                    assertEquals(headers, Headers.of("Set-Cookie", "a=1").with("X-One", "1").with("set-cookie", "b=2"));
                    assertThrows(UnsupportedOperationException.class,
                            () -> headers.fields().add(new Headers.Field("a", "b")));
                }),
                test("the limits and timeouts refuse nonsense", () -> {
                    assertThrows(IllegalArgumentException.class, () -> HttpLimits.DEFAULT.withMaxBodySize(0));
                    assertThrows(IllegalArgumentException.class, () -> HttpLimits.DEFAULT.withMaxConnections(-1));
                    assertThrows(IllegalArgumentException.class, () -> HttpTimeouts.DEFAULT.withIdleTimeout(Duration.ZERO));
                    assertThrows(IllegalArgumentException.class,
                            () -> HttpTimeouts.DEFAULT.withWriteTimeout(Duration.ofDays(30)));
                    assertThrows(NullPointerException.class, () -> HttpTimeouts.DEFAULT.withReadTimeout(null));
                    assertThrows(IllegalArgumentException.class, () -> HttpServerOptions.DEFAULT.withPort(65_536));
                    assertEquals(new HttpLimits(8_192, 16_384, 100, 10L << 20, 10_000), HttpLimits.DEFAULT);
                    assertEquals(new HttpTimeouts(Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofSeconds(30),
                            Duration.ofSeconds(30), Duration.ofSeconds(30)), HttpTimeouts.DEFAULT);
                }));
    }
}
