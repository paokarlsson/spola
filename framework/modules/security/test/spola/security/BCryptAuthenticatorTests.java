package spola.security;

import static java.nio.charset.StandardCharsets.UTF_8;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import spola.security.Support.CountingHasher;
import spola.security.Support.RecordingLogger;
import spola.test.Suite;

/**
 * Criteria 12 and 13: Authenticator with a delegating hasher migrates users from PBKDF2 and from
 * a lower bcrypt cost, and works under 200 concurrent virtual threads.
 */
final class BCryptAuthenticatorTests {

    private BCryptAuthenticatorTests() {
    }

    static final String PASSWORD = "correct horse battery staple";
    static final AuthResult BAD = new AuthResult.Failed(Failure.BAD_CREDENTIALS);
    static final Pattern COST_5 = Pattern.compile("\\{bcrypt\\}\\$2a\\$05\\$[./A-Za-z0-9]{53}");

    static AuthResult ok(String name) {
        return new AuthResult.Authenticated(new Identity(name, Set.of("USER")));
    }

    /** Bcrypt of(5) as default and PBKDF2 as "pbkdf2", both counted, with recorded upgrades. */
    record Fixture(CountingHasher bcrypt, CountingHasher pbkdf2, PasswordHasher hasher, InMemoryUserStore users,
                   List<String> upgrades, RecordingLogger log, Authenticator authenticator) {

        void reset() {
            bcrypt.reset();
            pbkdf2.reset();
        }

        String storedHash(String username) {
            return users.find(username).orElseThrow().passwordHash();
        }
    }

    static Fixture fixture(Map<String, String> accounts) {
        var bcrypt = new CountingHasher(BCryptPasswordHasher.of(5));
        var pbkdf2 = new CountingHasher(Pbkdf2PasswordHasher.of(10_000, 16, 32));
        var hasher = PasswordHasher.delegating("bcrypt", Map.of("bcrypt", bcrypt, "pbkdf2", pbkdf2));
        var users = new InMemoryUserStore();
        accounts.forEach((name, hash) -> users.add(new User(name, hash, Set.of("USER"), true)));
        var upgrades = Collections.synchronizedList(new ArrayList<String>());
        var upgrader = users.upgrader();
        var log = new RecordingLogger();
        var authenticator = Authenticator.of(users, hasher, (username, verifiedHash, newHash) -> {
            upgrades.add(username + " " + newHash);
            upgrader.upgrade(username, verifiedHash, newHash);
        }, log);
        var fixture = new Fixture(bcrypt, pbkdf2, hasher, users, upgrades, log, authenticator);
        fixture.reset();
        return fixture;
    }

