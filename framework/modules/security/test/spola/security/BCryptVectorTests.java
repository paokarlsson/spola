package spola.security;

import static java.nio.charset.StandardCharsets.UTF_8;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertNotEquals;
import static spola.test.Assertions.assertNotNull;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;
import spola.test.Suite;

/**
 * Criteria 4 and 5: the published bcrypt vectors (jBCrypt, Openwall) and independent ones from
 * libxcrypt, through the package-private primitive and through BCryptPasswordHasher.
 */
final class BCryptVectorTests {

    private BCryptVectorTests() {
    }

    /**
     * A vector: the password as text ({@code null} for raw bytes), its bytes and the expected
     * string.
     */
    record Vector(String label, String text, byte[] password, String expected) {

        static Vector of(String text, String expected) {
            return new Vector(describe(text), text, text.getBytes(UTF_8), expected);
        }

        static Vector raw(String hex, String expected) {
            return new Vector("bytes " + hex, null, Support.hex(hex.replace(" ", "")), expected);
        }

        /** Whether BCryptPasswordHasher accepts the password: text of 1 to 72 bytes. */
        boolean accepted() {
            return text != null && password.length >= 1 && password.length <= 72;
        }

        @Override
        public String toString() {
            return label + " " + expected.substring(0, 7);
        }
    }

    static final String SPECIAL = "~!@#$%^&*()" + " ".repeat(6) + "~!@#$%^&*()PNBFRD";
    static final String L72 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    static final String L98 = L72 + "chars after 72 are ignored";

    /** jBCrypt's test suite (Damien Miller), which Spring's BCrypt is built on. */
    static final List<Vector> JBCRYPT = List.of(
            Vector.of("", "$2a$06$DCq7YPn5Rq63x1Lad4cll.TV4S6ytwfsfvkgY8jIucDrjc8deX1s."),
            Vector.of("", "$2a$08$HqWuK6/Ng6sg9gQzbLrgb.Tl.ZHfXLhvt/SgVyWhQqgqcZ7ZuUtye"),
            Vector.of("", "$2a$10$k1wbIrmNyFAPwPVPSVa/zecw2BCEnBwVS2GbrmgzxFUOqW9dk4TCW"),
            Vector.of("", "$2a$12$k42ZFHFWqBp3vWli.nIn8uYyIkbvYRvodzbfbK18SSsY.CsIQPlxO"),
            Vector.of("a", "$2a$06$m0CrhHm10qJ3lXRY.5zDGO3rS2KdeeWLuGmsfGlMfOxih58VYVfxe"),
            Vector.of("a", "$2a$08$cfcvVd2aQ8CMvoMpP2EBfeodLEkkFJ9umNEfPD18.hUF62qqlC/V."),
            Vector.of("a", "$2a$10$k87L/MF28Q673VKh8/cPi.SUl7MU/rWuSiIDDFayrKk/1tBsSQu4u"),
            Vector.of("a", "$2a$12$8NJH3LsPrANStV6XtBakCez0cKHXVxmvxIlcz785vxAIZrihHZpeS"),
            Vector.of("abc", "$2a$06$If6bvum7DFjUnE9p2uDeDu0YHzrHM6tf.iqN8.yx.jNN1ILEf7h0i"),
            Vector.of("abc", "$2a$08$Ro0CUfOqk6cXEKf3dyaM7OhSCvnwM9s4wIX9JeLapehKK5YdLxKcm"),
            Vector.of("abc", "$2a$10$WvvTPHKwdBJ3uk0Z37EMR.hLA2W6N9AEBhEgrAOljy2Ae5MtaSIUi"),
            Vector.of("abc", "$2a$12$EXRkfkdmXn2gzds2SSitu.MW9.gAVqa9eLS1//RYtYCmB1eLHg.9q"),
            Vector.of("abcdefghijklmnopqrstuvwxyz", "$2a$06$.rCVZVOThsIa97pEDOxvGuRRgzG64bvtJ0938xuqzv18d3ZpQhstC"),
            Vector.of("abcdefghijklmnopqrstuvwxyz", "$2a$08$aTsUwsyowQuzRrDqFflhgekJ8d9/7Z3GV3UcgvzQW3J5zMyrTvlz."),
            Vector.of("abcdefghijklmnopqrstuvwxyz", "$2a$10$fVH8e28OQRj9tqiDXs1e1uxpsjN0c7II7YPKXua2NAKYvM6iQk7dq"),
            Vector.of("abcdefghijklmnopqrstuvwxyz", "$2a$12$D4G5f18o7aMMfwasBL7GpuQWuP3pkrZrOAnqP.bmezbMng.QwJ/pG"),
            Vector.of(SPECIAL, "$2a$06$fPIsBO8qRqkjj273rfaOI.HtSV9jLDpTbZn782DC6/t7qT67P6FfO"),
            Vector.of(SPECIAL, "$2a$08$Eq2r4G/76Wv39MzSX262huzPz612MZiYHVUJe/OcOql2jo4.9UxTW"),
            Vector.of(SPECIAL, "$2a$10$LgfYWkbzEvQ4JakH7rOvHe0y8pHKF9OaFgwUZ2q7W2FFZmZzJYlfS"),
            Vector.of(SPECIAL, "$2a$12$WApznUOJfkEGSmYRfnkrPOr466oFDCaj4b6HY3EXGvfxm43seyhgC"));

