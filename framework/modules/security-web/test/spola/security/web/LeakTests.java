package spola.security.web;

import static spola.security.web.Support.ADA;
import static spola.security.web.Support.ADA_PASSWORD;
import static spola.security.web.Support.basic;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.ArrayList;
import java.util.List;
import spola.http.Response;
import spola.test.Suite;

/**
 * Criterion 11: no username, password or {@code Authorization} value in any response or log
 * line of the suite, and no control character from a request in a log line. Runs last: it
 * reads every reply and every router log line the other tests produced (they all go through
 * {@link Support}).
 */
final class LeakTests {

    private LeakTests() {
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

    /** The secrets: the markers every test username and password holds, the RFC examples, every value sent. */
    static List<String> secrets() {
        var secrets = new ArrayList<>(List.of(Support.USER_MARKER, Support.PASSWORD_MARKER, "Aladdin", "open sesame",
                "123£", "QWxhZGRpbjpvcGVuIHNlc2FtZQ", "dGVzdDoxMjPCow"));
        List<String> sent;
        synchronized (Support.SENT) {
            sent = List.copyOf(Support.SENT);
        }
        for (var value : sent) {
            if (value.length() >= 8) {
                secrets.add(value);
            }
            int space = value.indexOf(' ');
            var credentials = space < 0 ? "" : value.substring(space).strip();
            if (credentials.length() >= 8) {
                secrets.add(credentials);
            }
        }
        return secrets;
    }

    static Suite tests() {
        return suite("secrets and log lines",
                test("control characters from a request never reach a log line raw", () -> {
                    var security = WebSecurity.builder()
                            .authentication(HttpBasic.of(Support.authenticator(), "app"))
                            .rule(request -> {
                                throw new IllegalStateException("matcher saw q=" + request.query("q").orElse("") + " path="
                                        + request.path());
                            }, AccessRule.permitAll())
                            .build();
                    var router = Support.router().filter(security.filter()).get("/x", request -> Response.text(200, "x"))
                            .build();
                    var before = Support.LOG.records().size();
                    try (var server = Support.serve(router)) {
                        for (var target : List.of("/x?q=%0D%0AERROR%20forged%1B%5B31m", "/x%0A?q=%E2%80%A8", "/%C2%85?q=%7F")) {
                            assertEquals(500, Support.send(server.port(), "GET", target).status(), target);
                            assertEquals(500, Support.send(server.port(), "GET", target, basic(ADA, ADA_PASSWORD)).status());
                            // Rejected before the rules: 401, and nothing logged.
                            assertEquals(401, Support.send(server.port(), "GET", target, "Basic\t\t" + ADA).status());
                        }
                    }
                    var lines = Support.LOG.records().subList(before, Support.LOG.records().size());
                    assertEquals(6, lines.size(), String.valueOf(lines));
                    assertClean(lines);
                    assertTrue(lines.getFirst().contains("matcher saw q=\\x0d\\x0aERROR forged\\x1b[31m"), lines.getFirst());
                }),
                test("no response and no log line of the suite holds a username, a password or an Authorization value",
                        () -> {
                            List<Support.Reply> replies;
                            synchronized (Support.REPLIES) {
                                replies = List.copyOf(Support.REPLIES);
                            }
                            var lines = Support.LOG.records();
                            assertTrue(replies.size() > 300, "only " + replies.size() + " replies recorded");
                            assertTrue(lines.size() > 20, "only " + lines.size() + " log lines recorded");
                            var secrets = secrets();
                            assertTrue(secrets.size() > 100, "only " + secrets.size() + " secrets");
                            var failures = new ArrayList<String>();
                            for (var reply : replies) {
                                var text = reply.head() + "\r\n\r\n" + reply.body();
                                for (var secret : secrets) {
                                    if (text.contains(secret)) {
                                        failures.add("response " + reply.status() + " holds " + secret);
                                    }
                                }
                            }
                            for (var line : lines) {
                                for (var secret : secrets) {
                                    if (line.contains(secret)) {
                                        failures.add("log line holds " + secret + ": " + line.lines().findFirst().orElse(""));
                                    }
                                }
                            }
                            assertEquals(List.of(), failures);
                            assertClean(lines);
                        }));
    }
}
