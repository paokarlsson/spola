package spola.http;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import spola.test.Suite;

/**
 * Malformed requests over a raw socket, each with the status the server must answer and the
 * section of RFC 9112 or RFC 9110 that decides it. Every case gets exactly that status,
 * {@code Connection: close} and a closed connection.
 *
 * <p>For the {@link #HEAD_CASES} the handler is never called: the server refuses the request
 * from its head. The {@link #BODY_CASES} are broken in the chunked body, which the server reads
 * lazily (only when the handler reads it, so that {@code 100 Continue} can wait for the
 * handler): there the handler is called, but never gets the body; its read throws
 * {@link RequestBodyException} and the server answers the body's status instead of the handler's.
 * A handler that never reads the body gets the same: the server reads the rest of the body
 * before it writes the response, and answers the body's status in place of the handler's.
 */
final class MalformedRequestTests {

    private MalformedRequestTests() {
    }

    /**
     * A malformed request.
     *
     * @param name    what is wrong
     * @param request the bytes sent (ISO-8859-1)
     * @param status  the status the server must answer
     * @param rfc     the section that decides it
     */
    record Bad(String name, String request, int status, String rfc) {
        @Override
        public String toString() {
            return name + " -> " + status + " (" + rfc + ")";
        }
    }

    private static final String H = "Host: a\r\n";

    private static Bad bad(String name, String request, int status, String rfc) {
        return new Bad(name, request, status, rfc);
    }

    /** "GET / HTTP/1.1" with Host and the given field lines. */
    private static String withFields(String... lines) {
        return "GET / HTTP/1.1\r\n" + H + String.join("", lines.length == 0 ? new String[0]
                : java.util.Arrays.stream(lines).map(l -> l + "\r\n").toArray(String[]::new)) + "\r\n";
    }

    /** "POST / HTTP/1.1", Host, the field lines, then the body as given. */
    private static String post(String fields, String body) {
        return "POST / HTTP/1.1\r\n" + H + fields + "\r\n" + body;
    }

