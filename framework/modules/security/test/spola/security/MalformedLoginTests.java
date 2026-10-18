package spola.security;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertSame;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import spola.security.Support.Entry;
import spola.security.Support.RecordingLogger;
import spola.test.Suite;

/**
 * 15c, criterion 4 (L2): a known user whose stored hash cannot be parsed gets
 * {@code BAD_CREDENTIALS} after exactly one dummy check, as an unknown user does, and the data
 * error is logged at {@code WARNING} with only the exception's class name.
 */
final class MalformedLoginTests {

    private MalformedLoginTests() {
    }

    static final AuthResult BAD = new AuthResult.Failed(Failure.BAD_CREDENTIALS);
    static final String PASSWORD_MARKER = "PWMARKER";
    static final String HASH_MARKER = "HASHMARKER";
    /** The line for a malformed hash from Spola's hashers. */
    static final Entry LINE = new Entry(Level.WARNING,
            "login refused, the stored password hash is malformed: java.lang.IllegalArgumentException", null);

    /** A stored hash that the hashers cannot parse, and where it comes from. */
    record Case(String label, String stored) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** A hasher under test, by name. */
    record Hasher(String label, PasswordHasher hasher) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** A hasher and a stored hash. */
    record Combination(Hasher hasher, Case stored) {
        @Override
        public String toString() {
            return hasher + ", " + stored;
        }
    }

    static List<Case> cases() {
        var bcrypt = BCryptPasswordHasher.of(4).hash("x");
        return List.of(
                new Case("{noop}x", "{noop}x"),
                new Case("{noop} with a marker", "{noop}" + HASH_MARKER),
                new Case("{sha256} from Spring's StandardPasswordEncoder",
                        "{sha256}" + HASH_MARKER + "97cde38028ad898ebcd8f2e3fa84ec34be3b1e1f24ec64cb1b0bba4e5b9d89f8"
                                + "9e3fbb0bf41e6d07"),
                new Case("{MD5} from Spring's MessageDigestPasswordEncoder",
                        "{MD5}{" + HASH_MARKER + "}5f4dcc3b5aa765d61d8327deb882cf99"),
                new Case("hex {pbkdf2} from Spring's Pbkdf2PasswordEncoder",
                        "{pbkdf2}" + "5d923b44a6d129f3ddf3e3c8d29412723dcbde72445e8ef6bf3b508fbf17fa4ed4d6b99ca763d8dc"),
                new Case("a truncated {bcrypt} hash", "{bcrypt}" + bcrypt.substring(0, 59)),
                new Case("a truncated bcrypt hash without a prefix", bcrypt.substring(0, 59)),
                new Case("{bcrypt}$2$ (not supported)", "{bcrypt}$2$" + bcrypt.substring(4)),
                new Case("the empty string", ""));
    }

    static List<Hasher> hashers() {
        var bcrypt = BCryptPasswordHasher.of(4);
        var pbkdf2 = Support.fast();
        return List.of(
                new Hasher("delegating, bcrypt by default", PasswordHasher.delegating("bcrypt",
                        Map.of("bcrypt", bcrypt, "pbkdf2", pbkdf2))),
                new Hasher("bcrypt", bcrypt),
                new Hasher("pbkdf2", pbkdf2));
    }

    static List<Combination> combinations() {
        var all = new ArrayList<Combination>();
        for (var hasher : hashers()) {
            for (var stored : cases()) {
                all.add(new Combination(hasher, stored));
            }
        }
        return all;
    }

    /** Records every encoded hash that {@code matches} is given and whether the call threw. */
    static final class Recording implements PasswordHasher {
        final PasswordHasher delegate;
        final List<String> hashed = Collections.synchronizedList(new ArrayList<>());
        final List<String> matched = Collections.synchronizedList(new ArrayList<>());
        final List<String> threw = Collections.synchronizedList(new ArrayList<>());
        final List<String> rehashChecked = Collections.synchronizedList(new ArrayList<>());

        Recording(PasswordHasher delegate) {
            this.delegate = delegate;
        }

        @Override
        public String hash(CharSequence password) {
            var hash = delegate.hash(password);
            hashed.add(hash);
            return hash;
        }

        @Override
        public boolean matches(CharSequence password, String encoded) {
            matched.add(encoded);
            try {
                return delegate.matches(password, encoded);
            } catch (RuntimeException e) {
                threw.add(encoded);
                throw e;
            }
        }

        @Override
        public boolean needsRehash(String encoded) {
            rehashChecked.add(encoded);
            return delegate.needsRehash(encoded);
        }

        /** The dummy hash: the one hash made when the authenticator was created. */
        String dummy() {
            assertEquals(1, hashed.size(), "hashes made");
            return hashed.getFirst();
        }

        void clear() {
            matched.clear();
            threw.clear();
            rehashChecked.clear();
        }
    }

