package spola.security;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertNotEquals;
import static spola.test.Assertions.assertSame;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import spola.test.Suite;

/**
 * 15c, criterion 3 (L1): the upgrade is a compare-and-set on the verified hash, so a password
 * change made while an upgrade is running is never undone, whatever settings it was hashed with.
 */
final class UpgradeRaceTests {

    private UpgradeRaceTests() {
    }

    static final AuthResult BAD = new AuthResult.Failed(Failure.BAD_CREDENTIALS);
    static final AuthResult ADA = new AuthResult.Authenticated(new Identity("ada", Set.of("USER")));

    /**
     * A rolling deployment: one instance still has the old settings, the other the new ones,
     * and both write to the same store.
     *
     * @param label   what changes
     * @param old     the instance that has not been upgraded yet
     * @param current the upgraded instance, whose authenticator upgrades old hashes
     */
    record Deployment(String label, PasswordHasher old, PasswordHasher current) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** One case: a deployment and which of its instances writes the password change. */
    record Change(Deployment deployment, boolean throughOld) {
        PasswordHasher writer() {
            return throughOld ? deployment.old() : deployment.current();
        }

        @Override
        public String toString() {
            return deployment + ", the change hashed by the " + (throughOld ? "old" : "upgraded") + " instance";
        }
    }

    static List<Deployment> deployments() {
        var bcrypt = BCryptPasswordHasher.of(4);
        var pbkdf2 = Support.fast();
        var both = Map.of("bcrypt", bcrypt, "pbkdf2", pbkdf2);
        return List.of(
                // The probe of the security review: bcrypt cost 4 on the old instance, 5 on the new.
                new Deployment("bcrypt cost 4 to cost 5", BCryptPasswordHasher.of(4), BCryptPasswordHasher.of(5)),
                new Deployment("PBKDF2 20 000 to 10 000 iterations", Pbkdf2PasswordHasher.of(20_000, 16, 32), pbkdf2),
                new Deployment("{pbkdf2} to {bcrypt}", PasswordHasher.delegating("pbkdf2", both),
                        PasswordHasher.delegating("bcrypt", both)));
    }

