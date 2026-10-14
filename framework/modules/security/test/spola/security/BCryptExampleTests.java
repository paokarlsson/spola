package spola.security;

import static java.nio.charset.StandardCharsets.UTF_8;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import spola.security.BCryptExample.Account;
import spola.test.Suite;

/** Criterion 3: the need's use case, through the README example, and the example's place in the README. */
final class BCryptExampleTests {

    private BCryptExampleTests() {
    }

    /** "hemligt" with cost 10 and the salt 0x10..0x1f, from libxcrypt: what Spring's BCryptPasswordEncoder writes. */
    static final String HEMLIGT = "$2a$10$C/CQCvOTDfaWEPmZF/ycFuvu6miZtNk3jj/4zDoWuN3Icm9TmKt9e";
    static final String HUNTER2 = "$2a$10$C/CQCvOTDfaWEPmZF/ycFuijXL7x1mYyENSqiAN9JavcgfCUlLFPC";
    static final Pattern STORED = Pattern.compile("\\{bcrypt\\}\\$2a\\$10\\$[./A-Za-z0-9]{53}");

    static byte[] salt() {
        var salt = new byte[16];
        for (int i = 0; i < 16; i++) {
            salt[i] = (byte) (0x10 + i);
        }
        return salt;
    }

    static Suite tests() {
        var hasher = BCryptExample.hasher();
        return suite("the bcrypt README example (the need's use case)",
                test("the README shows BCryptExample.java as it is compiled and tested here", () -> {
                    // The same check as ReadmeTests makes for LoginExample.java; that file is left
                    // as it is (crit. 2 allows only its deviation rows to change), so it is not shared.
                    var readme = Files.readString(ReadmeTests.README);
                    var source = Files.readString(Path.of("modules/security/test/spola/security/BCryptExample.java"));
                    var header = "package spola.security;\n\n";
                    assertTrue(source.startsWith(header));
                    var block = "```java\n" + source.substring(header.length()) + "```\n";
                    assertTrue(readme.contains(block), "the README's Spola example differs from BCryptExample.java");
                    var section = readme.substring(readme.indexOf("## BCrypt och hashar från Spring"));
                    assertTrue(section.contains(block), "the example is in the section BCrypt och hashar från Spring");
                    for (var spring : List.of("PasswordEncoderFactories.createDelegatingPasswordEncoder()",
                            "new DelegatingPasswordEncoder(\"bcrypt\", Map.of(", "new BCryptPasswordEncoder()")) {
                        assertTrue(section.contains(spring), "the Spring side has " + spring);
                    }
                }),
                test("a hash from Spring verifies with and without the {bcrypt} prefix", () -> {
                    assertTrue(BCryptExample.check(hasher, "hemligt", "{bcrypt}" + HEMLIGT));
                    assertTrue(hasher.matches("hemligt", "{bcrypt}" + HEMLIGT));
                    assertFalse(hasher.matches("hemligT", "{bcrypt}" + HEMLIGT));
                    assertFalse(BCryptExample.check(hasher, "hemligT", "{bcrypt}" + HEMLIGT));
                    assertTrue(BCryptPasswordHasher.defaults().matches("hemligt", HEMLIGT));
                    assertFalse(BCryptPasswordHasher.defaults().matches("hemligT", HEMLIGT));
                }),
                test("a new hash is {bcrypt}$2a$10$, matches Spring's pattern, has a canonical salt and verifies", () -> {
                    var stored = BCryptExample.hashPassword(hasher, "hemligt");
                    assertTrue(STORED.matcher(stored).matches(), "format");
                    var bare = stored.substring("{bcrypt}".length());
                    assertTrue(BCryptHasherTests.SPRING_PATTERN.matcher(bare).matches(), "Spring's BCRYPT_PATTERN");
                    assertTrue(BCryptHasherTests.canonicalSalt(bare), "canonical salt");
                    assertTrue(hasher.matches("hemligt", stored));
                    assertTrue(BCryptPasswordHasher.defaults().matches("hemligt", bare));
                    assertFalse(hasher.matches("hemligT", stored));
                }),
                test("the package-private path gives exactly Spring's string for \"hemligt\", cost 10 and the salt 0x10..0x1f", () -> {
                    assertEquals(HEMLIGT, BCrypt.format('a', 10, salt(), "hemligt".getBytes(UTF_8)));
                    assertEquals(HUNTER2, BCrypt.format('a', 10, salt(), "hunter2hunter2".getBytes(UTF_8)));
                    assertEquals("C/CQCvOTDfaWEPmZF/ycFu", BCrypt.toBase64(salt()));
                }),
                test("the example's authenticator: Authenticated with USER for the right password, BAD_CREDENTIALS otherwise", () -> {
                    var authenticator = BCryptExample.authenticator(hasher, List.of(
                            new Account("ada", "{bcrypt}" + HEMLIGT),
                            new Account("bob", "{bcrypt}" + HUNTER2)));
                    var bad = new AuthResult.Failed(Failure.BAD_CREDENTIALS);
                    assertEquals(new AuthResult.Authenticated(new Identity("ada", Set.of("USER"))),
                            authenticator.authenticate("ada", "hemligt"));
                    assertEquals(new AuthResult.Authenticated(new Identity("bob", Set.of("USER"))),
                            authenticator.authenticate("bob", "hunter2hunter2"));
                    assertEquals(bad, authenticator.authenticate("ada", "hemligT"));
                    assertEquals(bad, authenticator.authenticate("ada", "hunter2hunter2"));
                    assertEquals(bad, authenticator.authenticate("bob", "hemligt"));
                    assertEquals(bad, authenticator.authenticate("eve", "hemligt"));
                }));
    }
}
