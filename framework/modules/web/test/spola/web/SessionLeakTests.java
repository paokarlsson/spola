package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.web.SessionSupport.COUNT;
import static spola.web.SessionSupport.cookie;
import static spola.web.SessionSupport.issued;
import static spola.web.SessionSupport.send;
import static spola.web.SessionSupport.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import spola.http.Response;
import spola.test.Suite;

/**
 * Criterion 13: no session id that the tests saw in a {@code Set-Cookie} is in any log line the
 * routers wrote during the session tests, in a {@code toString()} of {@code Session},
 * {@code Sessions} or {@code SessionOptions}, in an exception message or in a body. Runs last:
 * it reads what the other session tests recorded through {@link SessionSupport}.
 */
final class SessionLeakTests {

    private SessionLeakTests() {
    }

    /** Every place in {@code texts} where an id of {@code ids} appears. */
    static List<String> leaks(String what, Iterable<String> texts, Set<String> ids) {
        var found = new ArrayList<String>();
        for (var text : texts) {
            for (int i = 0; i + Sessions.ID_LENGTH <= text.length(); i++) {
                if (ids.contains(text.substring(i, i + Sessions.ID_LENGTH))) {
                    found.add(what + " holds an id: " + text.lines().findFirst().orElse(""));
                    break;
                }
            }
        }
        return found;
    }

    static Suite tests() {
        return suite("session ids never leak",
                test("handlers and filters that fail with a session log no id", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT);
                    var router = SessionSupport.routes(SessionSupport.router().filter(sessions.filter()))
                            .post("/fail", r -> {
                                var session = Sessions.getOrCreate(r);
                                session.put(COUNT, 1);
                                throw new IllegalStateException("failed with " + session + " in " + sessions + " for "
                                        + r + " and " + r.cookies().keySet());
                            })
                            .build();
                    int before = SessionSupport.LOG.records().size();
                    try (var server = Support.serve(router)) {
                        var id = issued(send(server, "POST", "/count"));
                        var failed = send(server, "POST", "/fail", cookie(id));
                        assertEquals(500, failed.status());
                        var created = send(server, "POST", "/fail");
                        assertEquals(500, created.status());
                        issued(created); // the session the failing handler created is still announced
                        text(sessions);
                        text(SessionOptions.DEFAULT);
                    }
                    assertEquals(2, SessionSupport.LOG.records().size() - before);
                }),
                test("no log line, toString, exception message or body of the session tests holds a session id", () -> {
                    var ids = Set.copyOf(SessionSupport.IDS);
                    assertTrue(ids.size() > 10_000, "only " + ids.size() + " ids recorded");
                    var lines = SessionSupport.LOG.records();
                    assertTrue(lines.size() >= 9, "only " + lines.size() + " log lines recorded");
                    var texts = List.copyOf(SessionSupport.TEXTS);
                    assertTrue(texts.size() > 10_000, "only " + texts.size() + " texts recorded");
                    var failures = new ArrayList<String>();
                    failures.addAll(leaks("log line", lines, ids));
                    failures.addAll(leaks("text", texts, ids));
                    assertEquals(List.of(), failures);
                    var marker = ids.iterator().next();
                    assertEquals(1, leaks("marker", List.of("x" + marker + "y"), ids).size(), "the check finds an id");
                    LogTests.assertClean(lines);
                }));
    }
}
