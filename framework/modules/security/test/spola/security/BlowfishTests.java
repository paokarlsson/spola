package spola.security;

import static java.nio.charset.StandardCharsets.UTF_8;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import spola.test.Suite;

/**
 * Criterion 6: the Blowfish core, its tables derived from π, the JDK's own Blowfish and Eric
 * Young's vectors. Also the round count of criterion 7 and the source rules of criteria 2 and 16
 * for the new files (no Cipher, no table of constants).
 */
final class BlowfishTests {

    private BlowfishTests() {
    }

    /** A published Blowfish ECB vector (Eric Young): key, plaintext, ciphertext. */
    record Vector(String key, String plain, String cipher) {
    }

    static final List<Vector> ERIC_YOUNG = List.of(
            new Vector("0000000000000000", "0000000000000000", "4EF997456198DD78"),
            new Vector("FFFFFFFFFFFFFFFF", "FFFFFFFFFFFFFFFF", "51866FD5B85ECB8A"),
            new Vector("3000000000000000", "1000000000000001", "7D856F9A613063F2"),
            new Vector("0123456789ABCDEF", "1111111111111111", "61F9C3802281B096"),
            new Vector("FEDCBA9876543210", "0123456789ABCDEF", "0ACEAB0FC6A0A28D"));

    /** Encrypts one block with the core after Blowfish's ordinary key schedule. */
    static byte[] encrypt(byte[] key, byte[] block) {
        var state = Blowfish.withKey(key);
        var buffer = ByteBuffer.wrap(block);
        int[] words = {buffer.getInt(0), buffer.getInt(4)};
        state.encrypt(words, 0);
        return ByteBuffer.allocate(8).putInt(words[0]).putInt(words[1]).array();
    }

    static void checkKnownValues(int[] p, int[] s) {
        assertEquals(18, p.length);
        assertEquals(1024, s.length);
        assertEquals(0x243F6A88, p[0]);
        assertEquals(0x8979FB1B, p[17]);
        assertEquals(0xD1310BA6, s[0]);         // S0[0]
        assertEquals(0x6E85076A, s[255]);       // S0[255]
        assertEquals(0x4B7A70E9, s[256]);       // S1[0]
        assertEquals(0xE93D5A68, s[512]);       // S2[0]
        assertEquals(0x3A39CE37, s[768]);       // S3[0]
        assertEquals(0x3AC372E6, s[1023]);      // S3[255]
    }

