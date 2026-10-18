package spola.security;

import static java.nio.charset.StandardCharsets.UTF_8;
import static spola.security.Support.fast;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertNotEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import spola.test.Body;
import spola.test.Suite;

/** Criterion 4 of 10a: Pbkdf2PasswordHasher; and 15c criterion 5: at most 32 bytes for new hashes. */
final class HasherTests {

    private HasherTests() {
    }

    static final Pattern DEFAULT_FORMAT =
            Pattern.compile("\\$pbkdf2-sha256\\$i=600000\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}");
    static final Pattern FAST_FORMAT =
            Pattern.compile("\\$pbkdf2-sha256\\$i=10000\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}");
    static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();

    static final String LONE_HIGH = "abc\ud83d";
    static final String LONE_LOW = "\ude00abc";
    static final String TOO_LONG = "x".repeat(Pbkdf2PasswordHasher.MAX_PASSWORD_CHARS + 1);

    /** A hash built by hand from the documented format. */
    static String byHand(String password, byte[] salt, int iterations, int length) {
        var hash = Pbkdf2.deriveSha256(password.getBytes(UTF_8), salt, iterations, length);
        return "$pbkdf2-sha256$i=" + iterations + "$" + B64.encodeToString(salt) + "$" + B64.encodeToString(hash);
    }

    static byte[] salt(int length) {
        var salt = new byte[length];
        for (int i = 0; i < length; i++) {
            salt[i] = (byte) (i * 37 + 11);
        }
        return salt;
    }