    /** Openwall crypt_blowfish (wrapper.c). */
    static final Vector OPENWALL_L98 = Vector.of(L98, "$2a$05$abcdefghijklmnopqrstuu5s2v8.iXieOjg/.AySBTTZIIVFJeBui");
    static final Vector OPENWALL_FF_FF_A3 = Vector.raw("ff ff a3", "$2b$05$/OK.fbVrR/bpIqNJ5ianF.CE5elHaaO4EbggVDjb8P19RukzXSM3e");
    static final List<Vector> OPENWALL = List.of(
            Vector.of("U*U", "$2a$05$CCCCCCCCCCCCCCCCCCCCC.E5YPO9kmyuRGyh0XouQYb4YMJKvyOeW"),
            Vector.of("U*U*", "$2a$05$CCCCCCCCCCCCCCCCCCCCC.VGOzA784oUp/Z0DY336zx7pLYAy0lwK"),
            Vector.of("U*U*U", "$2a$05$XXXXXXXXXXXXXXXXXXXXXOAcXxm9kjPGEMsLznoKqmqw7tc8WCx4a"),
            OPENWALL_L98,
            Vector.of("", "$2a$05$CCCCCCCCCCCCCCCCCCCCC.7uG0VCzI2bS7j6ymqJi9CdcdxiRTWNy"),
            Vector.raw("a3", "$2y$05$/OK.fbVrR/bpIqNJ5ianF.Sa7shbm4.OzKpvFnX1pQLmQW96oUlCq"),
            OPENWALL_FF_FF_A3,
            Vector.raw("ff ff a3", "$2y$05$/OK.fbVrR/bpIqNJ5ianF.CE5elHaaO4EbggVDjb8P19RukzXSM3e"));

    static final List<Vector> PUBLISHED = concat(JBCRYPT, OPENWALL);

