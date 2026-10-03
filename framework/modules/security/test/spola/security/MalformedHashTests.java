package spola.security;

import static spola.security.Support.fast;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.Base64;
import java.util.List;
import spola.test.Suite;

/** Criterion 5: malformed stored hashes give IllegalArgumentException without echoing the string. */
final class MalformedHashTests {

    private MalformedHashTests() {
    }

    /** A malformed stored hash and what is wrong with it. */
    record Case(String label, String encoded) {
        @Override
        public String toString() {
            return label;
        }
    }

    static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
    static final String SALT = B64.encodeToString(HasherTests.salt(16));        // 22 characters
    static final String HASH = B64.encodeToString(HasherTests.salt(32));        // 43 characters
    static final String PREFIX = "$pbkdf2-sha256$i=10000$";
    static final String GOOD = PREFIX + SALT + "$" + HASH;

    static String bytes(int n) {
        return B64.encodeToString(new byte[n]);
    }

    static final List<Case> CASES = List.of(
            new Case("wrong id", "$pbkdf2$i=10000$" + SALT + "$" + HASH),
            new Case("pbkdf2-sha512", "$pbkdf2-sha512$i=10000$" + SALT + "$" + HASH),
            new Case("id in upper case", "$PBKDF2-SHA256$i=10000$" + SALT + "$" + HASH),
            new Case("id from Spring's {pbkdf2} prefix", "{pbkdf2}" + SALT + HASH),
            new Case("missing part (no hash)", PREFIX + SALT),
            new Case("missing part (no salt)", PREFIX + HASH),
            new Case("missing leading $", "pbkdf2-sha256$i=10000$" + SALT + "$" + HASH),
            new Case("an extra part", GOOD + "$" + HASH),
            new Case("a trailing $", GOOD + "$"),
            new Case("an empty salt", PREFIX + "$" + HASH),
            new Case("padding = on the salt", PREFIX + SALT + "==$" + HASH),
            new Case("padding = on the hash", PREFIX + SALT + "$" + HASH + "="),
            new Case("URL-safe Base64 - in the salt", PREFIX + "-" + SALT.substring(1) + "$" + HASH),
            new Case("URL-safe Base64 _ in the hash", PREFIX + SALT + "$_" + HASH.substring(1)),
            new Case("non-canonical Base64 (unused bits set) in the salt", PREFIX + "AAAAAAAAAAAAAAAAAAAAAB$" + HASH),
            new Case("non-canonical Base64 (unused bits set) in the hash", PREFIX + SALT + "$" + bytes(32).substring(0, 42) + "B"),
            new Case("Base64 of impossible length (25 characters)", PREFIX + SALT + "AAA$" + HASH),
            new Case("leading zero in i", "$pbkdf2-sha256$i=010000$" + SALT + "$" + HASH),
            new Case("i=0", "$pbkdf2-sha256$i=0$" + SALT + "$" + HASH),
            new Case("i=10000001", "$pbkdf2-sha256$i=10000001$" + SALT + "$" + HASH),
            new Case("i=99999999", "$pbkdf2-sha256$i=99999999$" + SALT + "$" + HASH),
            new Case("i not a number", "$pbkdf2-sha256$i=ten$" + SALT + "$" + HASH),
            new Case("i in exponent form", "$pbkdf2-sha256$i=1e4$" + SALT + "$" + HASH),
            new Case("i with a sign", "$pbkdf2-sha256$i=+10000$" + SALT + "$" + HASH),
            new Case("i negative", "$pbkdf2-sha256$i=-10000$" + SALT + "$" + HASH),
            new Case("i empty", "$pbkdf2-sha256$i=$" + SALT + "$" + HASH),
            new Case("i that overflows int", "$pbkdf2-sha256$i=4294977296$" + SALT + "$" + HASH),
            new Case("i that overflows long", "$pbkdf2-sha256$i=99999999999999999999$" + SALT + "$" + HASH),
            new Case("i in full-width digits", "$pbkdf2-sha256$i=１００００$" + SALT + "$" + HASH),
            new Case("another parameter name", "$pbkdf2-sha256$rounds=10000$" + SALT + "$" + HASH),
            new Case("an extra parameter", "$pbkdf2-sha256$i=10000,l=32$" + SALT + "$" + HASH),
            new Case("salt too short (7 bytes)", PREFIX + bytes(7) + "$" + HASH),
            new Case("salt too long (65 bytes)", PREFIX + bytes(65) + "$" + HASH),
            new Case("hash too short (15 bytes)", PREFIX + SALT + "$" + bytes(15)),
            new Case("hash too long (65 bytes)", PREFIX + SALT + "$" + bytes(65)),
            new Case("257 characters", PREFIX + SALT + "$" + HASH + "A".repeat(257 - GOOD.length())),
            new Case("a line feed", PREFIX + SALT + "\n$" + HASH),
            new Case("a NUL", PREFIX + SALT + "$" + HASH + "\u0000"),
            new Case("a tab", "\t" + GOOD),
            new Case("a trailing CR LF", GOOD + "\r\n"),
            new Case("a space", " " + GOOD),
            new Case("a non-ASCII letter", PREFIX + "é" + SALT.substring(1) + "$" + HASH),
            new Case("a bidi override", GOOD + "‮"),
            new Case("the empty string", ""));

    static Suite tests() {
        var hasher = fast();
        return suite("malformed stored hashes",
                test("the good base string is accepted, so each case fails for its own reason", () -> {
                    assertFalse(hasher.matches("pw", GOOD));
                    assertFalse(hasher.needsRehash(GOOD));
                    assertEquals(256, (PREFIX + SALT + "$" + HASH + "A".repeat(256 - GOOD.length())).length());
                    assertEquals(257, CASES.stream().filter(c -> c.label().equals("257 characters")).findFirst()
                            .orElseThrow().encoded().length());
                    assertTrue(CASES.size() >= 25);
                }),
                parameterized("matches and needsRehash throw IllegalArgumentException without the string", CASES, c -> {
                    var fromMatches = assertThrows(IllegalArgumentException.class, () -> hasher.matches("pw", c.encoded()));
                    var fromNeedsRehash = assertThrows(IllegalArgumentException.class, () -> hasher.needsRehash(c.encoded()));
                    // A malformed hash throws whatever the password is, also one matches would refuse.
                    assertThrows(IllegalArgumentException.class, () -> hasher.matches("", c.encoded()));
                    for (var e : List.of(fromMatches, fromNeedsRehash)) {
                        checkMessage(e, c.encoded());
                    }
                }),
                test("null gives NullPointerException in matches and needsRehash", () -> {
                    assertThrows(NullPointerException.class, () -> hasher.matches("pw", null));
                    assertThrows(NullPointerException.class, () -> hasher.needsRehash(null));
                }));
    }

    /** The message is short, printable ASCII, and has neither the string nor its salt or hash. */
    static void checkMessage(IllegalArgumentException e, String encoded) {
        var message = e.getMessage();
        assertTrue(message.startsWith("malformed stored password hash: "), message);
        assertTrue(message.length() <= 120, message);
        assertTrue(message.chars().allMatch(ch -> ch >= 0x20 && ch < 0x7f), message);
        assertTrue(e.getCause() == null, "no cause");
        if (!encoded.isEmpty()) {
            assertFalse(message.contains(encoded), "the message contains the string");
        }
        for (var part : encoded.split("\\$")) {
            if (part.length() >= 8 && !part.equals("pbkdf2-sha256")) {
                assertFalse(message.contains(part), "the message contains a part of the string");
            }
        }
    }
}
