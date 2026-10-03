package spola.security;

import static spola.security.Support.fast;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertSame;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import spola.security.Support.CountingHasher;
import spola.security.Support.RecordingLogger;
import spola.test.Suite;

/** Criterion 7: Authenticator with a counting hasher. */
final class AuthenticatorTests {

    private AuthenticatorTests() {
    }

    static final String PASSWORD = "correct horse battery staple";
    static final AuthResult BAD = new AuthResult.Failed(Failure.BAD_CREDENTIALS);
    static final AuthResult DISABLED = new AuthResult.Failed(Failure.DISABLED);

    /** A store with ada (enabled, USER and ADMIN) and bob (disabled), hashed with the fast settings. */
    record Fixture(InMemoryUserStore store, CountingHasher hasher, Authenticator authenticator) {
    }

    static Fixture fixture() {
        var hasher = new CountingHasher(fast());
        var store = new InMemoryUserStore();
        store.add(new User("ada", hasher.hash(PASSWORD), Set.of("USER", "ADMIN"), true));
        store.add(new User("bob", hasher.hash(PASSWORD), Set.of("USER"), false));
        var authenticator = Authenticator.of(store, hasher);
        hasher.reset();
        return new Fixture(store, hasher, authenticator);
    }

    /** Recorded calls of a PasswordUpgrade. */
    record Upgrade(String username, String newHash) {
    }

