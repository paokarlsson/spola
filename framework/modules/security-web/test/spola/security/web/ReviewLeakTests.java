package spola.security.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import spola.http.Response;
import spola.security.Identity;
import spola.test.Suite;
import spola.web.SessionOptions;
import spola.web.Sessions;

/**
 * Uppdrag 15a, criterion 9: no session id, CSRF token or password in a response (except the
 * value in the response's own {@code Set-Cookie}), a log line, an exception message or a
 * {@code toString()} from the new paths: the limit per user, the new session for another user,
 * the 503 at {@code maxSessions} and the {@code Cache-Control} rules. Runs after the other
 * tests of 15a and reads what they recorded through {@link ReviewSupport}.
 */
final class ReviewLeakTests {

    private ReviewLeakTests() {
    }

    /** {@code Set-Cookie: SESSION=<value>} or {@code XSRF-TOKEN=<value>} in a response head. */
    static final Pattern OWN_COOKIE = Pattern.compile("(?im)^(Set-Cookie:\\s*(?:SESSION|XSRF-TOKEN)=)([^;\\r\\n]*)");

    static Suite tests() {
        return suite("secrets on the new paths",
                test("a handler that fails after a login that ended the user's oldest session and another user's"
                        + " session logs no id", () -> {
                    var sessions = Sessions.inMemory(SessionOptions.DEFAULT.withMaxSessions(3));
                    var security = ReviewSupport.loginSecurity(sessions, 1);
                    var routes = Support.router()
                            .post("/login-and-fail", request -> {
                                WebSecurity.login(request, new Identity(LoginSupport.ANNA, Set.of("USER")));
                                var session = Sessions.find(request).orElseThrow();
                                throw new IllegalStateException("failed with " + session + " in " + sessions + " by "
                                        + security + " for " + request.cookies().keySet());
                            })
                            .get("/plain", request -> Response.text(200, "plain"));
                    var router = LoginExample.router(routes, security, LoginSupport.authenticator());
                    int before = Support.LOG.records().size();
                    try (var server = Support.serve(router)) {
                        var bertil = ReviewSupport.issued(ReviewSupport.login(server, LoginSupport.BERTIL, null));
                        var anna = ReviewSupport.issued(ReviewSupport.login(server, LoginSupport.ANNA, null));
                        // as bertil's browser: anna's login over bertil's session, which ends anna's older one too
                        var failed = ReviewSupport.raw(server, "POST", "/login-and-fail", null,
                                LoginSupport.cookie(bertil));
                        assertEquals(500, failed.status());
                        assertEquals(1, LoginSupport.sessionCookies(failed).size(), "the login stands");
                        assertEquals("401", ReviewSupport.me(server, anna), "anna's older session ended");
                        assertEquals("401", ReviewSupport.me(server, bertil), "bertil's session ended");
                        ReviewSupport.text(sessions);
                        ReviewSupport.text(security);
                    }
                    assertEquals(1, Support.LOG.records().size() - before, "the router logged the failure");
                }),
                test("no session id, token or password in a response (but its own Set-Cookie), a log line, an"
                        + " exception message or a toString() of these tests", () -> {
                    var secrets = new HashSet<String>(ReviewSupport.IDS);
                    secrets.addAll(ReviewSupport.TOKENS);
                    var replies = List.copyOf(ReviewSupport.REPLIES);
                    var lines = Support.LOG.records();
                    var texts = List.copyOf(ReviewSupport.TEXTS);
                    // the record is not empty: 200 logins in the concurrency test alone
                    assertTrue(ReviewSupport.IDS.size() > 300, "only " + ReviewSupport.IDS.size() + " ids recorded");
                    assertTrue(ReviewSupport.TOKENS.size() >= 30, "only " + ReviewSupport.TOKENS.size() + " tokens");
                    assertTrue(replies.size() > 600, "only " + replies.size() + " replies recorded");
                    assertTrue(texts.stream().anyMatch(t -> t.contains("a user needs at least 1 session")),
                            "the build() problems were recorded");
                    assertTrue(lines.stream().anyMatch(l -> l.contains("failed with Session[")), "the failure was logged");
                    assertTrue(replies.stream().anyMatch(r -> r.status() == 503), "a 503 was recorded");
                    var passwords = new ArrayList<>(LoginSupport.PASSWORDS.values());
                    passwords.add(Support.PASSWORD_MARKER);

                    var failures = new ArrayList<String>();
                    int own = 0;
                    for (var reply : replies) {
                        var matcher = OWN_COOKIE.matcher(reply.head());
                        var head = new StringBuilder();
                        while (matcher.find()) {
                            own += matcher.group(2).isEmpty() ? 0 : 1;
                            matcher.appendReplacement(head, "$1<own value>");
                        }
                        matcher.appendTail(head);
                        var text = head + "\r\n\r\n" + reply.body();
                        if (!LoginLeakTests.idsIn(text, secrets).isEmpty()) {
                            failures.add("a " + reply.status() + " response holds an id or a token outside its own"
                                    + " Set-Cookie");
                        }
                        for (var password : passwords) {
                            if (text.contains(password)) {
                                failures.add("a " + reply.status() + " response holds a password");
                            }
                        }
                    }
                    assertTrue(own > 300, "only " + own + " cookies set");
                    for (var line : lines) {
                        if (!LoginLeakTests.idsIn(line, secrets).isEmpty()) {
                            failures.add("a log line holds an id or a token: " + line.lines().findFirst().orElse("")
                                    .replaceAll("[A-Za-z0-9_-]{43}", "<value>"));
                        }
                        for (var password : passwords) {
                            if (line.contains(password)) {
                                failures.add("a log line holds a password");
                            }
                        }
                    }
                    for (var text : texts) {
                        if (!LoginLeakTests.idsIn(text, secrets).isEmpty()) {
                            failures.add("a message or toString() holds an id or a token");
                        }
                        for (var password : passwords) {
                            if (text.contains(password)) {
                                failures.add("a message or toString() holds a password");
                            }
                        }
                    }
                    assertEquals(List.of(), failures);
                }));
    }
}