    static final List<Bad> HEAD_CASES = List.of(
            // ---- line endings: RFC 9112 2.2 ----
            bad("LF without CR ending the request-line", "GET / HTTP/1.1\n" + H + "\r\n", 400, "RFC 9112 2.2"),
            bad("LF without CR ending a field line", "GET / HTTP/1.1\r\nHost: a\n\r\n", 400, "RFC 9112 2.2"),
            bad("LF without CR ending the header section", "GET / HTTP/1.1\r\n" + H + "\n", 400, "RFC 9112 2.2"),
            bad("CR without LF in the request-line", "GET /\r HTTP/1.1\r\n" + H + "\r\n", 400, "RFC 9112 2.2"),
            bad("CR without LF in a field value", withFields("X: a\rb"), 400, "RFC 9110 5.5, RFC 9112 2.2"),
            bad("too many empty lines before the request-line", "\r\n".repeat(9) + withFields(), 400,
                    "RFC 9112 2.2"),
            // ---- field lines: RFC 9112 5, RFC 9110 5 ----
            bad("obs-fold with SP", withFields("X: a", " b"), 400, "RFC 9112 5.2"),
            bad("obs-fold with HTAB", withFields("X: a", "\tb"), 400, "RFC 9112 5.2"),
            bad("whitespace before the first field line", "GET / HTTP/1.1\r\n Host: a\r\n\r\n", 400, "RFC 9112 2.2"),
            bad("SP between field name and colon", "GET / HTTP/1.1\r\nHost : a\r\n\r\n", 400, "RFC 9112 5.1"),
            bad("HTAB between field name and colon", withFields("Content-Length\t: 0"), 400, "RFC 9112 5.1"),
            bad("empty field name", withFields(": a"), 400, "RFC 9110 5.1"),
            bad("field line without a colon", withFields("X-No-Colon"), 400, "RFC 9112 5"),
            bad("field name that is not a token", withFields("X(y): a"), 400, "RFC 9110 5.1"),
            bad("NUL in a field value", withFields("X: a\0b"), 400, "RFC 9110 5.5"),
            bad("ESC in a field value", withFields("X: a\u001bb"), 400, "RFC 9110 5.5"),
            bad("DEL in a field value", withFields("X: a\u007fb"), 400, "RFC 9110 5.5"),
            // ---- Host: RFC 9112 3.2 ----
            bad("HTTP/1.1 without Host", "GET / HTTP/1.1\r\n\r\n", 400, "RFC 9112 3.2"),
            bad("two Host fields", withFields("Host: b"), 400, "RFC 9112 3.2"),
            bad("two Host fields in HTTP/1.0", "GET / HTTP/1.0\r\nHost: a\r\nHost: b\r\n\r\n", 400, "RFC 9112 3.2"),
            bad("Host with a space", "GET / HTTP/1.1\r\nHost: a b\r\n\r\n", 400, "RFC 9112 3.2, RFC 9110 7.2"),
            bad("Host with userinfo", "GET / HTTP/1.1\r\nHost: user@a\r\n\r\n", 400, "RFC 9112 3.2, RFC 9110 7.2"),
            // ---- version: RFC 9112 2.3, RFC 9110 15.6.6 ----
            bad("lower-case HTTP-name", "GET / http/1.1\r\n" + H + "\r\n", 400, "RFC 9112 2.3"),
            bad("version without a minor digit", "GET / HTTP/1\r\n" + H + "\r\n", 400, "RFC 9112 2.3"),
            bad("two-digit major version", "GET / HTTP/11.1\r\n" + H + "\r\n", 400, "RFC 9112 2.3"),
            bad("SP after the version", "GET / HTTP/1.1 \r\n" + H + "\r\n", 400, "RFC 9112 3"),
            bad("HTTP/2.0", "GET / HTTP/2.0\r\n" + H + "\r\n", 505, "RFC 9110 15.6.6"),
            bad("HTTP/3.0", "GET / HTTP/3.0\r\n" + H + "\r\n", 505, "RFC 9110 15.6.6"),
            bad("HTTP/0.9 in HTTP/1.x syntax", "GET / HTTP/0.9\r\n" + H + "\r\n", 505, "RFC 9110 15.6.6"),
            // ---- request-line and target: RFC 9112 3, 3.2 ----
            bad("control character (U+0001) in the target", "GET /a\u0001b HTTP/1.1\r\n" + H + "\r\n", 400,
                    "RFC 9112 3.2"),
            bad("ESC in the target", "GET /a\u001b[31m HTTP/1.1\r\n" + H + "\r\n", 400, "RFC 9112 3.2"),
            bad("DEL in the target", "GET /a\u007f HTTP/1.1\r\n" + H + "\r\n", 400, "RFC 9112 3.2"),
            bad("byte over 0x7E in the target", "GET /café HTTP/1.1\r\n" + H + "\r\n", 400, "RFC 9112 3.2"),
            bad("SP inside the target", "GET /a b HTTP/1.1\r\n" + H + "\r\n", 400, "RFC 9112 3"),
            bad("two SP after the method", "GET  / HTTP/1.1\r\n" + H + "\r\n", 400, "RFC 9112 3"),
            bad("HTAB instead of SP", "GET\t/ HTTP/1.1\r\n" + H + "\r\n", 400, "RFC 9112 3"),
            bad("no request-target", "GET HTTP/1.1\r\n" + H + "\r\n", 400, "RFC 9112 3"),
            bad("method that is not a token", "GE(T / HTTP/1.1\r\n" + H + "\r\n", 400, "RFC 9110 9.1, 5.6.2"),
            bad("asterisk-form with GET", "GET * HTTP/1.1\r\n" + H + "\r\n", 400, "RFC 9112 3.2.4"),
            bad("authority-form (CONNECT)", "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n", 501,
                    "RFC 9112 3.2.3"),
            bad("authority-form with GET", "GET example.com:80 HTTP/1.1\r\n" + H + "\r\n", 400, "RFC 9112 3.2"),
            bad("fragment in the target", "GET /a#frag HTTP/1.1\r\n" + H + "\r\n", 400, "RFC 9112 3.2, RFC 3986 3.3"),
            bad("broken percent-encoding in the target", "GET /a%zz HTTP/1.1\r\n" + H + "\r\n", 400,
                    "RFC 9112 3.2, RFC 3986 2.1"),
            bad("userinfo in an absolute-form target", "GET http://user@a/ HTTP/1.1\r\n" + H + "\r\n", 400,
                    "RFC 9110 4.2.4"),
            bad("absolute-form with an empty host", "GET http:///x HTTP/1.1\r\n" + H + "\r\n", 400, "RFC 9110 4.2.1"),
            bad("absolute-form with a scheme other than http(s)", "GET ftp://a/ HTTP/1.1\r\n" + H + "\r\n", 400,
                    "RFC 9112 3.2.2"),
            // ---- message body length: RFC 9112 6, RFC 9110 8.6 ----
            bad("Content-Length and Transfer-Encoding", post("Content-Length: 5\r\nTransfer-Encoding: chunked\r\n",
                    "0\r\n\r\n"), 400, "RFC 9112 6.1, 6.3"),
            bad("Transfer-Encoding and Content-Length", post("Transfer-Encoding: chunked\r\nContent-Length: 5\r\n",
                    "0\r\n\r\n"), 400, "RFC 9112 6.1, 6.3"),
            bad("two Content-Length with the same value", post("Content-Length: 5\r\nContent-Length: 5\r\n", "hello"),
                    400, "RFC 9112 6.3, RFC 9110 8.6"),
            bad("two Content-Length with different values", post("Content-Length: 5\r\nContent-Length: 6\r\n",
                    "hello!"), 400, "RFC 9112 6.3, RFC 9110 8.6"),
            bad("Content-Length as a list", post("Content-Length: 5, 5\r\n", "hello"), 400,
                    "RFC 9112 6.3, RFC 9110 8.6"),
            bad("Content-Length with +", post("Content-Length: +5\r\n", "hello"), 400, "RFC 9110 8.6"),
            bad("Content-Length with -", post("Content-Length: -5\r\n", "hello"), 400, "RFC 9110 8.6"),
            bad("Content-Length with SP inside", post("Content-Length: 1 0\r\n", "0123456789"), 400, "RFC 9110 8.6"),
            bad("Content-Length in hex with 0x", post("Content-Length: 0x10\r\n", ""), 400, "RFC 9110 8.6"),
            bad("Content-Length as a hex digit", post("Content-Length: a\r\n", ""), 400, "RFC 9110 8.6"),
            bad("Content-Length one over Long.MAX_VALUE", post("Content-Length: 9223372036854775808\r\n", ""), 400,
                    "RFC 9110 8.6"),
            bad("Content-Length far over long", post("Content-Length: 99999999999999999999999\r\n", ""), 400,
                    "RFC 9110 8.6"),
            bad("empty Content-Length", post("Content-Length:\r\n", ""), 400, "RFC 9110 8.6"),
            bad("Transfer-Encoding: chunked, identity", post("Transfer-Encoding: chunked, identity\r\n", "0\r\n\r\n"),
                    400, "RFC 9112 6.3 (chunked not final)"),
            bad("Transfer-Encoding: gzip, chunked", post("Transfer-Encoding: gzip, chunked\r\n", "0\r\n\r\n"), 501,
                    "RFC 9112 6.1"),
            bad("Transfer-Encoding: xchunked", post("Transfer-Encoding: xchunked\r\n", "0\r\n\r\n"), 400,
                    "RFC 9112 6.3 (chunked not final)"),
            bad("Transfer-Encoding: identity", post("Transfer-Encoding: identity\r\n", ""), 400,
                    "RFC 9112 6.3 (chunked not final)"),
            bad("chunked twice in two Transfer-Encoding fields",
                    post("Transfer-Encoding: chunked\r\nTransfer-Encoding: chunked\r\n", "0\r\n\r\n"), 501,
                    "RFC 9112 6.1, 7"),
            bad("Transfer-Encoding: chunked in HTTP/1.0",
                    "POST / HTTP/1.0\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n", 400, "RFC 9112 6.1"),
            bad("Content-Length over maxBodySize", post("Content-Length: 10485761\r\n", "x"), 413,
                    "RFC 9110 15.5.14"),
            bad("expectation other than 100-continue", withFields("Expect: teapot"), 417, "RFC 9110 10.1.1"),
            // ---- limits: RFC 9110 5.4, 15.5.15 ----
            bad("request-line longer than maxRequestLineLength",
                    "GET /" + "a".repeat(8_200) + " HTTP/1.1\r\n" + H + "\r\n", 414, "RFC 9112 3, RFC 9110 15.5.15"),
            bad("one field line over maxHeaderSectionSize", withFields("X: " + "v".repeat(16_400)), 431,
                    "RFC 9110 5.4"),
            bad("header section over maxHeaderSectionSize in many fields",
                    withFields(java.util.stream.IntStream.range(0, 40).mapToObj(i -> "X-" + i + ": " + "v".repeat(500))
                            .toArray(String[]::new)), 431, "RFC 9110 5.4"),
            bad("more than maxHeaderCount field lines",
                    withFields(java.util.stream.IntStream.range(0, 100).mapToObj(i -> "X-" + i + ": v")
                            .toArray(String[]::new)), 431, "RFC 9110 5.4"));