    /** Independent vectors from libxcrypt ($2b$, cost 05, one salt), and the use case's vectors. */
    static final String L72_DIGITS = "0123456789".repeat(7) + "ab";
    static final String COMPOSED = "\u00e9";            // é as one code point, 2 bytes
    static final String DECOMPOSED = "e\u0301";         // e + combining acute accent, 3 bytes
    static final Vector LIBXCRYPT_COMPOSED = Vector.of(COMPOSED, "$2b$05$Ro0CUfOqk6cXEKf3dyaM7OrPiNk.sCjPBJgB586DXbvR7BCyIeK4u");
    static final Vector LIBXCRYPT_DECOMPOSED = Vector.of(DECOMPOSED, "$2b$05$Ro0CUfOqk6cXEKf3dyaM7OxTExh/ADjMJUTgkLPpLHB9EL0C7gukq");
    /** The use case's "hemligt" with cost 04 and the salt 0x10..0x1f. */
    static final Vector HEMLIGT_COST_4 = Vector.of("hemligt", "$2a$04$C/CQCvOTDfaWEPmZF/ycFuUeA49K0sshzyZIXUTBmHM4.xmgxaWVq");
    static final List<Vector> LIBXCRYPT = List.of(
            Vector.of("p\u00e5ssw\u00f6rd\u20ac", "$2b$05$Ro0CUfOqk6cXEKf3dyaM7O.S2ZSxjNS5nuoViyt6yOY7ibmQwDI9q"),
            Vector.of("\u5bc6\u7801", "$2b$05$Ro0CUfOqk6cXEKf3dyaM7ODF6gsKUXq4Djycywf7GhU9udo5XTFB2"),
            Vector.of("\ud83d\udd11", "$2b$05$Ro0CUfOqk6cXEKf3dyaM7Ohl9xa58bNl6EQFRekkrSqQExcldbnyi"),
            LIBXCRYPT_COMPOSED,
            LIBXCRYPT_DECOMPOSED,
            Vector.of("0123456789".repeat(7) + "a", "$2b$05$Ro0CUfOqk6cXEKf3dyaM7O6.RPl6KXlZILu4rgREbR4cKEWJAEJO2"),
            Vector.of(L72_DIGITS, "$2b$05$Ro0CUfOqk6cXEKf3dyaM7ODEomjXJBnwUsayvMT.y0r2XwXzL7pLm"),
            Vector.of("\u20ac".repeat(24), "$2b$05$Ro0CUfOqk6cXEKf3dyaM7OBEbxJMKi50s1SLoOuLvN/iq/buH/sla"),
            Vector.of("\u20ac".repeat(23) + "ab", "$2b$05$Ro0CUfOqk6cXEKf3dyaM7OAaksHpP8MpJaZejZlDtYT0IoDUyo31m"),
            Vector.of("hemligt", "$2a$10$C/CQCvOTDfaWEPmZF/ycFuvu6miZtNk3jj/4zDoWuN3Icm9TmKt9e"),
            HEMLIGT_COST_4,
            Vector.of("hunter2hunter2", "$2a$10$C/CQCvOTDfaWEPmZF/ycFuijXL7x1mYyENSqiAN9JavcgfCUlLFPC"));
    static final String L73_DIGITS = L72_DIGITS + "c";
    static final String L72_DIGITS_HASH = "$2b$05$Ro0CUfOqk6cXEKf3dyaM7ODEomjXJBnwUsayvMT.y0r2XwXzL7pLm";

    /** A short ASCII label for a password: quoted, non-ASCII as \\uXXXX, at most 32 characters. */
    static String describe(String password) {
        var label = new StringBuilder("\"");
        password.chars().forEach(c -> label.append(c >= 0x20 && c < 0x7f ? Character.toString(c) : String.format("\\u%04x", c)));
        var text = label.append('"').toString();
        return text.length() <= 32 ? text : text.substring(0, 28) + "...\"";
    }

    /** The string with {@code $2<version>$} instead of its own version. */
    static String withVersion(String encoded, char version) {
        return "$2" + version + encoded.substring(3);
    }

    /**
     * The primitive, as crypt(3) would be called: the version, cost and salt are read from the
     * first 29 characters of {@code setting}, and the whole string is computed by the same
     * package-private path as {@link BCryptPasswordHasher#hash}.
     */
    static String crypt(byte[] password, String setting) {
        char version = setting.charAt(2);
        int cost = Integer.parseInt(setting.substring(4, 6));
        var salt = assertNotNull(BCrypt.fromBase64(setting, 7, BCrypt.SALT_BYTES));
        return BCrypt.format(version, cost, salt, password);
    }

    /** The password with its last {@code char} changed (bit 0 flipped: still the same kind of char). */
    static String oneCharacterDifferent(String password) {
        int last = password.length() - 1;
        return password.substring(0, last) + (char) (password.charAt(last) ^ 1);
    }

