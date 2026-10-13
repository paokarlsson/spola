package spola.security.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import spola.test.Suite;

/**
 * Criterion 9: no password and no session id in any response (except the id in the response's
 * own session {@code Set-Cookie}), in any log line the routers wrote or in any exception message
 * or {@code toString()} the tests looked at. Runs after the other tests of the login with a
 * session: it reads every reply ({@link Support#REPLIES}), every router log line
 * ({@link Support#LOG}) and the texts and ids that {@link LoginSupport} recorded.
 */
final class LoginLeakTests {

    private LoginLeakTests() {
    }

    /** {@code Set-Cookie: SESSION=<value>} in a response head, with its value. */
    static final Pattern OWN_COOKIE = Pattern.compile("(?im)^(Set-Cookie:\\s*SESSION=)([^;\\r\\n]*)");

    /** The ids in {@code text}: every 43-character window that is a recorded id. */
    static List<String> idsIn(String text, Set<String> ids) {
        var found = new ArrayList<String>();
        for (int i = 0; i + 43 <= text.length(); i++) {
            var window = text.substring(i, i + 43);
            if (ids.contains(window)) {
                found.add(window);
            }
        }
        return found;
    }

    static Suite tests() {
        return suite("secrets of the login with a session",
                test("the login example's body never shows the password in toString()", () -> {
                    var login = new LoginExample.Login(LoginSupport.ANNA, LoginSupport.ANNA_PASSWORD);
                    assertEquals("Login[username=anna]", LoginSupport.text(login));
                }),
                test("no password and no session id in a response (but its own Set-Cookie), a log line, an exception"
                        + " message or a toString()", () -> {
                    var ids = Set.copyOf(LoginSupport.IDS);
                    List<Support.Reply> replies;
                    synchronized (Support.REPLIES) {
                        replies = List.copyOf(Support.REPLIES);
                    }
                    var lines = Support.LOG.records();
                    var texts = List.copyOf(LoginSupport.TEXTS);
                    // the record is not empty: 200 logins in the concurrency test alone
                    assertTrue(ids.size() > 210, "only " + ids.size() + " session ids recorded");
                    assertTrue(replies.size() > 700, "only " + replies.size() + " replies recorded");
                    assertTrue(lines.stream().anyMatch(line -> line.contains("register only security.filter()")),
                            "the 500s of the wrong setups were logged");
                    assertTrue(texts.stream().anyMatch(text -> text.contains("needs sessions")),
                            "the exception messages were recorded");
                    var passwords = new ArrayList<>(LoginSupport.PASSWORDS.values());
                    passwords.add(Support.PASSWORD_MARKER);

                    var failures = new ArrayList<String>();
                    int ownCookies = 0;
                    for (var reply : replies) {
                        var own = OWN_COOKIE.matcher(reply.head());
                        var head = new StringBuilder();
                        while (own.find()) {
                            ownCookies += own.group(2).isEmpty() ? 0 : 1;
                            own.appendReplacement(head, "$1<own id>");
                        }
                        own.appendTail(head);
                        var text = head + "\r\n\r\n" + reply.body();
                        if (!idsIn(text, ids).isEmpty()) {
                            failures.add("a " + reply.status() + " response holds a session id outside its own Set-Cookie");
                        }
                        for (var password : passwords) {
                            if (text.contains(password)) {
                                failures.add("a " + reply.status() + " response holds a password");
                            }
                        }
                    }
                    assertTrue(ownCookies > 210, "only " + ownCookies + " session cookies set");
                    for (var line : lines) {
                        var found = idsIn(line, ids);
                        if (!found.isEmpty()) {
                            var first = line.lines().findFirst().orElse("");
                            for (var id : found) {
                                first = first.replace(id, "<id>");
                            }
                            failures.add("a log line holds a session id: " + first);
                        }
                        for (var password : passwords) {
                            assertFalse(line.contains(password), "a log line holds a password");
                        }
                    }
                    for (var text : texts) {
                        if (!idsIn(text, ids).isEmpty()) {
                            failures.add("a message or toString() holds a session id");
                        }
                        for (var password : passwords) {
                            assertFalse(text.contains(password), "a message or toString() holds a password");
                        }
                    }
                    assertEquals(List.of(), failures);
                }));
    }
}