    /** Broken in the chunked body: the handler is called but its body read fails. */
    static final List<Bad> BODY_CASES = List.of(
            bad("chunk size with SP before it", post("Transfer-Encoding: chunked\r\n", " 5\r\nhello\r\n0\r\n\r\n"),
                    400, "RFC 9112 7.1"),
            bad("chunk size with SP after it", post("Transfer-Encoding: chunked\r\n", "5 \r\nhello\r\n0\r\n\r\n"),
                    400, "RFC 9112 7.1"),
            bad("chunk size with 0x", post("Transfer-Encoding: chunked\r\n", "0x5\r\nhello\r\n0\r\n\r\n"), 400,
                    "RFC 9112 7.1"),
            bad("chunk size with -", post("Transfer-Encoding: chunked\r\n", "-5\r\nhello\r\n0\r\n\r\n"), 400,
                    "RFC 9112 7.1"),
            bad("chunk size over 63 bits", post("Transfer-Encoding: chunked\r\n", "10000000000000000\r\nhello\r\n"),
                    400, "RFC 9112 7.1"),
            bad("empty chunk-size line", post("Transfer-Encoding: chunked\r\n", "\r\nhello\r\n0\r\n\r\n"), 400,
                    "RFC 9112 7.1"),
            bad("chunk-size line ending in LF only", post("Transfer-Encoding: chunked\r\n", "5\nhello\r\n0\r\n\r\n"),
                    400, "RFC 9112 7.1, 2.2"),
            bad("no CRLF after the chunk data", post("Transfer-Encoding: chunked\r\n", "5\r\nhelloX\r\n0\r\n\r\n"),
                    400, "RFC 9112 7.1"),
            bad("chunk extension without a name", post("Transfer-Encoding: chunked\r\n", "5;\r\nhello\r\n0\r\n\r\n"),
                    400, "RFC 9112 7.1.1"),
            bad("chunk-size line over the limit", post("Transfer-Encoding: chunked\r\n",
                    "5;a=" + "b".repeat(5_000) + "\r\nhello\r\n0\r\n\r\n"), 400, "RFC 9112 7.1.1"),
            bad("obs-fold in the trailer section", post("Transfer-Encoding: chunked\r\n",
                    "5\r\nhello\r\n0\r\nT: a\r\n b\r\n\r\n"), 400, "RFC 9112 7.1.2, 5.2"),
            bad("NUL in a trailer value", post("Transfer-Encoding: chunked\r\n", "5\r\nhello\r\n0\r\nT: a\0\r\n\r\n"),
                    400, "RFC 9112 7.1.2, RFC 9110 5.5"),
            bad("trailer section over maxHeaderSectionSize", post("Transfer-Encoding: chunked\r\n",
                    "5\r\nhello\r\n0\r\nT: " + "v".repeat(16_400) + "\r\n\r\n"), 431, "RFC 9112 7.1.2, RFC 9110 5.4"),
            bad("chunked body over maxBodySize", post("Transfer-Encoding: chunked\r\n", "a00001\r\n"), 413,
                    "RFC 9110 15.5.14"));

