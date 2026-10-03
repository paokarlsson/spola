package spola.security;

import static java.nio.charset.StandardCharsets.UTF_8;
import static spola.security.Support.hex;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import spola.test.Body;
import spola.test.Suite;

/** Criterion 3: PBKDF2-HMAC-SHA256 against RFC 7914 §11 and against the JDK's SecretKeyFactory. */
final class Pbkdf2Tests {

    private Pbkdf2Tests() {
    }

    /** Characters for random passwords: ASCII, Latin-1, BMP and supplementary (surrogate pairs). */
    static final List<String> ALPHABET = List.of("a", "Z", "7", " ", "~", "$", "é", "å", "ß",
            "€", "中", "́", "😀", "𐀀", "􏿿");

    static Suite tests() {
        return suite("Pbkdf2",
                test("RFC 7914 §11: P=passwd, S=salt, c=1, dkLen=64", () -> assertEquals(
                        "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc"
                                + "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
                        hex(Pbkdf2.deriveSha256("passwd".getBytes(UTF_8), "salt".getBytes(UTF_8), 1, 64)))),
                test("RFC 7914 §11: P=Password, S=NaCl, c=80000, dkLen=64", () -> assertEquals(
                        "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56"
                                + "a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
                        hex(Pbkdf2.deriveSha256("Password".getBytes(UTF_8), "NaCl".getBytes(UTF_8), 80_000, 64)))),
                test("a prefix of the output is the output for the shorter length", () -> {
                    var full = Pbkdf2.deriveSha256("passwd".getBytes(UTF_8), "salt".getBytes(UTF_8), 1, 64);
                    for (int length : new int[] {1, 31, 32, 33, 63}) {
                        assertEquals(hex(Arrays.copyOf(full, length)),
                                hex(Pbkdf2.deriveSha256("passwd".getBytes(UTF_8), "salt".getBytes(UTF_8), 1, length)));
                    }
                }),
                test("60 random cases equal the JDK's PBKDF2WithHmacSHA256", Pbkdf2Tests::againstJdk),
                test("the arguments are not modified", () -> {
                    var password = "passwd".getBytes(UTF_8);
                    var salt = "salt".getBytes(UTF_8);
                    Pbkdf2.deriveSha256(password, salt, 2, 40);
                    assertEquals("passwd", new String(password, UTF_8));
                    assertEquals("salt", new String(salt, UTF_8));
                }),
                test("1024 bytes is the longest output", () -> assertEquals(1024,
                        Pbkdf2.deriveSha256(new byte[] {1}, new byte[] {2}, 1, Pbkdf2.MAX_LENGTH_BYTES).length)),
                test("invalid arguments give IllegalArgumentException", () -> {
                    byte[] p = {1};
                    byte[] s = {2};
                    List<Body> calls = List.of(
                            () -> Pbkdf2.deriveSha256(null, s, 1, 32),
                            () -> Pbkdf2.deriveSha256(new byte[0], s, 1, 32),
                            () -> Pbkdf2.deriveSha256(p, null, 1, 32),
                            () -> Pbkdf2.deriveSha256(p, new byte[0], 1, 32),
                            () -> Pbkdf2.deriveSha256(p, s, 0, 32),
                            () -> Pbkdf2.deriveSha256(p, s, -1, 32),
                            () -> Pbkdf2.deriveSha256(p, s, Integer.MIN_VALUE, 32),
                            () -> Pbkdf2.deriveSha256(p, s, 1, 0),
                            () -> Pbkdf2.deriveSha256(p, s, 1, -1),
                            () -> Pbkdf2.deriveSha256(p, s, 1, 1025),
                            () -> Pbkdf2.deriveSha256(p, s, 1, Integer.MAX_VALUE));
                    for (var call : calls) {
                        assertThrows(IllegalArgumentException.class, call);
                    }
                }));
    }

    static void againstJdk() throws Exception {
        var random = new Random(20261003L);
        var factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        boolean oneBlock = false;
        boolean manyBlocks = false;
        boolean nonAscii = false;
        boolean surrogates = false;
        for (int i = 0; i < 60; i++) {
            var password = new StringBuilder();
            int chars = 1 + random.nextInt(24);
            for (int c = 0; c < chars; c++) {
                // Every other case is ASCII only.
                var pick = i % 2 == 0 ? ALPHABET.get(random.nextInt(6)) : ALPHABET.get(random.nextInt(ALPHABET.size()));
                password.append(pick);
            }
            var text = password.toString();
            var salt = new byte[8 + random.nextInt(57)];
            random.nextBytes(salt);
            int iterations = 1 + random.nextInt(3_000);
            int length = i < 20 ? 1 + random.nextInt(32) : 33 + random.nextInt(68);
            oneBlock |= length <= 32;
            manyBlocks |= length > 64;
            nonAscii |= text.chars().anyMatch(c -> c > 0x7f);
            surrogates |= text.chars().anyMatch(c -> Character.isSurrogate((char) c));

            var spec = new PBEKeySpec(text.toCharArray(), salt, iterations, length * 8);
            var expected = factory.generateSecret(spec).getEncoded();
            spec.clearPassword();
            var actual = Pbkdf2.deriveSha256(text.getBytes(UTF_8), salt, iterations, length);
            assertEquals(hex(expected), hex(actual), "case " + i + ": " + iterations + " iterations, " + length + " bytes");
        }
        assertTrue(oneBlock && manyBlocks && nonAscii && surrogates, "coverage of the random cases");
    }
}
