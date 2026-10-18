package spola.security.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import spola.test.Suite;

/**
 * Uppdrag 15a, G1 (criterion 4): 200 virtual threads log in five users at once, 40 logins each,
 * through the README example with the default limit of 10 per user.
 */
final class UserLimitConcurrencyTests {

    private UserLimitConcurrencyTests() {
    }

    static final int THREADS = 200;

    /** What one thread got: the status and body of its login, and its session id. */
    record Login(String name, String outcome, String id) {
    }

    static Suite tests() {
        return suite("sessions per user with 200 virtual threads",
                test("200 virtual threads log in five users at once: no user has more than 10 sessions, each has"
                        + " exactly 10, and size() is the number of sessions that work", () -> {
                    var sessions = LoginExample.sessions(false, InstantSource.system());
                    var start = new CountDownLatch(1);
                    try (var server = Support.serve(ReviewSupport.router(LoginExample.security(sessions)));
                            var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        var futures = new ArrayList<Future<Login>>();
                        for (int t = 0; t < THREADS; t++) {
                            var name = LoginSupport.NAMES.get(t % LoginSupport.NAMES.size());
                            futures.add(executor.submit(() -> {
                                start.await();
                                var reply = ReviewSupport.login(server, name, null);
                                var cookies = LoginSupport.sessionCookies(reply);
                                return new Login(name, reply.status() + " " + reply.body(),
                                        cookies.size() == 1 ? LoginSupport.idOf(cookies.getFirst()) : "");
                            }));
                        }
                        start.countDown();
                        var logins = new ArrayList<Login>();
                        var failures = new ArrayList<String>();
                        for (var future : futures) {
                            var login = future.get(120, TimeUnit.SECONDS);
                            logins.add(login);
                            if (!login.outcome().equals("200 {\"success\":true,\"username\":\"" + login.name() + "\"}")
                                    || !LoginSupport.ID.matcher(login.id()).matches()) {
                                failures.add(login.name() + " got " + login.outcome());
                            }
                        }
                        assertEquals(List.of(), failures);
                        int total = sessions.size();
                        // which ids still work, per user: asking touches them, but ends none
                        var working = new HashMap<String, Integer>();
                        for (var login : logins) {
                            var answer = ReviewSupport.me(server, login.id());
                            if (answer.equals(ReviewSupport.named(login.name()))) {
                                working.merge(login.name(), 1, Integer::sum);
                            } else {
                                assertEquals("401", answer, "an ended session is anonymous");
                            }
                        }
                        var expected = new HashMap<String, Integer>();
                        for (var name : LoginSupport.NAMES) {
                            expected.put(name, 10);
                        }
                        assertEquals(Map.copyOf(expected), Map.copyOf(working));
                        assertEquals(50, total, "five users with 10 sessions each, nothing else in memory");
                        assertEquals(50, sessions.size());
                    }
                }));
    }
}
