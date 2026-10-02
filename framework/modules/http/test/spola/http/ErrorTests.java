package spola.http;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.charset.StandardCharsets;
import spola.test.Suite;

/** Failures in handlers and body writers, and what the server writes about them (log and body). */
final class ErrorTests {

    private ErrorTests() {
    }

    static Handler failing() {
        return request -> switch (request.path()) {
            case "/runtime" -> throw new IllegalStateException("secret detail from the handler");
            case "/error" -> throw new StackOverflowError("deep");
            case "/null" -> null;
            case "/stream-late" -> Response.stream(200, "text/plain", out -> {
                out.write("partial".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                throw new IllegalStateException("late failure");
            });
            case "/stream-early" -> Response.stream(200, "text/plain", out -> {
                throw new IllegalStateException("early failure");
            });
            case "/echo-in-exception" -> throw new IllegalArgumentException(
                    "bad input: " + new String(request.bytes(), StandardCharsets.ISO_8859_1));
            default -> Response.text(200, "fine");
        };
    }

    /** Control characters that must never reach a log line or an error body raw. */
    static boolean hasRawControl(String text, boolean allowLineBreaks) {
        for (char c : text.toCharArray()) {
            if ((c < 0x20 && !(allowLineBreaks && (c == '\n' || c == '\t'))) || (c >= 0x7F && c <= 0x9F)) {
                return true;
            }
        }
        return false;
    }

    static Suite tests() {
        return suite("errors",
                test("a RuntimeException from the handler gives 500 without its message or stack trace", () -> {
                    var log = new Support.CapturedLog();
                    try (var server = HttpServer.start(Support.options(), failing(), log);
                         var c = new Support.Client(server.port())) {
                        var reply = c.send(Support.get("/runtime")).read();
                        assertEquals(500, reply.status());
                        assertEquals("Internal Server Error\n", reply.text());
                        assertFalse(reply.text().contains("secret"));
                        assertTrue(reply.closes());
                        assertTrue(c.closedByServer());
                    }
                    var logged = String.join("\n", log.records());
                    assertTrue(logged.contains("java.lang.IllegalStateException: secret detail from the handler"),
                            "logged: " + logged);
                    assertTrue(logged.contains("spola.http.ErrorTests.lambda"), "with the stack trace: " + logged);
                }),
                test("the logged copy of an exception is escaped, keeps the trace and cuts cause cycles", () -> {
                    var cause = new IllegalStateException("inner \u001b[2J\r\nforged");
                    var outer = new RuntimeException("outer\0", cause);
                    cause.initCause(new java.io.IOException((String) null, outer)); // a cycle back to outer
                    var copy = LoggedThrowable.of(outer);
                    assertEquals("java.lang.RuntimeException: outer\\x00", copy.toString());
                    assertEquals("java.lang.IllegalStateException: inner \\x1b[2J\\x0d\\x0aforged",
                            copy.getCause().toString());
                    assertEquals("java.io.IOException", copy.getCause().getCause().toString());
                    assertEquals(null, copy.getCause().getCause().getCause());
                    assertEquals(java.util.List.of(outer.getStackTrace()), java.util.List.of(copy.getStackTrace()));
                    assertEquals("a\\\\b\\u2028" + "x".repeat(196) + "...", Syntax.escape("a\\b\u2028" + "x".repeat(300)));
                }),
                test("an Error from the handler does not kill the server", () -> {
                    try (var server = Support.start(failing())) {
                        for (int i = 0; i < 3; i++) {
                            try (var c = new Support.Client(server.port())) {
                                assertEquals(500, c.send(Support.get("/error")).read().status());
                            }
                        }
                        try (var c = new Support.Client(server.port())) {
                            assertEquals("fine", c.send(Support.get("/")).read().text());
                        }
                    }
                }),
                test("a handler that returns null gives 500", () -> {
                    try (var server = Support.start(failing()); var c = new Support.Client(server.port())) {
                        assertEquals(500, c.send(Support.get("/null")).read().status());
                    }
                }),
                test("a failure after a streaming response started aborts the connection", () -> {
                    try (var server = Support.start(failing()); var c = new Support.Client(server.port())) {
                        var head = c.send(Support.get("/stream-late")).head();
                        assertEquals(200, head.status());
                        assertEquals("chunked", head.header("Transfer-Encoding"));
                        var rest = Support.ascii(c.rest());
                        assertEquals("7\r\npartial\r\n", rest);
                        assertFalse(rest.contains("0\r\n\r\n"), "no last chunk: the response is not complete");
                    }
                }),
                test("a failure before a streaming response wrote anything gives a clean 500", () -> {
                    try (var server = Support.start(failing()); var c = new Support.Client(server.port())) {
                        var reply = c.send(Support.get("/stream-early")).read();
                        assertEquals(500, reply.status());
                        assertEquals("Internal Server Error\n", reply.text());
                        assertTrue(reply.closes());
                    }
                }),
                test("no log line and no error body holds raw control characters from a request", () -> {
                    var log = new Support.CapturedLog();
                    var bodies = new java.util.ArrayList<String>();
                    var evil = "\u001b[2J\0\r\n2026-01-01 FAKE LOG LINE\u0085\u009b";
                    try (var server = HttpServer.start(Support.options(), failing(), log)) {
                        var requests = java.util.List.of(
                                "GET /\u001b[31mred HTTP/1.1\r\nHost: a\r\n\r\n",
                                "GET /a\0b HTTP/1.1\r\nHost: a\r\n\r\n",
                                "GET / HTTP/1.1\r\nHost: a\r\nX-Evil: \u001b]0;title\u0007\r\n\r\n",
                                "GET / HTTP/1.1\r\nHost: a\r\nX-Nul: a\0b\r\n\r\n",
                                "GET / HTTP/1.1\r\nHost: \u001b[2J\r\n\r\n",
                                "\u001b[2J / HTTP/1.1\r\nHost: a\r\n\r\n",
                                "POST /echo-in-exception HTTP/1.1\r\nHost: a\r\nContent-Length: " + evil.length()
                                        + "\r\n\r\n" + evil,
                                "POST /echo-in-exception HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n5\u001b\r\nhello\r\n");
                        for (var request : requests) {
                            try (var c = new Support.Client(server.port())) {
                                var reply = c.send(request).read();
                                assertTrue(reply.status() == 400 || reply.status() == 500, reply.statusLine());
                                bodies.add(reply.text());
                            }
                        }
                    }
                    for (var body : bodies) {
                        assertFalse(hasRawControl(body.strip(), false), "error body: " + Support.visible(body));
                    }
                    var records = log.records();
                    assertTrue(records.stream().anyMatch(r -> r.contains("FAKE LOG LINE")), "the exception was logged");
                    for (var record : records) {
                        assertFalse(hasRawControl(record, true), "log record: " + Support.visible(record));
                        for (var line : record.split("\n")) {
                            assertFalse(line.startsWith("2026-01-01"), "a forged log line: " + Support.visible(line));
                        }
                        // the first line (the message) has no line break at all
                        assertFalse(hasRawControl(record.lines().findFirst().orElse(""), false));
                    }
                }));
    }
}