    static Suite tests() {
        var hasher = fast();
        var stored = hasher.hash("correct horse battery staple");
        return suite("Pbkdf2PasswordHasher",
                test("defaults(): one hash has the exact format and verifies", () -> {
                    var defaults = Pbkdf2PasswordHasher.defaults();
                    var hash = defaults.hash("correct horse battery staple");
                    assertTrue(DEFAULT_FORMAT.matcher(hash).matches(), "format");
                    assertFalse(defaults.needsRehash(hash));
                    assertTrue(hasher.needsRehash(hash));
                    assertTrue(defaults.matches("correct horse battery staple", hash));
                }),
                test("of(10_000, 16, 32) has the same format with i=10000", () ->
                        assertTrue(FAST_FORMAT.matcher(stored).matches(), "format")),
                test("two hashes of the same password differ (different salt) and both verify", () -> {
                    var other = hasher.hash("correct horse battery staple");
                    assertNotEquals(stored, other);
                    assertNotEquals(stored.split("\\$")[3], other.split("\\$")[3]);
                    assertTrue(hasher.matches("correct horse battery staple", other));
                }),
                test("matches: the right password is true", () ->
                        assertTrue(hasher.matches("correct horse battery staple", stored))),
                test("matches: a StringBuilder with the right password is true", () ->
                        assertTrue(hasher.matches(new StringBuilder("correct horse battery staple"), stored))),
                test("matches: wrong passwords are false", () -> {
                    for (var wrong : List.of("Tr0ub4dor&3", "correct horse battery stapl", "correct horse battery staplE",
                            "Correct horse battery staple", "correct horse battery staple ", " correct horse battery staple",
                            "", TOO_LONG, "x".repeat(5000), LONE_HIGH, LONE_LOW, "\ud83d", "\ude00")) {
                        assertFalse(hasher.matches(wrong, stored), "for a password of " + wrong.length() + " chars");
                    }
                }),
                test("matches: 1024 characters is the longest password that verifies", () -> {
                    var longest = "é".repeat(1023) + "\ud83d";
                    assertThrows(IllegalArgumentException.class, () -> hasher.hash(longest));
                    var max = "é".repeat(1022) + "😀";
                    assertEquals(1024, max.length());
                    var hash = hasher.hash(max);
                    assertTrue(hasher.matches(max, hash));
                    assertFalse(hasher.matches(max + "x", hash));
                }),
                test("a hash built by hand from the documented format verifies", () -> {
                    var hash = byHand("correct horse battery staple", salt(16), 10_000, 32);
                    assertTrue(FAST_FORMAT.matcher(hash).matches(), "format");
                    assertTrue(hasher.matches("correct horse battery staple", hash));
                    assertFalse(hasher.matches("correct horse battery stapl", hash));
                    assertFalse(hasher.needsRehash(hash));
                }),
                test("a hash with other settings verifies with its own settings (8-byte salt, 1 iteration, 64 bytes)", () -> {
                    var hash = byHand("pw", salt(8), 1, 64);
                    assertTrue(hasher.matches("pw", hash));
                    assertTrue(hasher.needsRehash(hash));
                    var hash16 = byHand("pw", salt(64), 2, 16);
                    assertTrue(hasher.matches("pw", hash16));
                }),
                test("a password is UTF-8 encoded, without normalisation", () -> {
                    var salt = salt(16);
                    for (var password : List.of("é", "é", "gröt€中😀", " x ")) {
                        var hash = byHand(password, salt, 10_000, 32);
                        assertTrue(hasher.matches(password, hash), "UTF-8 hash of a non-ASCII password");
                    }
                    var composed = byHand("é", salt, 10_000, 32);
                    var decomposed = byHand("é", salt, 10_000, 32);
                    assertNotEquals(composed, decomposed);
                    assertFalse(hasher.matches("é", composed));
                    assertFalse(hasher.matches("é", decomposed));
                    assertFalse(hasher.matches("é", hasher.hash("é")));
                    assertFalse(hasher.matches("x", hasher.hash("x ")));
                    // ISO-8859-1 bytes of é are not what is hashed.
                    var latin1 = Pbkdf2.deriveSha256("é".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                            salt, 10_000, 32);
                    assertNotEquals(composed.split("\\$")[4], B64.encodeToString(latin1));
                }),
                test("hash: empty, too long and lone surrogates give IllegalArgumentException; null gives NPE", () -> {
                    for (var bad : List.of("", TOO_LONG, LONE_HIGH, LONE_LOW, "\ud83d", "\ude00")) {
                        assertThrows(IllegalArgumentException.class, () -> hasher.hash(bad));
                    }
                    assertThrows(NullPointerException.class, () -> hasher.hash(null));
                }),
                test("matches: a null password or hash gives NullPointerException", () -> {
                    assertThrows(NullPointerException.class, () -> hasher.matches(null, stored));
                    assertThrows(NullPointerException.class, () -> hasher.matches("x", null));
                    assertThrows(NullPointerException.class, () -> hasher.needsRehash(null));
                }),
                test("of(...) outside the bounds gives IllegalArgumentException; the bounds themselves work", () -> {
                    List<Body> bad = List.of(
                            () -> Pbkdf2PasswordHasher.of(9_999, 16, 32),
                            () -> Pbkdf2PasswordHasher.of(10_000_001, 16, 32),
                            () -> Pbkdf2PasswordHasher.of(0, 16, 32),
                            () -> Pbkdf2PasswordHasher.of(-1, 16, 32),
                            () -> Pbkdf2PasswordHasher.of(10_000, 15, 32),
                            () -> Pbkdf2PasswordHasher.of(10_000, 65, 32),
                            () -> Pbkdf2PasswordHasher.of(10_000, 16, 15),
                            () -> Pbkdf2PasswordHasher.of(10_000, 16, 33),
                            () -> Pbkdf2PasswordHasher.of(10_000, 16, 65));
                    for (var call : bad) {
                        assertThrows(IllegalArgumentException.class, call);
                    }
                    Pbkdf2PasswordHasher.of(10_000_000, 64, 32);
                    var small = Pbkdf2PasswordHasher.of(10_000, 16, 16);
                    var smallHash = small.hash("pw");
                    assertTrue(Pattern.matches("\\$pbkdf2-sha256\\$i=10000\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{22}", smallHash));
                    var large = Pbkdf2PasswordHasher.of(10_000, 64, 32);
                    var largeHash = large.hash("pw");
                    assertTrue(Pattern.matches("\\$pbkdf2-sha256\\$i=10000\\$[A-Za-z0-9+/]{86}\\$[A-Za-z0-9+/]{43}", largeHash));
                    assertTrue(large.matches("pw", largeHash));
                }),
                test("hash length: of(..., ..., 33) and of(..., ..., 64) are refused; a stored 64-byte hash verifies and needs a rehash", () -> {
                    assertEquals(32, Pbkdf2PasswordHasher.MAX_HASH_BYTES);
                    for (int hashBytes : new int[] {33, 48, 64}) {
                        var e = assertThrows(IllegalArgumentException.class, () -> Pbkdf2PasswordHasher.of(10_000, 16, hashBytes));
                        assertEquals("hashBytes must be 16 to 32", e.getMessage());
                    }
                    for (int hashBytes : new int[] {16, 32}) {
                        var allowed = Pbkdf2PasswordHasher.of(10_000, 16, hashBytes);
                        assertFalse(allowed.needsRehash(allowed.hash("pw")));
                    }
                    var defaults = Pbkdf2PasswordHasher.defaults();
                    for (int length : new int[] {33, 48, 64}) {
                        var hash = byHand("correct horse battery staple", salt(16), 10_000, length);
                        assertEquals(length, Base64.getDecoder().decode(hash.split("\\$")[4]).length);
                        assertTrue(hasher.matches("correct horse battery staple", hash), length + " bytes");
                        assertFalse(hasher.matches("correct horse battery stapl", hash), length + " bytes");
                        assertTrue(hasher.needsRehash(hash), length + " bytes");
                        assertTrue(defaults.needsRehash(hash), length + " bytes");
                    }
                    // A stored 32-byte hash with the same settings is the one that needs no rehash.
                    assertFalse(hasher.needsRehash(byHand("correct horse battery staple", salt(16), 10_000, 32)));
                }),
                test("hash length: a login with upgrade moves a stored 64-byte hash to 32 bytes", () -> {
                    var store = new InMemoryUserStore();
                    var old = byHand("correct horse battery staple", salt(16), 10_000, 64);
                    store.add(new User("ada", old, Set.of(), true));
                    var authenticator = Authenticator.of(store, hasher, store.upgrader());
                    assertTrue(authenticator.authenticate("ada", "correct horse battery staple") instanceof AuthResult.Authenticated);
                    var upgraded = store.find("ada").orElseThrow().passwordHash();
                    assertNotEquals(old, upgraded);
                    assertTrue(FAST_FORMAT.matcher(upgraded).matches(), "32 bytes, the hasher's settings");
                    assertFalse(hasher.needsRehash(upgraded));
                    assertTrue(authenticator.authenticate("ada", "correct horse battery staple") instanceof AuthResult.Authenticated);
                    assertEquals(upgraded, store.find("ada").orElseThrow().passwordHash());
                }),
                test("needsRehash: false for its own hashes, true when iterations, salt or hash length differ", () -> {
                    assertFalse(hasher.needsRehash(stored));
                    assertFalse(hasher.needsRehash(byHand("pw", salt(16), 10_000, 32)));
                    assertTrue(hasher.needsRehash(Pbkdf2PasswordHasher.of(20_000, 16, 32).hash("pw")));
                    assertTrue(hasher.needsRehash(byHand("pw", salt(16), 9_999, 32)));
                    assertTrue(hasher.needsRehash(byHand("pw", salt(16), 10_001, 32)));
                    assertTrue(hasher.needsRehash(Pbkdf2PasswordHasher.of(10_000, 24, 32).hash("pw")));
                    assertTrue(hasher.needsRehash(byHand("pw", salt(15), 10_000, 32)));
                    assertTrue(hasher.needsRehash(byHand("pw", salt(16), 10_000, 48)));
                    assertTrue(hasher.needsRehash(Pbkdf2PasswordHasher.of(10_000, 16, 16).hash("pw")));
                    assertTrue(hasher.needsRehash(byHand("pw", salt(16), 10_000, 31)));
                }),
                test("toString shows the settings", () -> assertEquals(
                        "Pbkdf2PasswordHasher[iterations=10000, saltBytes=16, hashBytes=32]", hasher.toString())));
    }
}