    static Suite tests() {
        return suite("a malformed stored hash gives BAD_CREDENTIALS, as an unknown user",
                parameterized("a known user with a malformed or unknown hash: BAD_CREDENTIALS, one dummy check, one log line",
                        combinations(), c -> {
                            var hasher = new Recording(c.hasher().hasher());
                            var stored = c.stored().stored();
                            // The hashers themselves still throw.
                            assertThrows(IllegalArgumentException.class, () -> c.hasher().hasher().matches("x", stored));
                            var store = new InMemoryUserStore();
                            store.add(new User("legacy", stored, Set.of("USER"), true));
                            store.add(new User("off", stored, Set.of("USER"), false));
                            var upgrades = new ArrayList<String>();
                            var log = new RecordingLogger();
                            var authenticator = Authenticator.of(store, hasher, (u, v, n) -> upgrades.add(u), log);
                            var dummy = hasher.dummy();
                            var passwords = List.of("x", PASSWORD_MARKER + "-guess", "", PASSWORD_MARKER.repeat(10));
                            for (var username : List.of("legacy", "off")) {
                                for (var password : passwords) {
                                    hasher.clear();
                                    int before = log.entries().size();
                                    assertEquals(BAD, authenticator.authenticate(username, password));
                                    // The stored hash once (it threw), then the dummy hash exactly once.
                                    assertEquals(List.of(stored, dummy), hasher.matched, username + " " + password.length());
                                    assertEquals(List.of(stored), hasher.threw);
                                    assertEquals(List.of(), hasher.rehashChecked);
                                    var lines = log.entries().subList(before, log.entries().size());
                                    assertEquals(List.of(LINE), lines);
                                    var message = lines.getFirst().message();
                                    if (!stored.isEmpty()) {
                                        assertFalse(message.contains(stored), "the hash is in the log");
                                    }
                                    assertFalse(message.contains(HASH_MARKER), "a part of the hash is in the log");
                                    assertFalse(message.contains(PASSWORD_MARKER), "the password is in the log");
                                    assertFalse(message.contains(username), "the username is in the log");
                                    // An unknown user: the dummy hash exactly once, no log line.
                                    hasher.clear();
                                    assertEquals(BAD, authenticator.authenticate("nobody", password));
                                    assertEquals(List.of(dummy), hasher.matched);
                                    assertEquals(before + 1, log.entries().size());
                                }
                            }
                            assertEquals(List.of(), upgrades);
                            assertEquals(1, hasher.hashed.size());
                            assertSame(stored, store.find("legacy").orElseThrow().passwordHash());
                        }),
                test("a null password never reaches the malformed hash: one dummy check and no log line, as before", () -> {
                    var hasher = new Recording(Support.fast());
                    var store = new InMemoryUserStore();
                    store.add(new User("legacy", "{noop}x", Set.of(), true));
                    var log = new RecordingLogger();
                    var authenticator = Authenticator.of(store, hasher, (u, v, n) -> {
                        throw new AssertionError("no upgrade");
                    }, log);
                    var dummy = hasher.dummy();
                    hasher.clear();
                    assertEquals(BAD, authenticator.authenticate("legacy", null));
                    assertEquals(List.of(dummy), hasher.matched);
                    assertEquals(List.of(), log.entries());
                }),
                test("a subclass of IllegalArgumentException is logged by its class name only", () -> {
                    var throwing = new PasswordHasher() {
                        final PasswordHasher real = Support.fast();

                        @Override
                        public String hash(CharSequence password) {
                            return real.hash(password);
                        }

                        @Override
                        public boolean matches(CharSequence password, String encoded) {
                            if (encoded.startsWith("legacy:")) {
                                throw new NumberFormatException("bad " + encoded + " for " + password);
                            }
                            return real.matches(password, encoded);
                        }

                        @Override
                        public boolean needsRehash(String encoded) {
                            return real.needsRehash(encoded);
                        }
                    };
                    var store = new InMemoryUserStore();
                    store.add(new User("legacy", "legacy:" + HASH_MARKER, Set.of(), true));
                    var log = new RecordingLogger();
                    var authenticator = Authenticator.of(store, throwing, (u, v, n) -> {
                    }, log);
                    assertEquals(BAD, authenticator.authenticate("legacy", PASSWORD_MARKER));
                    assertEquals(List.of(new Entry(Level.WARNING,
                            "login refused, the stored password hash is malformed: java.lang.NumberFormatException", null)),
                            log.entries());
                }),
                test("only IllegalArgumentException is a malformed hash: another RuntimeException from matches propagates", () -> {
                    var failure = new IllegalStateException("the hasher is broken");
                    var real = Support.fast();
                    var broken = new PasswordHasher() {
                        @Override
                        public String hash(CharSequence password) {
                            return real.hash(password);
                        }

                        @Override
                        public boolean matches(CharSequence password, String encoded) {
                            if (encoded.equals("broken")) {
                                throw failure;
                            }
                            return real.matches(password, encoded);
                        }

                        @Override
                        public boolean needsRehash(String encoded) {
                            return real.needsRehash(encoded);
                        }
                    };
                    var store = new InMemoryUserStore();
                    store.add(new User("ada", "broken", Set.of(), true));
                    var log = new RecordingLogger();
                    var authenticator = Authenticator.of(store, broken, (u, v, n) -> {
                    }, log);
                    assertSame(failure, assertThrows(IllegalStateException.class,
                            () -> authenticator.authenticate("ada", "x")));
                    assertEquals(List.of(), log.entries());
                }),
                test("a well-formed hash with a wrong password logs nothing", () -> {
                    var hasher = Support.fast();
                    var store = new InMemoryUserStore();
                    store.add(new User("ada", hasher.hash("right"), Set.of(), true));
                    var log = new RecordingLogger();
                    var authenticator = Authenticator.of(store, hasher, store.upgrader(), log);
                    assertEquals(BAD, authenticator.authenticate("ada", "wrong"));
                    assertTrue(authenticator.authenticate("ada", "right") instanceof AuthResult.Authenticated);
                    assertEquals(List.of(), log.entries());
                }));
    }
}
