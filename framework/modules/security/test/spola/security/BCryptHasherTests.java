package spola.security;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertNotEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Assertions.fail;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import spola.test.Body;
import spola.test.Suite;

/** Criteria 7, 8 and 10: BCryptPasswordHasher's settings, password rules and needsRehash. */
final class BCryptHasherTests {

    private BCryptHasherTests() {
    }

    static final Pattern DEFAULT_FORMAT = Pattern.compile("\\$2a\\$10\\$[./A-Za-z0-9]{53}");
    static final Pattern COST_4_FORMAT = Pattern.compile("\\$2a\\$04\\$[./A-Za-z0-9]{53}");
    /** Spring's BCryptPasswordEncoder.BCRYPT_PATTERN. */
    static final Pattern SPRING_PATTERN = Pattern.compile("\\A\\$2(a|y|b)?\\$(\\d\\d)\\$[./0-9A-Za-z]{53}");

    /** The 53 characters of salt and hash of a published vector (jBCrypt, "a", cost 06). */
    static final String TAIL = "m0CrhHm10qJ3lXRY.5zDGO3rS2KdeeWLuGmsfGlMfOxih58VYVfxe";

    /** A stored hash with the given prefix ({@code $2a$10$}) and a valid, canonical tail. */
    static String stored(String prefix) {
        return prefix + TAIL;
    }

    /** Whether the salt of a 60-character hash is canonical: its last character is one of {@code .Oeu}. */
    static boolean canonicalSalt(String encoded) {
        return ".Oeu".indexOf(encoded.charAt(28)) >= 0 && BCrypt.fromBase64(encoded, 7, 16) != null;
    }

    /** How long a check that involves cost 31 may take; computing bcrypt there takes days. */
    static final Duration NO_BCRYPT = Duration.ofSeconds(5);

