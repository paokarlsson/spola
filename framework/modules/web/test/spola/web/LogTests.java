package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;
import spola.test.Suite;

/** Criterion 13: no log line from spola.web holds a raw control character. */
final class LogTests {

    private LogTests() {
    }

    /** Fails if a line of a record holds a control character (TAB starts stack trace lines and is allowed). */
    static void assertClean(List<String> records) {
        for (var record : records) {
            for (var line : record.split("\n", -1)) {
                for (char c : line.toCharArray()) {
                    boolean control = (c < 0x20 && c != '\t') || (c >= 0x7F && c <= 0x9F) || c == ' ' || c == ' ';
                    assertFalse(control, "control character U+" + Integer.toHexString(c) + " in log line: "
                            + line.replace("\r", "<CR>").replace("\u001b", "<ESC>"));
                }
            }
        }
    }

    static Suite tests() {
        return suite("log lines",
                test("percent-encoded CR, LF and ESC in the path and the query never reach a log line raw", () -> {
                    var log = new Support.CapturedLog();
                    var router = Router.builder()
                            .get("/echo/{name}", r -> {
                                throw new IllegalStateException("failed for " + r.pathParam("name") + " q="
                                        + r.query("q").orElse("") + " path=" + r.path(),
                                        new IllegalArgumentException("cause " + r.query("q").orElse("")));
                            })
                            .get("/mapped", r -> {
                                throw new UnsupportedOperationException("value " + r.query("q").orElse(""));
                            })
                            .onException(UnsupportedOperationException.class, (e, r) -> {
                                throw new IllegalStateException("mapper saw " + e.getMessage() + " at " + r.path());
                            })
                            .filter((request, next) -> {
                                if (request.query("filter").isPresent()) {
                                    throw new IllegalStateException("filter " + request.query("filter").get()
                                            + " " + request.path());
                                }
                                return next.proceed(request);
                            })
                            .log(log)
                            .build();
                    try (var server = Support.serve(router)) {
                        var targets = List.of(
                                "/echo/%C2%85%E2%80%A8x?q=%0D%0A%1B%5B31mFAKE%20ERROR%20line",
                                "/echo/a?q=%0AINFO%20forged&q=2",
                                "/echo/%C2%9B31m?q=%0D",
                                "/mapped?q=%0A%0D%1B",
                                "/echo/x?filter=%0A%1B%0D",
                                "/bad%0Apath?filter=%0D%0A",
                                "/bad%1B?filter=x");
                        for (var target : targets) {
                            var reply = Support.send(server.port(), "GET", target);
                            assertEquals(500, reply.status(), target + " " + reply);
                        }
                    }
                    var records = log.records();
                    assertEquals(7, records.size(), String.valueOf(records));
                    assertClean(records);
                    assertTrue(records.getFirst().startsWith("ERROR GET /echo/\\x85\\u2028x failed; answering 500\n"),
                            records.getFirst());
                    assertTrue(records.getFirst().contains("q=\\x0d\\x0a\\x1b[31mFAKE ERROR line"), records.getFirst());
                    assertTrue(records.getFirst().contains("Caused by: java.lang.IllegalArgumentException: cause \\x0d\\x0a"),
                            records.getFirst());
                    assertTrue(records.get(3).contains("mapper saw value \\x0a\\x0d\\x1b"), records.get(3));
                }),
                test("a long path and message are shortened in the log line", () -> {
                    var log = new Support.CapturedLog();
                    var router = Router.builder()
                            .get("/{p...}", r -> {
                                throw new IllegalStateException("m".repeat(10_000));
                            })
                            .log(log)
                            .build();
                    try (var server = Support.serve(router)) {
                        Support.send(server.port(), "GET", "/" + "s".repeat(3_000));
                    }
                    var first = log.records().getFirst().split("\n")[0];
                    assertTrue(first.length() < 300, "first line has " + first.length() + " characters");
                    var second = log.records().getFirst().split("\n")[1];
                    assertTrue(second.length() < 300, "second line has " + second.length() + " characters");
                    assertClean(log.records());
                }));
    }
}
