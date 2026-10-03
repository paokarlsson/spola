package spola.security;

import static spola.security.Support.fast;
import static spola.test.Assertions.assertEquals;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import spola.test.Suite;

/** Criterion 8: 200 virtual threads authenticate at the same time. */
final class ConcurrencyTests {

    private ConcurrencyTests() {
    }

    static final int THREADS = 200;
    static final int USERS = 10;

    static Suite tests() {
        return suite("concurrency",
                test("200 virtual threads each get exactly their own result", () -> {
                    var hasher = fast();
                    var store = new InMemoryUserStore();
                    for (int u = 0; u < USERS; u++) {
                        store.add(new User("user" + u, hasher.hash("password-" + u), Set.of("R" + u), u % 4 != 3));
                    }
                    var authenticator = Authenticator.of(store, hasher);
                    var start = new CountDownLatch(1);
                    var expected = new ArrayList<AuthResult>();
                    var futures = new ArrayList<Future<AuthResult>>();
                    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        for (int t = 0; t < THREADS; t++) {
                            int u = t % USERS;
                            String username = t % 7 == 6 ? "nobody" + t : "user" + u;
                            boolean rightPassword = t % 3 != 0;
                            String password = rightPassword ? "password-" + u : "password-" + (u + 1);
                            boolean known = !username.startsWith("nobody");
                            boolean enabled = u % 4 != 3;
                            expected.add(!known || !rightPassword ? new AuthResult.Failed(Failure.BAD_CREDENTIALS)
                                    : !enabled ? new AuthResult.Failed(Failure.DISABLED)
                                    : new AuthResult.Authenticated(new Identity(username, Set.of("R" + u))));
                            futures.add(executor.submit(() -> {
                                start.await();
                                return authenticator.authenticate(username, password);
                            }));
                        }
                        start.countDown();
                        List<AuthResult> actual = new ArrayList<>();
                        for (var future : futures) {
                            actual.add(future.get());
                        }
                        for (int t = 0; t < THREADS; t++) {
                            assertEquals(expected.get(t), actual.get(t), "thread " + t);
                        }
                    }
                    // All three kinds of result occurred.
                    assertEquals(3L, expected.stream().map(r -> switch (r) {
                        case AuthResult.Authenticated a -> "ok";
                        case AuthResult.Failed(var reason) -> reason.name();
                    }).distinct().count());
                }));
    }
}