    /**
     * Runs {@code check}, which involves cost 31, on a daemon thread, and fails with a clear
     * message if it has not returned within {@link #NO_BCRYPT}: that can only mean bcrypt was
     * computed. bcrypt does not look at interrupts, so such a thread runs on until the JVM exits;
     * as a daemon it does not keep the JVM alive. What {@code check} throws is rethrown.
     */
    static void withoutBcrypt(String what, Body check) throws Exception {
        var thrown = new AtomicReference<Throwable>();
        var thread = Thread.ofPlatform().daemon().name("bcrypt check: " + what).start(() -> {
            try {
                check.run();
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        if (!thread.join(NO_BCRYPT)) {
            fail("bcrypt was computed: " + what + " did not return within " + NO_BCRYPT.toSeconds()
                    + " s (cost 31 takes days)");
        }
        switch (thrown.get()) {
            case null -> {
            }
            case Exception e -> throw e;
            case Error e -> throw e;
            case Throwable t -> throw new AssertionError(t);
        }
    }

    /** A password the hasher refuses, and why. */
    record Refused(String label, String password) {
        @Override
        public String toString() {
            return label;
        }
    }

    static final List<Refused> REFUSED = List.of(
            new Refused("empty", ""),
            new Refused("73 bytes of ASCII", "x".repeat(73)),
            new Refused("€ x 24 + a (73 bytes)", "€".repeat(24) + "a"),
            new Refused("€ x 25 (75 bytes)", "€".repeat(25)),
            new Refused("73 chars", "é".repeat(73)),
            new Refused("a lone high surrogate", "abc\ud83d"),
            new Refused("a lone low surrogate", "\ude00abc"),
            new Refused("only a lone surrogate", "\ud800"),
            new Refused("U+0000 alone", "\u0000"),
            new Refused("a U+0000 a", "a\u0000a"),
            new Refused("U+0000 at the end", "password\u0000"));

    static Suite tests() {
        var fast = BCryptPasswordHasher.of(4);
        // Cost 31: verifying against it would take days, so these tests also show that no bcrypt is computed.
        var slowest = BCryptPasswordHasher.of(31);
        var cost31 = stored("$2a$31$");
        var valid = fast.hash("a");
        return suite("BCryptPasswordHasher",
                test("defaults(): one hash has the format $2a$10$, a canonical salt, and verifies", () -> {
                    var defaults = BCryptPasswordHasher.defaults();
                    var hash = defaults.hash("correct horse battery staple");
                    assertTrue(DEFAULT_FORMAT.matcher(hash).matches(), "format");
                    assertTrue(SPRING_PATTERN.matcher(hash).matches(), "Spring's pattern");
                    assertTrue(canonicalSalt(hash), "canonical salt");
                    assertEquals(60, hash.length());
                    assertTrue(defaults.matches("correct horse battery staple", hash));
                    assertFalse(defaults.needsRehash(hash));
                }),
                test("two hashes of the same password differ (salt and hash) and both verify", () -> {
                    var first = fast.hash("correct horse battery staple");
                    var second = fast.hash("correct horse battery staple");
                    assertTrue(COST_4_FORMAT.matcher(first).matches(), "format");
                    assertNotEquals(first, second);
                    assertNotEquals(first.substring(7, 29), second.substring(7, 29));
                    assertTrue(canonicalSalt(first) && canonicalSalt(second), "canonical salts");
                    assertTrue(fast.matches("correct horse battery staple", first));
                    assertTrue(fast.matches("correct horse battery staple", second));
                }),
                test("of(3), of(32), of(-1), of(Integer.MAX_VALUE) and of(Integer.MIN_VALUE) are refused; 4 and 31 work", () -> {
                    List<Body> bad = List.of(
                            () -> BCryptPasswordHasher.of(3),
                            () -> BCryptPasswordHasher.of(32),
                            () -> BCryptPasswordHasher.of(-1),
                            () -> BCryptPasswordHasher.of(0),
                            () -> BCryptPasswordHasher.of(Integer.MAX_VALUE),
                            () -> BCryptPasswordHasher.of(Integer.MIN_VALUE));
                    for (var call : bad) {
                        var e = assertThrows(IllegalArgumentException.class, call);
                        assertEquals("cost must be 4 to 31", e.getMessage());
                    }
                    assertEquals("BCryptPasswordHasher[cost=4]", BCryptPasswordHasher.of(4).toString());
                    assertEquals("BCryptPasswordHasher[cost=31]", BCryptPasswordHasher.of(31).toString());
                    assertEquals(4, BCryptPasswordHasher.MIN_COST);
                    assertEquals(31, BCryptPasswordHasher.MAX_COST);
                    assertEquals(10, BCryptPasswordHasher.DEFAULT_COST);
                    assertEquals(72, BCryptPasswordHasher.MAX_PASSWORD_BYTES);
                }),
                test("toString is BCryptPasswordHasher[cost=<cost>]", () -> {
                    assertEquals("BCryptPasswordHasher[cost=10]", BCryptPasswordHasher.defaults().toString());
                    assertEquals("BCryptPasswordHasher[cost=12]", BCryptPasswordHasher.of(12).toString());
                }),
                parameterized("a refused password: hash throws without it, matches is false, no bcrypt computed", REFUSED, r -> {
                    withoutBcrypt("of(31).hash of a refused password", () -> {
                        var e = assertThrows(IllegalArgumentException.class, () -> slowest.hash(r.password()));
                        if (!r.password().isEmpty()) {
                            assertFalse(e.getMessage().contains(r.password()), "the message contains the password");
                        }
                    });
                    assertFalse(fast.matches(r.password(), valid));
                    withoutBcrypt("matches of a refused password against a cost-31 hash",
                            () -> assertFalse(fast.matches(r.password(), cost31)));
                }).timeout(Duration.ofSeconds(10)),
                test("\"a\\u0000a\" does not verify against hash(\"a\"), though both give the key a 00 a 00 ...", () -> {
                    var hash = fast.hash("a");
                    assertTrue(fast.matches("a", hash));
                    assertFalse(fast.matches("a\u0000a", hash));
                    assertFalse(fast.matches("a\u0000", hash));
                }),
                test("72 bytes are accepted: 72 ASCII characters and € x 24", () -> {
                    for (var password : List.of("x".repeat(72), "€".repeat(24), "😀".repeat(18))) {
                        var hash = fast.hash(password);
                        assertTrue(fast.matches(password, hash));
                        assertFalse(fast.matches(password.substring(0, password.length() - 1), hash));
                    }
                }),
                test("null gives NullPointerException in hash and matches", () -> {
                    assertThrows(NullPointerException.class, () -> fast.hash(null));
                    assertThrows(NullPointerException.class, () -> fast.matches(null, valid));
                    assertThrows(NullPointerException.class, () -> fast.matches("a", null));
                    assertThrows(NullPointerException.class, () -> fast.needsRehash(null));
                }),
                test("matches accepts any CharSequence", () ->
                        assertTrue(fast.matches(new StringBuilder("a"), valid))),
                test("needsRehash with of(10): true only for a lower cost; version and higher cost give false", () -> {
                    var hasher = BCryptPasswordHasher.of(10);
                    assertTrue(hasher.needsRehash(stored("$2a$04$")));
                    assertTrue(hasher.needsRehash(stored("$2b$09$")));
                    assertFalse(hasher.needsRehash(stored("$2a$10$")));
                    assertFalse(hasher.needsRehash(stored("$2b$10$")));
                    assertFalse(hasher.needsRehash(stored("$2y$10$")));
                    assertFalse(hasher.needsRehash(stored("$2a$12$")));
                    long start = System.nanoTime();
                    withoutBcrypt("needsRehash of a cost-31 hash", () -> {
                        assertFalse(hasher.needsRehash(cost31));
                        assertFalse(hasher.needsRehash(stored("$2y$31$")));
                    });
                    long millis = (System.nanoTime() - start) / 1_000_000;
                    assertTrue(millis < 1_000, "needsRehash computed something: " + millis + " ms");
                }).timeout(Duration.ofSeconds(10)),
                test("needsRehash is false for the hasher's own hashes, at every cost from 4 to 6", () -> {
                    for (int cost = 4; cost <= 6; cost++) {
                        var hasher = BCryptPasswordHasher.of(cost);
                        var hash = hasher.hash("pw");
                        assertTrue(hash.startsWith("$2a$0" + cost + "$"), hash.substring(0, 7));
                        assertFalse(hasher.needsRehash(hash));
                        assertTrue(BCryptPasswordHasher.of(cost + 1).needsRehash(hash));
                        assertFalse(BCryptPasswordHasher.of(cost - 1 < 4 ? 4 : cost - 1).needsRehash(hash));
                    }
                }));
    }
}