    static String salted(char version, int cost, String password) {
        var salt = new byte[16];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) (31 * i + cost);
        }
        return BCrypt.format(version, cost, salt, password.getBytes(UTF_8));
    }

    static Suite tests() {
        return suite("Authenticator with bcrypt and a delegating hasher",
                test("a {pbkdf2} user logs in, is upgraded once to {bcrypt}$2a$05$, and not again", () -> {
                    var f = fixture(Map.of("pia", "{pbkdf2}" + Support.fast().hash(PASSWORD)));
                    assertEquals(ok("pia"), f.authenticator().authenticate("pia", PASSWORD));
                    assertEquals(1, f.upgrades().size());
                    var upgrade = f.upgrades().getFirst();
                    assertTrue(upgrade.startsWith("pia "), upgrade);
                    var newHash = upgrade.substring("pia ".length());
                    assertTrue(COST_5.matcher(newHash).matches(), "format of the new hash");
                    assertTrue(f.hasher().matches(PASSWORD, newHash));
                    assertTrue(BCryptPasswordHasher.of(5).matches(PASSWORD, newHash.substring("{bcrypt}".length())));
                    assertEquals(newHash, f.storedHash("pia"));
                    assertEquals(ok("pia"), f.authenticator().authenticate("pia", PASSWORD));
                    assertEquals(ok("pia"), f.authenticator().authenticate("pia", PASSWORD));
                    assertEquals(1, f.upgrades().size());
                }),
                test("{bcrypt}$2a$04$ is upgraded to cost 05; {bcrypt}$2a$05$ and {bcrypt}$2b$05$ are not", () -> {
                    var cost4 = "{bcrypt}" + salted('a', 4, PASSWORD);
                    var cost5a = "{bcrypt}" + salted('a', 5, PASSWORD);
                    var cost5b = "{bcrypt}" + salted('b', 5, PASSWORD);
                    var cost6y = "{bcrypt}" + salted('y', 6, PASSWORD);
                    var f = fixture(Map.of("four", cost4, "fiveA", cost5a, "fiveB", cost5b, "sixY", cost6y));
                    for (var name : List.of("four", "fiveA", "fiveB", "sixY")) {
                        assertEquals(ok(name), f.authenticator().authenticate(name, PASSWORD));
                    }
                    assertEquals(1, f.upgrades().size());
                    assertTrue(f.upgrades().getFirst().startsWith("four {bcrypt}$2a$05$"), "upgraded to cost 05");
                    assertTrue(COST_5.matcher(f.storedHash("four")).matches(), "stored cost 05");
                    assertEquals(cost5a, f.storedHash("fiveA"));
                    assertEquals(cost5b, f.storedHash("fiveB"));
                    assertEquals(cost6y, f.storedHash("sixY"));
                    assertEquals(ok("four"), f.authenticator().authenticate("four", PASSWORD));
                    assertEquals(1, f.upgrades().size());
                }),
                test("a wrong password gives BAD_CREDENTIALS and no upgrade, for both algorithms", () -> {
                    var f = fixture(Map.of(
                            "pia", "{pbkdf2}" + Support.fast().hash(PASSWORD),
                            "four", "{bcrypt}" + salted('a', 4, PASSWORD)));
                    for (var name : List.of("pia", "four")) {
                        assertEquals(BAD, f.authenticator().authenticate(name, PASSWORD + "!"));
                        assertEquals(BAD, f.authenticator().authenticate(name, ""));
                        assertEquals(BAD, f.authenticator().authenticate(name, "x".repeat(80)));
                    }
                    assertEquals(List.of(), f.upgrades());
                    assertEquals(0, f.bcrypt().hashCalls.get() + f.pbkdf2().hashCalls.get());
                }),
                test("an unknown user gives BAD_CREDENTIALS after exactly one matches on the bcrypt delegate", () -> {
                    var f = fixture(Map.of("pia", "{pbkdf2}" + Support.fast().hash(PASSWORD)));
                    for (var password : List.of(PASSWORD, "wrong", "x".repeat(80), "")) {
                        f.reset();
                        assertEquals(BAD, f.authenticator().authenticate("nobody", password));
                        assertEquals(1, f.bcrypt().matchesCalls.get());
                        assertEquals(0, f.pbkdf2().matchesCalls.get());
                        assertEquals(0, f.bcrypt().hashCalls.get());
                    }
                    assertEquals(List.of(), f.upgrades());
                }),
                test("creating the authenticator hashes the dummy password once, with the default (bcrypt) delegate", () -> {
                    var f = fixture(Map.of());
                    var bcrypt = new CountingHasher(BCryptPasswordHasher.of(5));
                    var hasher = PasswordHasher.delegating("bcrypt", Map.of("bcrypt", bcrypt, "pbkdf2", Support.fast()));
                    Authenticator.of(f.users(), hasher);
                    assertEquals(1, bcrypt.hashCalls.get());
                    assertEquals(0, bcrypt.matchesCalls.get());
                }),
                test("a {pbkdf2} user with an 80-character password logs in; the upgrade fails, is logged, and the hash stays", () -> {
                    var password = "lang-fras-" + "x".repeat(70);
                    assertEquals(80, password.length());
                    var old = "{pbkdf2}" + Support.fast().hash(password);
                    var f = fixture(Map.of("pia", old));
                    assertEquals(ok("pia"), f.authenticator().authenticate("pia", password));
                    assertEquals(List.of(), f.upgrades());
                    assertEquals(1, f.bcrypt().hashCalls.get());
                    assertEquals(old, f.storedHash("pia"));
                    var entries = f.log().entries();
                    assertEquals(List.of(new Support.Entry(Level.WARNING,
                            "password upgrade failed: java.lang.IllegalArgumentException", null)), entries);
                    assertFalse(entries.getFirst().message().contains("lang-fras"));
                    // Still a login next time, with the same result.
                    assertEquals(ok("pia"), f.authenticator().authenticate("pia", password));
                    assertEquals(old, f.storedHash("pia"));
                }),
                test("200 virtual threads with both algorithms each get exactly their own result", () -> {
                    var bcrypt = BCryptPasswordHasher.of(4);
                    var pbkdf2 = Pbkdf2PasswordHasher.of(10_000, 16, 32);
                    var hasher = PasswordHasher.delegating("bcrypt", Map.of("bcrypt", bcrypt, "pbkdf2", pbkdf2));
                    var users = new InMemoryUserStore();
                    int count = 10;
                    for (int u = 0; u < count; u++) {
                        var hash = u % 2 == 0 ? "{bcrypt}" + bcrypt.hash("password-" + u) : "{pbkdf2}" + pbkdf2.hash("password-" + u);
                        users.add(new User("user" + u, hash, Set.of("R" + u), true));
                    }
                    var authenticator = Authenticator.of(users, hasher);
                    var start = new CountDownLatch(1);
                    var expected = new ArrayList<AuthResult>();
                    var futures = new ArrayList<Future<AuthResult>>();
                    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        for (int t = 0; t < 200; t++) {
                            int u = t % count;
                            var username = t % 7 == 6 ? "nobody" + t : "user" + u;
                            boolean right = t % 3 != 0;
                            var password = right ? "password-" + u : "password-" + (u + 1);
                            expected.add(username.startsWith("nobody") || !right ? BAD
                                    : new AuthResult.Authenticated(new Identity(username, Set.of("R" + u))));
                            futures.add(executor.submit(() -> {
                                start.await();
                                return authenticator.authenticate(username, password);
                            }));
                        }
                        start.countDown();
                        for (int t = 0; t < 200; t++) {
                            assertEquals(expected.get(t), futures.get(t).get(), "thread " + t);
                        }
                    }
                    assertTrue(expected.contains(BAD), "some failures");
                    assertTrue(expected.stream().anyMatch(r -> r instanceof AuthResult.Authenticated), "some logins");
                }),
                test("200 concurrent hash calls on of(4) give 200 different salts, and every hash verifies", () -> {
                    var hasher = BCryptPasswordHasher.of(4);
                    var start = new CountDownLatch(1);
                    var futures = new ArrayList<Future<String>>();
                    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        for (int t = 0; t < 200; t++) {
                            var password = "password-" + (t % 5);
                            futures.add(executor.submit(() -> {
                                start.await();
                                return hasher.hash(password);
                            }));
                        }
                        start.countDown();
                        var salts = new HashSet<String>();
                        for (int t = 0; t < 200; t++) {
                            var hash = futures.get(t).get();
                            assertTrue(BCryptHasherTests.COST_4_FORMAT.matcher(hash).matches(), "format");
                            salts.add(hash.substring(7, 29));
                            assertTrue(hasher.matches("password-" + (t % 5), hash), "hash " + t);
                            assertFalse(hasher.matches("password-" + (t % 5 + 1), hash), "hash " + t);
                        }
                        assertEquals(200, salts.size());
                    }
                }));
    }
}