    /** Sends the request and checks status, Connection: close and the closed connection. */
    private static void refused(Transport t, Bad bad, AtomicInteger calls, AtomicInteger bodies) throws Exception {
        refused(t, bad, request -> {
            calls.incrementAndGet();
            request.bytes();
            bodies.incrementAndGet();
            return Response.text(200, "handled");
        });
    }

    private static void refused(Transport t, Bad bad, Handler handler) throws Exception {
        try (var server = t.start(handler); var c = t.client(server.port())) {
            c.send(bad.request());
            var reply = c.read();
            assertEquals(bad.status(), reply.status(), "status for " + Support.visible(bad.request()));
            assertTrue(reply.closes(), "Connection: close");
            assertTrue(c.closedByServer(), "the server closed the connection");
            assertFalse(reply.text().contains("handled"));
        }
    }

    static Suite tests() {
        var calls = new AtomicInteger();
        var bodies = new AtomicInteger();
        return suite("malformed requests",
                test("at least 45 head cases, each with a status and an RFC section", () -> {
                    assertTrue(HEAD_CASES.size() >= 45, "only " + HEAD_CASES.size() + " cases");
                    var names = new ArrayList<String>();
                    for (var bad : HEAD_CASES) {
                        assertTrue(bad.rfc().startsWith("RFC 9112") || bad.rfc().startsWith("RFC 9110"), bad.name());
                        names.add(bad.name());
                    }
                    assertEquals((long) names.size(), names.stream().distinct().count());
                }),
                parameterized("refused from the head; the handler is never called", HEAD_CASES, bad -> {
                    calls.set(0);
                    refused(Transport.PLAIN, bad, calls, bodies);
                    assertEquals(0, calls.get(), "handler calls");
                }),
                parameterized("refused in the chunked body; the handler never gets the body", BODY_CASES, bad -> {
                    calls.set(0);
                    bodies.set(0);
                    refused(Transport.PLAIN, bad, calls, bodies);
                    assertEquals(0, bodies.get(), "bodies the handler got");
                }),
                parameterized("refused in the chunked body when the handler never reads it", BODY_CASES, bad -> {
                    calls.set(0);
                    refused(Transport.PLAIN, bad, request -> {
                        calls.incrementAndGet();
                        return Response.text(200, "handled");
                    });
                    assertEquals(1, calls.get(), "handler calls");
                }),
                parameterized("refused in the chunked body when the handler reads only part of it", BODY_CASES,
                        bad -> refused(Transport.PLAIN, bad, request -> {
                            request.body().read(); // one byte; a body broken from the start throws here
                            return Response.text(200, "handled");
                        })),
                test("exactly at the limits is accepted", () -> {
                    var limits = HttpLimits.DEFAULT;
                    var line = "GET /" + "a".repeat(limits.maxRequestLineLength() - "GET / HTTP/1.1".length())
                            + " HTTP/1.1";
                    assertEquals(limits.maxRequestLineLength(), line.length());
                    // Host line (9 bytes with CRLF) plus one field filling the rest of the section
                    var filler = "X: " + "v".repeat(limits.maxHeaderSectionSize() - "Host: a\r\n".length() - 5);
                    var many = java.util.stream.IntStream.range(0, 99).mapToObj(i -> "X-" + i + ": v")
                            .toArray(String[]::new);
                    try (var server = Support.start(request -> Response.status(204));
                         var c = new Support.Client(server.port())) {
                        assertEquals(204, c.send(line + "\r\n" + H + "\r\n").read().status());
                        assertEquals(204, c.send(withFields(filler)).read().status());
                        assertEquals(204, c.send(withFields(many)).read().status());
                        assertEquals(204, c.send("\r\n".repeat(8) + withFields()).read().status());
                        assertEquals(204, c.send(post("Content-Length: 10485760\r\n", "")).send(new byte[10 << 20])
                                .read().status());
                    }
                }),
                test("a smuggled request hidden in a body never reaches the handler", () -> smuggling(Transport.PLAIN)));
    }