    static Suite tests() {
        var hasher = BCryptPasswordHasher.of(4);
        return suite("bcrypt vectors",
                parameterized("the primitive gives each published string (jBCrypt, Openwall)", PUBLISHED, v ->
                        assertEquals(v.expected(), crypt(v.password(), v.expected()))),
                test("bytes >= 0x80 are not sign-extended: ff ff a3 gives the same hash with $2a$, $2b$ and $2y$", () -> {
                    var expected = OPENWALL_FF_FF_A3.expected();
                    for (char version : new char[] {'a', 'b', 'y'}) {
                        assertEquals(withVersion(expected, version),
                                crypt(OPENWALL_FF_FF_A3.password(), withVersion(expected, version)));
                    }
                    // libxcrypt's $2a$ countermeasure would give .nqd1wy...; jBCrypt and Spola do not apply it.
                    assertNotEquals("$2a$05$/OK.fbVrR/bpIqNJ5ianF.nqd1wy.pTMdcvrRWxyiGL2eMz.2a85.",
                            crypt(OPENWALL_FF_FF_A3.password(), withVersion(expected, 'a')));
                }),
                parameterized("matches is true with $2a$, $2b$ and $2y$, and false for one character different",
                        concat(PUBLISHED, LIBXCRYPT).stream().filter(Vector::accepted).toList(), v -> {
                            for (char version : new char[] {'a', 'b', 'y'}) {
                                assertTrue(hasher.matches(v.text(), withVersion(v.expected(), version)), "$2" + version + "$");
                            }
                            assertFalse(hasher.matches(oneCharacterDifferent(v.text()), v.expected()));
                        }),
                test("every published vector with a 1-72 byte text password is in the matches table", () -> {
                    var accepted = PUBLISHED.stream().filter(Vector::accepted).count();
                    assertEquals(19L, accepted); // 16 of jBCrypt (not ""), 3 of Openwall (not L98, "" or raw bytes)
                }),
                test("matches(\"\", ...) is false for the vectors of the empty password", () -> {
                    var empty = PUBLISHED.stream().filter(v -> "".equals(v.text())).toList();
                    assertEquals(5, empty.size());
                    for (var v : empty) {
                        assertFalse(hasher.matches("", v.expected()));
                    }
                }),
                test("CVE-2025-22228: L98 verifies only as its first 72 bytes, and cannot be hashed", () -> {
                    var stored = OPENWALL_L98.expected();
                    assertEquals(98, L98.getBytes(UTF_8).length);
                    assertEquals(L72, L98.substring(0, 72));
                    assertTrue(hasher.matches(L72, stored));
                    assertFalse(hasher.matches(L98, stored));
                    assertFalse(hasher.matches(L72 + "c", stored));
                    var e = assertThrows(IllegalArgumentException.class, () -> hasher.hash(L98));
                    assertFalse(e.getMessage().contains("chars after"), e.getMessage());
                }),
                parameterized("the primitive gives each libxcrypt string with $2a$, $2b$ and $2y$", LIBXCRYPT, v -> {
                    for (char version : new char[] {'a', 'b', 'y'}) {
                        var expected = withVersion(v.expected(), version);
                        assertEquals(expected, crypt(v.password(), expected));
                    }
                }),
                test("73 bytes: the primitive gives the 72-byte string, but matches is false and hash refuses", () -> {
                    assertEquals(73, L73_DIGITS.getBytes(UTF_8).length);
                    assertEquals(L72_DIGITS_HASH, crypt(L73_DIGITS.getBytes(UTF_8), L72_DIGITS_HASH));
                    assertTrue(hasher.matches(L72_DIGITS, L72_DIGITS_HASH));
                    assertFalse(hasher.matches(L73_DIGITS, L72_DIGITS_HASH));
                    assertThrows(IllegalArgumentException.class, () -> hasher.hash(L73_DIGITS));
                }),
                test("\"\\u00e9\" and \"e\\u0301\" give different hashes and do not verify each other", () -> {
                    var composed = LIBXCRYPT_COMPOSED;
                    var decomposed = LIBXCRYPT_DECOMPOSED;
                    assertEquals(COMPOSED, composed.text());
                    assertEquals(DECOMPOSED, decomposed.text());
                    assertEquals(2, composed.password().length);
                    assertEquals(3, decomposed.password().length);
                    assertNotEquals(composed.expected(), decomposed.expected());
                    assertFalse(hasher.matches(COMPOSED, decomposed.expected()));
                    assertFalse(hasher.matches(DECOMPOSED, composed.expected()));
                    var fresh = hasher.hash(COMPOSED);
                    assertTrue(hasher.matches(COMPOSED, fresh));
                    assertFalse(hasher.matches(DECOMPOSED, fresh));
                }),
                test("the byte lengths of the libxcrypt passwords are the documented ones", () -> assertEquals(
                        List.of(13, 6, 4, 2, 3, 71, 72, 72, 71, 7, 7, 14),
                        LIBXCRYPT.stream().map(v -> v.password().length).toList())));
    }

    static <T> List<T> concat(List<? extends T> first, List<? extends T> second) {
        var all = new java.util.ArrayList<T>(first);
        all.addAll(second);
        return List.copyOf(all);
    }
}