    static Suite tests() {
        return suite("Authenticator",
                test("creating an authenticator hashes the dummy password once", () -> {
                    var hasher = new CountingHasher(fast());
                    Authenticator.of(new InMemoryUserStore(), hasher);
                    assertEquals(1, hasher.hashCalls.get());
                    assertEquals(0, hasher.matchesCalls.get());
                }),
                test("the right password gives Authenticated with the exact name and roles", () -> {
                    var f = fixture();
                    var result = f.authenticator().authenticate("ada", PASSWORD);
                    assertEquals(new AuthResult.Authenticated(new Identity("ada", Set.of("USER", "ADMIN"))), result);
                    var identity = ((AuthResult.Authenticated) result).identity();
                    assertEquals("ada", identity.name());
                    assertEquals(Set.of("ADMIN", "USER"), identity.roles());
                    assertEquals(1, f.hasher().matchesCalls.get());
                    assertEquals(0, f.hasher().hashCalls.get());
                }),
                test("a wrong password gives Failed(BAD_CREDENTIALS) after one matches", () -> {
                    var f = fixture();
                    assertEquals(BAD, f.authenticator().authenticate("ada", "Tr0ub4dor&3"));
                    assertEquals(1, f.hasher().matchesCalls.get());
                    f.hasher().reset();
                    assertEquals(BAD, f.authenticator().authenticate("ada", PASSWORD + " "));
                    assertEquals(BAD, f.authenticator().authenticate("ada", ""));
                    assertEquals(BAD, f.authenticator().authenticate("ada", "\ud800"));
                    assertEquals(BAD, f.authenticator().authenticate("ada", "x".repeat(1025)));
                    assertEquals(4, f.hasher().matchesCalls.get());
                }),
                test("unknown, null, empty and 257-character usernames and a null password: BAD_CREDENTIALS after exactly one matches", () -> {
                    var f = fixture();
                    var lookups = Collections.synchronizedList(new ArrayList<String>());
                    var authenticator = Authenticator.of(username -> {
                        lookups.add(username);
                        return f.store().find(username);
                    }, f.hasher());
                    record Attempt(String username, String password) {
                    }
                    var attempts = List.of(
                            new Attempt("ada", "wrong password"),
                            new Attempt("nobody", PASSWORD),
                            new Attempt("Ada", PASSWORD),
                            new Attempt(null, PASSWORD),
                            new Attempt("", PASSWORD),
                            new Attempt("a".repeat(257), PASSWORD),
                            new Attempt("ada", null),
                            new Attempt("nobody", null),
                            new Attempt(null, null));
                    for (var attempt : attempts) {
                        f.hasher().reset();
                        assertEquals(BAD, authenticator.authenticate(attempt.username(), attempt.password()));
                        assertEquals(1, f.hasher().matchesCalls.get(), "matches calls for " + attempt);
                        assertEquals(0, f.hasher().hashCalls.get());
                    }
                    assertEquals(List.of("ada", "nobody", "Ada"), lookups);
                    // The 256-character limit itself is a lookup.
                    assertEquals(BAD, authenticator.authenticate("a".repeat(256), PASSWORD));
                    assertEquals(4, lookups.size());
                }),
                test("a disabled account: DISABLED with the right password, BAD_CREDENTIALS with a wrong one", () -> {
                    var f = fixture();
                    assertEquals(DISABLED, f.authenticator().authenticate("bob", PASSWORD));
                    assertEquals(BAD, f.authenticator().authenticate("bob", "wrong"));
                    assertEquals(BAD, f.authenticator().authenticate("bob", null));
                    assertEquals(3, f.hasher().matchesCalls.get());
                }),
                test("a hash with 20 000 iterations is upgraded once, then not again", () -> {
                    var hasher = new CountingHasher(fast());
                    var store = new InMemoryUserStore();
                    var old = Pbkdf2PasswordHasher.of(20_000, 16, 32).hash(PASSWORD);
                    store.add(new User("ada", old, Set.of("USER"), true));
                    var upgrades = Collections.synchronizedList(new ArrayList<Upgrade>());
                    var upgrader = store.upgrader(hasher);
                    var authenticator = Authenticator.of(store, hasher, (username, newHash) -> {
                        upgrades.add(new Upgrade(username, newHash));
                        upgrader.upgrade(username, newHash);
                    });
                    var expected = new AuthResult.Authenticated(new Identity("ada", Set.of("USER")));
                    assertEquals(expected, authenticator.authenticate("ada", PASSWORD));
                    assertEquals(1, upgrades.size());
                    var upgrade = upgrades.getFirst();
                    assertEquals("ada", upgrade.username());
                    assertFalse(hasher.needsRehash(upgrade.newHash()));
                    assertTrue(hasher.matches(PASSWORD, upgrade.newHash()));
                    assertFalse(upgrade.newHash().equals(old));
                    assertEquals(upgrade.newHash(), store.find("ada").orElseThrow().passwordHash());
                    assertEquals(expected, authenticator.authenticate("ada", PASSWORD));
                    assertEquals(expected, authenticator.authenticate("ada", PASSWORD));
                    assertEquals(1, upgrades.size());
                }),
                test("a password change during an upgrade is not undone (InMemoryUserStore.upgrader)", () -> {
                    var hasher = fast();
                    var store = new InMemoryUserStore();
                    store.add(new User("ada", Pbkdf2PasswordHasher.of(20_000, 16, 32).hash("old-password"), Set.of("USER"), true));
                    var upgrader = store.upgrader(hasher);
                    var reached = new CountDownLatch(1);
                    var release = new CountDownLatch(1);
                    var upgradeCalls = new AtomicInteger();
                    var authenticator = Authenticator.of(store, hasher, (username, newHash) -> {
                        upgradeCalls.incrementAndGet();
                        reached.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            throw new IllegalStateException(e);
                        }
                        upgrader.upgrade(username, newHash);
                    });
                    var oldLogin = new AtomicReference<AuthResult>();
                    var login = Thread.ofVirtual().start(() -> oldLogin.set(authenticator.authenticate("ada", "old-password")));
                    assertTrue(reached.await(30, TimeUnit.SECONDS), "the upgrade was not reached");
                    // The login has verified the old password and holds a new hash of it; now the password changes.
                    var changed = hasher.hash("new-password");
                    store.updatePassword("ada", changed);
                    release.countDown();
                    login.join();
                    assertEquals(new AuthResult.Authenticated(new Identity("ada", Set.of("USER"))), oldLogin.get());
                    assertEquals(1, upgradeCalls.get());
                    assertEquals(changed, store.find("ada").orElseThrow().passwordHash());
                    assertTrue(authenticator.authenticate("ada", "new-password") instanceof AuthResult.Authenticated);
                    assertEquals(BAD, authenticator.authenticate("ada", "old-password"));
                    assertEquals(1, upgradeCalls.get());
                }),
                test("InMemoryUserStore.upgrader replaces only a hash that needs a rehash, atomically", () -> {
                    var hasher = fast();
                    var store = new InMemoryUserStore();
                    var old = Pbkdf2PasswordHasher.of(20_000, 16, 32).hash(PASSWORD);
                    var current = hasher.hash(PASSWORD);
                    store.add(new User("ada", old, Set.of("USER"), false));
                    store.add(new User("bob", current, Set.of(), true));
                    var malformed = "$pbkdf2-sha256$i=0$SECRETMARKERhashAAAAAA$" + "A".repeat(43);
                    store.add(new User("eve", malformed, Set.of(), true));
                    var upgrader = store.upgrader(hasher);
                    var fresh = hasher.hash(PASSWORD);
                    upgrader.upgrade("ada", fresh);
                    assertEquals(new User("ada", fresh, Set.of("USER"), false), store.find("ada").orElseThrow());
                    upgrader.upgrade("ada", hasher.hash(PASSWORD));
                    assertEquals(fresh, store.find("ada").orElseThrow().passwordHash());
                    upgrader.upgrade("bob", hasher.hash(PASSWORD));
                    assertEquals(current, store.find("bob").orElseThrow().passwordHash());
                    upgrader.upgrade("nobody", fresh);
                    assertTrue(store.find("nobody").isEmpty());
                    var e = assertThrows(IllegalArgumentException.class, () -> upgrader.upgrade("eve", fresh));
                    assertFalse(e.getMessage().contains("SECRETMARKER"));
                    assertEquals(malformed, store.find("eve").orElseThrow().passwordHash());
                    assertThrows(NullPointerException.class, () -> store.upgrader(null));
                    assertThrows(NullPointerException.class, () -> upgrader.upgrade(null, fresh));
                    assertThrows(NullPointerException.class, () -> upgrader.upgrade("ada", null));
                    assertEquals(fresh, store.find("ada").orElseThrow().passwordHash());
                }),
                test("no upgrade for a wrong password, a disabled account or without PasswordUpgrade", () -> {
                    var hasher = new CountingHasher(fast());
                    var store = new InMemoryUserStore();
                    var oldSettings = Pbkdf2PasswordHasher.of(20_000, 16, 32);
                    store.add(new User("ada", oldSettings.hash(PASSWORD), Set.of(), true));
                    store.add(new User("bob", oldSettings.hash(PASSWORD), Set.of(), false));
                    var upgrades = Collections.synchronizedList(new ArrayList<Upgrade>());
                    var authenticator = Authenticator.of(store, hasher, (u, h) -> upgrades.add(new Upgrade(u, h)));
                    assertEquals(BAD, authenticator.authenticate("ada", "wrong"));
                    assertEquals(DISABLED, authenticator.authenticate("bob", PASSWORD));
                    assertEquals(BAD, authenticator.authenticate("bob", "wrong"));
                    assertEquals(List.of(), upgrades);
                    hasher.reset();
                    var without = Authenticator.of(store, hasher);
                    hasher.reset();
                    assertTrue(without.authenticate("ada", PASSWORD) instanceof AuthResult.Authenticated);
                    assertEquals(0, hasher.hashCalls.get());
                    assertEquals(0, hasher.needsRehashCalls.get());
                    assertEquals(List.of(), upgrades);
                }),
                test("a RuntimeException from upgrade is logged by class name and the login succeeds", () -> {
                    var store = new InMemoryUserStore();
                    store.add(new User("ada", Pbkdf2PasswordHasher.of(20_000, 16, 32).hash(PASSWORD), Set.of("USER"), true));
                    var log = new RecordingLogger();
                    var authenticator = Authenticator.of(store, fast(), (u, h) -> {
                        throw new IllegalStateException("database down: " + h + " " + PASSWORD);
                    }, log);
                    var result = authenticator.authenticate("ada", PASSWORD);
                    assertEquals(new AuthResult.Authenticated(new Identity("ada", Set.of("USER"))), result);
                    assertEquals(List.of(new Support.Entry(Level.WARNING,
                            "password upgrade failed: java.lang.IllegalStateException", null)), log.entries());
                }),
                test("an Error from upgrade is not caught", () -> {
                    var store = new InMemoryUserStore();
                    store.add(new User("ada", Pbkdf2PasswordHasher.of(20_000, 16, 32).hash(PASSWORD), Set.of(), true));
                    var error = new AssertionError("from upgrade");
                    var log = new RecordingLogger();
                    var authenticator = Authenticator.of(store, fast(), (u, h) -> {
                        throw error;
                    }, log);
                    assertSame(error, assertThrows(AssertionError.class, () -> authenticator.authenticate("ada", PASSWORD)));
                    assertEquals(List.of(), log.entries());
                }),
                test("an exception from UserStore.find propagates as the same instance", () -> {
                    var failure = new IllegalStateException("connection refused");
                    var authenticator = Authenticator.of(username -> {
                        throw failure;
                    }, fast());
                    assertSame(failure, assertThrows(IllegalStateException.class, () -> authenticator.authenticate("ada", PASSWORD)));
                }),
                test("a store returning null gives NullPointerException", () -> {
                    var authenticator = Authenticator.of(username -> null, fast());
                    assertThrows(NullPointerException.class, () -> authenticator.authenticate("ada", PASSWORD));
                }),
                test("a malformed stored hash propagates as IllegalArgumentException, for any password", () -> {
                    var store = new InMemoryUserStore();
                    var malformed = "$pbkdf2-sha256$i=0$SECRETMARKERhashAAAAAA$" + "A".repeat(43);
                    store.add(new User("ada", malformed, Set.of(), true));
                    store.add(new User("bob", malformed, Set.of(), false));
                    var authenticator = Authenticator.of(store, fast());
                    for (var password : List.of(PASSWORD, "wrong", "")) {
                        var e = assertThrows(IllegalArgumentException.class, () -> authenticator.authenticate("ada", password));
                        assertFalse(e.getMessage().contains("SECRETMARKER"));
                        assertThrows(IllegalArgumentException.class, () -> authenticator.authenticate("bob", password));
                    }
                }),
                test("of(...) rejects null arguments", () -> {
                    var store = new InMemoryUserStore();
                    var hasher = fast();
                    assertThrows(NullPointerException.class, () -> Authenticator.of(null, hasher));
                    assertThrows(NullPointerException.class, () -> Authenticator.of(store, null));
                    assertThrows(NullPointerException.class, () -> Authenticator.of(store, hasher, null));
                    assertEquals("Authenticator[hasher=Pbkdf2PasswordHasher[iterations=10000, saltBytes=16, hashBytes=32],"
                            + " upgrade=false]", Authenticator.of(store, hasher).toString());
                    var upgrading = Authenticator.of(store, hasher, store.upgrader(hasher));
                    assertTrue(upgrading.toString().endsWith("upgrade=true]"));
                }));
    }
}
