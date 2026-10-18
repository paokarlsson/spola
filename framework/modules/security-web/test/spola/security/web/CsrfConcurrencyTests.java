package spola.security.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.http.HttpResponse;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import spola.test.Suite;

/**
 * Criterion 8: 200 virtual threads, each with its own {@code HttpClient} and
 * {@code CookieManager}, run the flow of criterion 3 as one of five users.
 */
final class CsrfConcurrencyTests {

    private CsrfConcurrencyTests() {
    }

    static final int THREADS = 200;

    /** What one thread saw, and the tokens (before login, after login, after logout) and the session id it got. */
    record Outcome(List<String> seen, List<String> tokens, String id) {
    }

    /** {@code status body [Set-Cookie for XSRF-TOKEN: new|none] [Set-Cookie for SESSION: new|removed|none]}. */
    static String describe(HttpResponse<String> response) {
        var xsrf = CsrfSupport.xsrfCookies(response);
        var session = CsrfSupport.sessionCookies(response);
        return response.statusCode() + " " + response.body()
                + (xsrf.isEmpty() ? " xsrf:none" : xsrf.size() == 1 ? " xsrf:new" : " xsrf:" + xsrf.size())
                + (session.isEmpty() ? " session:none"
                        : session.equals(List.of(LoginFlowTests.REMOVAL)) ? " session:removed"
                        : session.size() == 1 ? " session:new" : " session:" + session.size());
    }

    static String token(HttpResponse<String> response) {
        var xsrf = CsrfSupport.xsrfCookies(response);
        return xsrf.size() == 1 ? CsrfSupport.valueOf(xsrf.getFirst()) : "";
    }

    static Suite tests() {
        return suite("concurrency",
                test("200 virtual threads with their own HttpClient and CookieManager run the flow as one of five"
                        + " users", () -> {
                    var sessions = CsrfExample.sessions(false, InstantSource.system());
                    var counting = new Support.Counting(LoginSupport.USERS);
                    // the example's rules with room for every thread's session (15a: the default of 10 per
                    // user would end the oldest of each user's 40 logins)
                    var router = CsrfExample.router(Support.router(), UserSessionSupport.csrfSecurity(sessions, THREADS),
                            counting.authenticator);
                    var start = new CountDownLatch(1);
                    try (var server = Support.serve(router);
                            var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        var futures = new ArrayList<Future<Outcome>>();
                        for (int t = 0; t < THREADS; t++) {
                            var name = LoginSupport.NAMES.get(t % LoginSupport.NAMES.size());
                            futures.add(executor.submit(() -> {
                                start.await();
                                var seen = new ArrayList<String>();
                                var tokens = new ArrayList<String>();
                                String id;
                                try (var browser = new CsrfSupport.Browser(server)) {
                                    var health = browser.get("/actuator/health");
                                    seen.add(describe(health));
                                    tokens.add(token(health));
                                    var json = LoginSupport.loginJson(name, LoginSupport.PASSWORDS.get(name));
                                    seen.add(describe(browser.postWithoutHeader("/api/auth/login", json)));
                                    var login = browser.login(name, LoginSupport.PASSWORDS.get(name));
                                    seen.add(describe(login));
                                    tokens.add(token(login));
                                    var cookies = CsrfSupport.sessionCookies(login);
                                    id = cookies.size() == 1 ? CsrfSupport.valueOf(cookies.getFirst()) : "";
                                    seen.add(describe(browser.get("/api/auth/me")));
                                    seen.add(describe(browser.post("/api/auth/logout", null, tokens.getFirst())));
                                    var logout = browser.post("/api/auth/logout", null);
                                    seen.add(describe(logout));
                                    tokens.add(token(logout));
                                    seen.add(describe(browser.get("/api/auth/me")));
                                }
                                return new Outcome(seen, tokens, id);
                            }));
                        }
                        start.countDown();
                        var failures = new ArrayList<String>();
                        var tokens = new HashSet<String>();
                        var ids = new HashSet<String>();
                        for (int t = 0; t < THREADS; t++) {
                            var name = LoginSupport.NAMES.get(t % LoginSupport.NAMES.size());
                            var outcome = futures.get(t).get(120, TimeUnit.SECONDS);
                            var expected = List.of(
                                    "200 {\"status\":\"UP\"} xsrf:new session:none",
                                    "403 " + BasicTests.PROBLEM_403 + " xsrf:none session:none",
                                    "200 {\"success\":true,\"username\":\"" + name + "\"} xsrf:new session:new",
                                    "200 {\"username\":\"" + name + "\"} xsrf:none session:none",
                                    "403 " + BasicTests.PROBLEM_403 + " xsrf:none session:none",
                                    "200 {\"success\":true} xsrf:new session:removed",
                                    "401 " + BasicTests.PROBLEM_401 + " xsrf:none session:none");
                            if (!outcome.seen().equals(expected)) {
                                failures.add("thread " + t + " (" + name + ") saw " + outcome.seen());
                            }
                            for (var token : outcome.tokens()) {
                                if (!CsrfSupport.TOKEN.matcher(token).matches()) {
                                    failures.add("thread " + t + " got no token");
                                }
                            }
                            if (!CsrfSupport.TOKEN.matcher(outcome.id()).matches()) {
                                failures.add("thread " + t + " got no session id");
                            }
                            tokens.addAll(outcome.tokens());
                            ids.add(outcome.id());
                        }
                        assertEquals(List.of(), failures);
                        assertEquals(3 * THREADS, tokens.size(), "every token differs, within and between threads");
                        assertEquals(THREADS, ids.size(), "every login got its own session id");
                        assertEquals(THREADS, counting.calls(), "one password check per thread: no forged login got"
                                + " through");
                        assertEquals(0, sessions.size());
                    }
                }));
    }
}
