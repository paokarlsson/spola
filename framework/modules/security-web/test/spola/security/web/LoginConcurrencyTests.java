package spola.security.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

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
 * {@code CookieManager}, log in as one of five users, ask who they are, log out and ask again.
 */
final class LoginConcurrencyTests {

    private LoginConcurrencyTests() {
    }

    static final int THREADS = 200;

    /** What one thread saw, and the id it was given. */
    record Outcome(List<String> seen, String id) {
    }

    static Suite tests() {
        return suite("concurrency",
                test("200 virtual threads log in as one of five users, see their own name, log out and get 401", () -> {
                    var sessions = LoginExample.sessions(false, InstantSource.system());
                    var router = LoginExample.router(Support.router(), LoginExample.security(sessions),
                            LoginSupport.authenticator());
                    var start = new CountDownLatch(1);
                    try (var server = Support.serve(router);
                            var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        var futures = new ArrayList<Future<Outcome>>();
                        for (int t = 0; t < THREADS; t++) {
                            var name = LoginSupport.NAMES.get(t % LoginSupport.NAMES.size());
                            futures.add(executor.submit(() -> {
                                start.await();
                                var seen = new ArrayList<String>();
                                String id;
                                try (var client = LoginSupport.client()) {
                                    var login = LoginSupport.login(client, server, name, LoginSupport.PASSWORDS.get(name));
                                    seen.add(login.statusCode() + " " + login.body());
                                    var cookies = LoginSupport.sessionCookies(login);
                                    id = cookies.size() == 1 ? LoginSupport.idOf(cookies.getFirst()) : "";
                                    var me = LoginSupport.get(client, server, "/api/auth/me");
                                    seen.add(me.statusCode() + " " + me.body());
                                    var logout = LoginSupport.post(client, server, "/api/auth/logout", null);
                                    seen.add(logout.statusCode() + " " + logout.body() + " "
                                            + logout.headers().allValues("Set-Cookie"));
                                    var after = LoginSupport.get(client, server, "/api/auth/me");
                                    seen.add(after.statusCode() + " " + after.body());
                                }
                                return new Outcome(seen, id);
                            }));
                        }
                        start.countDown();
                        var failures = new ArrayList<String>();
                        var ids = new HashSet<String>();
                        for (int t = 0; t < THREADS; t++) {
                            var name = LoginSupport.NAMES.get(t % LoginSupport.NAMES.size());
                            var outcome = futures.get(t).get(120, TimeUnit.SECONDS);
                            var expected = List.of(
                                    "200 {\"success\":true,\"username\":\"" + name + "\"}",
                                    "200 {\"username\":\"" + name + "\"}",
                                    "200 {\"success\":true} [" + LoginFlowTests.REMOVAL + "]",
                                    "401 " + BasicTests.PROBLEM_401);
                            if (!outcome.seen().equals(expected)) {
                                failures.add("thread " + t + " (" + name + ") saw " + outcome.seen());
                            }
                            if (!LoginSupport.ID.matcher(outcome.id()).matches()) {
                                failures.add("thread " + t + " got no session id");
                            }
                            ids.add(outcome.id());
                        }
                        assertEquals(List.of(), failures);
                        assertEquals(THREADS, ids.size(), "every login got its own id");
                        assertEquals(0, sessions.size());
                    }
                }));
    }
}