    static List<Change> changes() {
        var changes = new ArrayList<Change>();
        for (var deployment : deployments()) {
            changes.add(new Change(deployment, true));
            changes.add(new Change(deployment, false));
        }
        return changes;
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the latch was not released");
            }
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    static Suite tests() {
        return suite("the upgrade is a compare-and-set on the verified hash",
                parameterized("the probe: an upgrade held by a latch while the password is changed does not undo the change",
                        changes(), change -> {
                            var deployment = change.deployment();
                            var store = new InMemoryUserStore();
                            store.add(new User("ada", deployment.old().hash("old-leaked"), Set.of("USER"), true));
                            var before = store.find("ada").orElseThrow().passwordHash();
                            assertTrue(deployment.current().needsRehash(before), "the old hash needs a rehash");
                            var reached = new CountDownLatch(1);
                            var release = new CountDownLatch(1);
                            var verified = new AtomicReference<String>();
                            var calls = new AtomicInteger();
                            var real = store.upgrader();
                            PasswordUpgrade held = (username, verifiedHash, newHash) -> {
                                calls.incrementAndGet();
                                verified.set(verifiedHash);
                                reached.countDown();
                                await(release);
                                real.upgrade(username, verifiedHash, newHash);
                            };
                            var upgrading = Authenticator.of(store, deployment.current(), held);
                            String changed;
                            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                                var login = executor.submit(() -> upgrading.authenticate("ada", "old-leaked"));
                                await(reached);
                                // The login has verified the old password and holds a new hash of it.
                                // Now the user changes the password, through the instance of the case.
                                changed = change.writer().hash("new-secret");
                                store.updatePassword("ada", changed);
                                release.countDown();
                                assertEquals(ADA, login.get(30, TimeUnit.SECONDS));
                            }
                            assertEquals(1, calls.get());
                            assertSame(before, verified.get());
                            assertSame(changed, store.find("ada").orElseThrow().passwordHash());
                            var check = Authenticator.of(store, deployment.current());
                            assertEquals(ADA, check.authenticate("ada", "new-secret"));
                            assertEquals(BAD, check.authenticate("ada", "old-leaked"));
                            // The next login with the new password upgrades the changed hash, if it needs it.
                            assertEquals(ADA, upgrading.authenticate("ada", "new-secret"));
                            var after = store.find("ada").orElseThrow().passwordHash();
                            assertFalse(deployment.current().needsRehash(after));
                            assertEquals(change.throughOld() ? 2 : 1, calls.get());
                            if (change.throughOld()) {
                                assertNotEquals(changed, after);
                            } else {
                                assertSame(changed, after);
                            }
                            assertEquals(ADA, check.authenticate("ada", "new-secret"));
                            assertEquals(BAD, check.authenticate("ada", "old-leaked"));
                        }),
                test("200 virtual threads log in and change the password in turn: the last password always holds", () -> {
                    for (int run = 0; run < RUNS; run++) {
                        loginsAndChanges(run);
                    }
                }));
    }

    static final int THREADS = 200;
    /** Operations per thread: login, change, login, change (odd threads start with a change). */
    static final int OPERATIONS = 4;
    static final int RUNS = 3;

    /**
     * One run: 200 virtual threads start together; each logs in with the password that is current
     * when it starts and changes the password, in turn. Changes are hashed by the instance with
     * the old settings (they need a rehash, as in the probe), logins go through the upgraded
     * instance, which upgrades. Each change is stored and recorded under one lock, so "the last
     * password" is well defined, and under the same lock each change first reads what the store
     * holds: that must be a hash of the password of the change before it (an upgrade may have
     * replaced the hash, never the password). When all are done, the last password works and no
     * other does.
     */
    static void loginsAndChanges(int run) throws Exception {
        var deployment = deployments().getFirst();
        var store = new InMemoryUserStore();
        var first = "password-" + run + "-start";
        store.add(new User("ada", deployment.old().hash(first), Set.of("USER"), true));
        var upgrades = new AtomicInteger();
        var real = store.upgrader();
        var authenticator = Authenticator.of(store, deployment.current(), (username, verifiedHash, newHash) -> {
            upgrades.incrementAndGet();
            real.upgrade(username, verifiedHash, newHash);
        });
        var lock = new Object();
        var last = new AtomicReference<>(first);
        var hasher = deployment.current();
        var overwritten = Collections.synchronizedList(new ArrayList<String>());
        var used = Collections.synchronizedList(new ArrayList<String>());
        used.add(first);
        var logins = new AtomicInteger();
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<?>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < THREADS; t++) {
                int thread = t;
                futures.add(executor.submit(() -> {
                    await(start);
                    for (int op = 0; op < OPERATIONS; op++) {
                        if ((thread + op) % 2 == 0) {
                            var result = authenticator.authenticate("ada", last.get());
                            // BAD_CREDENTIALS when the password changed in the meantime.
                            assertTrue(result.equals(ADA) || result.equals(BAD), String.valueOf(result));
                            if (result.equals(ADA)) {
                                logins.incrementAndGet();
                            }
                        } else {
                            var password = "password-" + run + "-" + thread + "-" + op;
                            var hash = deployment.old().hash(password);
                            String before;
                            String beforePassword;
                            synchronized (lock) {
                                before = store.find("ada").orElseThrow().passwordHash();
                                beforePassword = last.get();
                                store.updatePassword("ada", hash);
                                last.set(password);
                            }
                            used.add(password);
                            if (!hasher.matches(beforePassword, before)) {
                                overwritten.add(beforePassword);
                            }
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }
        assertEquals(1 + THREADS * OPERATIONS / 2, used.size());
        assertEquals(List.of(), overwritten, "run " + run + ": passwords that an upgrade replaced before the next change");
        assertTrue(logins.get() > 0, "no login succeeded");
        assertTrue(upgrades.get() > 0, "no upgrade ran");
        var stored = store.find("ada").orElseThrow().passwordHash();
        var winner = last.get();
        assertTrue(hasher.matches(winner, stored), "run " + run + ": the last password does not work");
        var checks = new ArrayList<Future<Boolean>>();
        var others = new ArrayList<String>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var password : List.copyOf(used)) {
                if (!password.equals(winner)) {
                    others.add(password);
                    checks.add(executor.submit(() -> hasher.matches(password, stored)));
                }
            }
            var wrong = new ArrayList<String>();
            for (int i = 0; i < checks.size(); i++) {
                if (checks.get(i).get(60, TimeUnit.SECONDS)) {
                    wrong.add(others.get(i));
                }
            }
            assertEquals(List.of(), wrong, "run " + run + ": earlier passwords that still work");
        }
        assertEquals(THREADS * OPERATIONS / 2, others.size());
        var check = Authenticator.of(store, hasher);
        assertEquals(ADA, check.authenticate("ada", winner));
    }
}