    /**
     * Over {@code t}: every {@code step}-th case of both tables (the handler is never called for
     * the head cases, never gets the body for the body cases) and the smuggling attempts.
     */
    static Suite selection(Transport t, int step) {
        var calls = new AtomicInteger();
        var bodies = new AtomicInteger();
        return suite("malformed requests",
                parameterized("refused from the head; the handler is never called", every(HEAD_CASES, step), bad -> {
                    calls.set(0);
                    refused(t, bad, calls, bodies);
                    assertEquals(0, calls.get(), "handler calls");
                }),
                parameterized("refused in the chunked body; the handler never gets the body", every(BODY_CASES, step),
                        bad -> {
                            bodies.set(0);
                            refused(t, bad, calls, bodies);
                            assertEquals(0, bodies.get(), "bodies the handler got");
                        }),
                test("a smuggled request hidden in a body never reaches the handler", () -> smuggling(t)));
    }

    private static List<Bad> every(List<Bad> cases, int step) {
        var selected = new ArrayList<Bad>();
        for (int i = 0; i < cases.size(); i += step) {
            selected.add(cases.get(i));
        }
        return selected;
    }

    private static void smuggling(Transport t) throws Exception {
        var paths = new CopyOnWriteArrayList<String>();
        Handler handler = request -> {
            paths.add(request.path());
            request.bytes();
            return Response.text(200, "ok");
        };
        var hidden = "GET /admin HTTP/1.1\r\nHost: a\r\n\r\n";
        var attempts = List.of(
                // CL.TE: a front end using Content-Length forwards all; we must not use chunked
                "POST /a HTTP/1.1\r\nHost: a\r\nContent-Length: " + (5 + hidden.length())
                        + "\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n" + hidden,
                // TE.CL: a front end using chunked forwards all; we must not use Content-Length
                "POST /a HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\nContent-Length: 4\r\n\r\n"
                        + Integer.toHexString(hidden.length()) + "\r\n" + hidden + "\r\n0\r\n\r\n",
                // obfuscated Transfer-Encoding names and values
                "POST /a HTTP/1.1\r\nHost: a\r\nContent-Length: " + (5 + hidden.length())
                        + "\r\nTransfer-Encoding : chunked\r\n\r\n0\r\n\r\n" + hidden,
                "POST /a HTTP/1.1\r\nHost: a\r\nContent-Length: " + (5 + hidden.length())
                        + "\r\nTransfer-Encoding: x\r\n chunked\r\n\r\n0\r\n\r\n" + hidden,
                "POST /a HTTP/1.1\r\nHost: a\r\nContent-Length: " + (5 + hidden.length())
                        + "\r\nTransfer-Encoding: xchunked\r\n\r\n0\r\n\r\n" + hidden,
                // a bare LF that a lenient parser would take as a line end
                "POST /a HTTP/1.1\r\nHost: a\r\nX: y\nTransfer-Encoding: chunked\r\nContent-Length: "
                        + (5 + hidden.length()) + "\r\n\r\n0\r\n\r\n" + hidden,
                // two Content-Length values, the smaller one first
                "POST /a HTTP/1.1\r\nHost: a\r\nContent-Length: 0\r\nContent-Length: " + hidden.length()
                        + "\r\n\r\n" + hidden);
        try (var server = t.start(handler)) {
            for (var attempt : attempts) {
                try (var c = t.client(server.port())) {
                    var reply = c.send(attempt).read();
                    assertEquals(400, reply.status(), Support.visible(attempt));
                    assertTrue(reply.closes());
                    assertTrue(c.closedByServer());
                }
            }
            // after a good request on the same connection, too
            try (var c = t.client(server.port())) {
                c.send(Support.get("/ok") + attempts.getFirst());
                assertEquals(200, c.read().status());
                assertEquals(400, c.read().status());
                assertTrue(c.closedByServer());
            }
        }
        assertEquals(List.of("/ok"), List.copyOf(paths));
    }
}
