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
import spola.test.Suite;

/**
 * Criterion 9: no password, no CSRF token and no session id in any response (except the value in
 * the response's own {@code Set-Cookie}), in any log line the routers wrote or in any exception
 * message or {@code toString()} the tests looked at. Runs last among the CSRF tests: it reads every
 * reply of the suite so far ({@link Support#REPLIES}), every router log line ({@link Support#LOG})
 * and the tokens, ids and texts that {@link CsrfSupport} (and {@link LoginSupport}) recorded.
 */
final class CsrfLeakTests {

    private CsrfLeakTests() {
    }

    /**
     * {@code Set-Cookie: XSRF-TOKEN=<value>} or {@code Set-Cookie: SESSION=<value>} (or
     * {@code __Host-SESSION}, 15b) in a response head, with its value.
     */
    static final Pattern OWN_COOKIE =
            Pattern.compile("(?im)^(Set-Cookie:\\s*(?:XSRF-TOKEN|(?:__Host-)?SESSION)=)([^;\\r\\n]*)");

    static Suite tests() {
        return suite("secrets of the CSRF protection",
                test("the example's body never shows the password in toString()", () -> {
                    var login = new CsrfExample.Login(LoginSupport.ANNA, LoginSupport.ANNA_PASSWORD);
                    assertEquals("Login[username=anna]", CsrfSupport.text(login));
                }),
                test("no password, CSRF token or session id in a response (but its own Set-Cookie), a log line, an"
                        + " exception message or a toString()", () -> {
                    var tokens = Set.copyOf(CsrfSupport.TOKENS);
                    var secrets = new HashSet<String>(tokens);
                    secrets.addAll(CsrfSupport.IDS);
                    secrets.addAll(LoginSupport.IDS);
                    List<Support.Reply> replies;
                    synchronized (Support.REPLIES) {
                        replies = List.copyOf(Support.REPLIES);
                    }
                    var lines = Support.LOG.records();
                    var texts = List.copyOf(CsrfSupport.TEXTS);
                    // the record is not empty: three tokens per thread in the concurrency test alone
                    assertTrue(tokens.size() > 3 * CsrfConcurrencyTests.THREADS, "only " + tokens.size() + " tokens");
                    assertTrue(CsrfSupport.IDS.size() > CsrfConcurrencyTests.THREADS, "only " + CsrfSupport.IDS.size()
                            + " session ids");
                    assertTrue(lines.stream().anyMatch(line -> line.contains("the handler failed on purpose")),
                            "the 500 of the CSRF tests was logged");
                    assertTrue(texts.stream().anyMatch(text -> text.contains("csrf(...): the cookie function")),
                            "the exception messages were recorded");
                    var passwords = new ArrayList<>(LoginSupport.PASSWORDS.values());
                    passwords.add(Support.PASSWORD_MARKER);

                    var failures = new ArrayList<String>();
                    int ownTokens = 0;
                    for (var reply : replies) {
                        var own = OWN_COOKIE.matcher(reply.head());
                        var head = new StringBuilder();
                        while (own.find()) {
                            if (own.group(1).contains("XSRF-TOKEN=") && !own.group(2).isEmpty()) {
                                ownTokens++;
                            }
                            own.appendReplacement(head, "$1<own value>");
                        }
                        own.appendTail(head);
                        var text = head + "\r\n\r\n" + reply.body();
                        if (!LoginLeakTests.idsIn(text, secrets).isEmpty()) {
                            failures.add("a " + reply.status() + " response holds a token or a session id outside its"
                                    + " own Set-Cookie");
                        }
                        for (var password : passwords) {
                            if (text.contains(password)) {
                                failures.add("a " + reply.status() + " response holds a password");
                            }
                        }
                    }
                    assertTrue(ownTokens > 3 * CsrfConcurrencyTests.THREADS, "only " + ownTokens + " tokens set");
                    // the copies in Support.REPLIES have <own value> for every token (see CsrfSupport)
                    for (var reply : replies) {
                        for (var setCookie : CsrfSupport.xsrfCookies(reply)) {
                            assertEquals(CsrfSupport.OWN_VALUE, CsrfSupport.valueOf(setCookie));
                        }
                    }
                    for (var line : lines) {
                        if (!LoginLeakTests.idsIn(line, secrets).isEmpty()) {
                            failures.add("a log line holds a token or a session id: " + line.lines().findFirst()
                                    .orElse("").replaceAll("[A-Za-z0-9_-]{43}", "<43>"));
                        }
                        for (var password : passwords) {
                            if (line.contains(password)) {
                                failures.add("a log line holds a password");
                            }
                        }
                    }
                    for (var text : texts) {
                        if (!LoginLeakTests.idsIn(text, secrets).isEmpty()) {
                            failures.add("a message or toString() holds a token or a session id");
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