    static Suite tests() {
        return suite("Blowfish core",
                test("the tables derived from pi have the known values, also after hashes were computed", () -> {
                    var p = Blowfish.initialP();
                    var s = Blowfish.initialS();
                    checkKnownValues(p, s);
                    BCrypt.format('a', 4, new byte[16], "password".getBytes(UTF_8));
                    BCryptPasswordHasher.of(4).hash("password");
                    encrypt("some key".getBytes(UTF_8), new byte[8]);
                    // A caller that scribbles on its copy changes nothing either.
                    Arrays.fill(p, 0);
                    Arrays.fill(s, 0);
                    var pAfter = Blowfish.initialP();
                    var sAfter = Blowfish.initialS();
                    checkKnownValues(pAfter, sAfter);
                    assertEquals(BCrypt.format('a', 4, new byte[16], "password".getBytes(UTF_8)),
                            BCrypt.format('a', 4, new byte[16], "password".getBytes(UTF_8)));
                }),
                parameterized("Eric Young's vectors", ERIC_YOUNG, v -> assertEquals(v.cipher(),
                        Support.hex(encrypt(Support.hex(v.key()), Support.hex(v.plain()))).toUpperCase(Locale.ROOT))),
                test("the core gives the JDK's Blowfish/ECB/NoPadding for 250 random keys of 4-56 bytes and blocks", () -> {
                    long seed = System.nanoTime();
                    var random = new Random(seed);
                    var cipher = Cipher.getInstance("Blowfish/ECB/NoPadding");
                    for (int i = 0; i < 250; i++) {
                        var key = new byte[4 + random.nextInt(53)];
                        random.nextBytes(key);
                        var block = new byte[8];
                        random.nextBytes(block);
                        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "Blowfish"));
                        var expected = cipher.doFinal(block);
                        assertEquals(Support.hex(expected), Support.hex(encrypt(key, block)),
                                "seed " + seed + ", case " + i + ", key of " + key.length + " bytes");
                    }
                }),
                test("the round count does not overflow: 16 for cost 4, 2^31 for cost 31", () -> {
                    assertEquals(16L, BCrypt.rounds(4));
                    assertEquals(1024L, BCrypt.rounds(10));
                    assertEquals(2_147_483_648L, BCrypt.rounds(31));
                    assertThrows(IllegalArgumentException.class, () -> BCrypt.rounds(3));
                    assertThrows(IllegalArgumentException.class, () -> BCrypt.rounds(32));
                }),
                test("the primitive runs exactly rounds(cost) rounds, counted in a long", () -> {
                    // Cost 31 cannot be run, so this reads the code; whitespace and comments do not count.
                    var code = withoutComments(mainCode("BCrypt.java")).replaceAll("\\s+", "");
                    assertTrue(code.contains("longrounds=rounds(cost);"), "BCrypt.hash takes its count from rounds(cost)");
                    assertTrue(code.contains("for(longround=0;round<rounds;round++){"), "the loop counts in a long");
                    assertEquals(1, count(code, "round<rounds"), "one expensive loop");
                    // Costs 4 and 5 differ exactly by one doubling of the loop: their vectors pass.
                    assertEquals("$2a$04$C/CQCvOTDfaWEPmZF/ycFuUeA49K0sshzyZIXUTBmHM4.xmgxaWVq",
                            BCryptVectorTests.crypt("hemligt".getBytes(UTF_8), "$2a$04$C/CQCvOTDfaWEPmZF/ycFu"));
                }),
                test("the main code uses no Cipher, no reflection and no table of Blowfish constants", () -> {
                    // The other rules for the main code (forbidden strings, file count) are in SourceRulesTests;
                    // these are the ones that only concern the new bcrypt files (crit. 2 and 16).
                    List<Path> sources;
                    try (var files = Files.walk(SourceRulesTests.SOURCES)) {
                        sources = files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
                    }
                    var known = List.of("243f6a88", "85a308d3", "8979fb1b", "d1310ba6", "98dfb5ac", "6e85076a",
                            "4b7a70e9", "e93d5a68", "3a39ce37", "3ac372e6");
                    // Sixteen or more numbers in one array initialiser: a table.
                    var table = Pattern.compile("\\{\\s*(?:-?(?:0[xX])?[0-9a-fA-F_]+[lL]?\\s*,\\s*){15,}");
                    var hexWord = Pattern.compile("0[xX][0-9a-fA-F_]{8,}");
                    for (var file : sources) {
                        var text = Files.readString(file);
                        var code = withoutComments(text);
                        assertFalse(code.contains("Cipher"), file + " uses Cipher");
                        assertFalse(code.contains("java.lang.reflect") || code.contains("java.lang.invoke"), file + " reflects");
                        var lower = text.toLowerCase(Locale.ROOT).replace("_", "");
                        for (var value : known) {
                            assertFalse(lower.contains(value), file + " contains the Blowfish constant " + value);
                        }
                        assertFalse(table.matcher(code).find(), file + " has a table of numbers");
                        assertTrue(hexWord.matcher(code).results().count() <= 2, file + " has 32-bit hex literals");
                    }
                    var blowfish = mainCode("Blowfish.java");
                    assertTrue(blowfish.contains("BigInteger"), "pi is computed with BigInteger");
                    assertTrue(blowfish.contains("arctanOfInverse(5, one)") && blowfish.contains("arctanOfInverse(239, one)"),
                            "Machin's formula");
                }));
    }

    static String mainCode(String file) throws java.io.IOException {
        return Files.readString(SourceRulesTests.SOURCES.resolve("spola/security").resolve(file));
    }

    /** The source without its comments, as SourceRulesTests reads it. */
    static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    static int count(String text, String part) {
        int count = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            count++;
        }
        return count;
    }
}
