package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.web.ForwardedSupport.xfp;
import static spola.web.ForwardedSupport.xff;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import spola.test.Suite;
import spola.web.ForwardedSupport.Case;

/**
 * Criterion 11: no value of {@code X-Forwarded-For} or {@code X-Forwarded-Proto} reaches a 400
 * response, a log line the router writes or an exception message. Every value holds a marker.
 */
final class ForwardedLeakTests {

    private ForwardedLeakTests() {
    }

    /** In every value below; never in what the server gives back or logs. */
    static final String MARKER = "zXf";

    /** Invalid values with the marker, in the shapes of criteria 5 and 6. */
    static final List<Case> MARKED = List.of(
            xff("zXf"),
            xff("zXf.example.com"),
            xff("203.0.113.7:zXf"),
            xff("[zXf::1]"),
            xff("fe80::1%zXf"),
            xff("203.0.113.7 zXf"),
            xff("zXf, 10.0.0.2"),
            xff("203.0.113.7, zXf"),
            xff("zXf\u00a0"),
            new Case("a long value with markers", "X-Forwarded-For: " + "zXf, ".repeat(1_000) + "zXf"),
            new Case("two fields with markers", "X-Forwarded-For: 203.0.113.7", "X-Forwarded-For: zXf-2"),
            xfp("zXf"),
            xfp("httpszXf"),
            xfp("https, zXf"),
            xfp("zXf\u00a0"),
            new Case("two proto fields, one with the marker", "X-Forwarded-Proto: https", "X-Forwarded-Proto: zXf"));

    static Suite tests() {
        return suite("ForwardedHeaders leaks nothing",
                test("marked values: not in the 400, not in the router's log, not in the exception message", () -> {
                    var log = new Support.CapturedLog();
                    var messages = new ArrayList<String>();
                    var router = Router.builder()
                            .filter(ForwardedHeaders.trusting(ForwardedSupport.TRUSTING))
                            .get("/who", request -> spola.http.Response.text(200, "never"))
                            .get("/fail", request -> {
                                throw new IllegalStateException("the handler failed");
                            })
                            .onException(WebException.class, (e, request) -> {
                                synchronized (messages) {
                                    messages.add(e.getMessage());
                                    messages.add(e.toString());
                                    messages.add(e.detail().orElse(""));
                                    messages.add(e.title());
                                }
                                return e.toResponse(request);
                            })
                            .log(log)
                            .build();
                    var untrusting = Router.builder()
                            .filter(ForwardedHeaders.trusting(ForwardedSupport.NOT_TRUSTING))
                            .get("/fail", request -> {
                                throw new IllegalStateException("the handler failed");
                            })
                            .log(log)
                            .build();
                    try (var server = Support.serve(router); var other = Support.serve(untrusting)) {
                        for (var header : MARKED) {
                            for (var target : List.of("/who", "/fail", "/missing")) {
                                var lines = header.lines().toArray(String[]::new);
                                var reply = Support.send(server.port(), "GET", target, lines);
                                assertEquals(400, reply.status(), header + " " + target);
                                assertClean(reply, header);
                                var passed = Support.send(other.port(), "GET", "/fail", lines);
                                assertEquals(500, passed.status(), header.toString());
                                assertClean(passed, header);
                            }
                        }
                        // a valid request to the failing handler: the client's address is in no log line
                        var valid = Support.send(server.port(), "GET", "/fail", "X-Forwarded-For: 198.51.100.77",
                                "X-Forwarded-Proto: https");
                        assertEquals(500, valid.status());
                    }
                    assertEquals(MARKED.size() * 3 * 4, messages.size(), "the mapper saw every 400");
                    for (var message : messages) {
                        assertFalse(message.contains(MARKER), message);
                        assertTrue(message.contains("X-Forwarded-") || message.equals("Bad Request"), message);
                    }
                    var records = log.records();
                    assertEquals(MARKED.size() * 3 + 1, records.size(), "one line per failed handler");
                    for (var record : records) {
                        assertFalse(record.contains(MARKER), record);
                        assertFalse(record.contains("198.51.100.77"), record);
                        assertTrue(record.startsWith("ERROR GET /fail failed; answering 500"), record);
                    }
                    LogTests.assertClean(records);
                }));
    }

    /** No marker in the response, as bytes (ISO-8859-1, so obs-text compares too) and as text. */
    static void assertClean(Support.Reply reply, Case header) {
        var whole = new StringBuilder(reply.body());
        for (var h : reply.headers()) {
            whole.append(h[0]).append(": ").append(h[1]).append('\n');
        }
        var text = whole.toString();
        assertFalse(text.contains(MARKER), header + ": " + text);
        assertFalse(new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1).contains(MARKER),
                header.toString());
    }
}
