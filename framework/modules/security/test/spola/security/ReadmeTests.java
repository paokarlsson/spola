package spola.security;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import spola.test.Suite;

/** Criterion 10: the README example, its comparison table and its deviation rows; and what 15c changed. */
final class ReadmeTests {

    private ReadmeTests() {
    }

    static final Path README = Path.of("modules/security/README.md");

    static Suite tests() {
        return suite("README",
                test("the README shows LoginExample.java as it is compiled and tested here", () -> {
                    var readme = Files.readString(README);
                    var source = Files.readString(Path.of("modules/security/test/spola/security/LoginExample.java"));
                    var header = "package spola.security;\n\n";
                    assertTrue(source.startsWith(header));
                    var block = "```java\n" + source.substring(header.length()) + "```\n";
                    assertTrue(readme.contains(block), "the README's Spola example differs from LoginExample.java");
                }),
                test("the example works as the README says", () -> {
                    var users = new InMemoryUserStore();
                    var authenticator = LoginExample.authenticator(Support.fast(), users);
                    assertEquals("welcome, administrator ada",
                            LoginExample.login(authenticator, "ada", "correct horse battery staple"));
                    assertEquals("wrong username or password", LoginExample.login(authenticator, "ada", "wrong"));
                    assertEquals("wrong username or password", LoginExample.login(authenticator, "eve", "wrong"));
                    assertEquals("your account is disabled", LoginExample.login(authenticator, "bob", "hunter2hunter2"));
                    assertEquals("wrong username or password", LoginExample.login(authenticator, "bob", "wrong"));
                    users.add(new User("carl", Pbkdf2PasswordHasher.of(20_000, 16, 32).hash("pw-carl"), Set.of("USER"), true));
                    assertEquals("welcome, carl", LoginExample.login(authenticator, "carl", "pw-carl"));
                    assertFalse(Support.fast().needsRehash(users.find("carl").orElseThrow().passwordHash()));
                }),
                test("the README shows the SQL of the compare-and-set and says what 15c changed", () -> {
                    var readme = Files.readString(README).replaceAll("\\s+", " ");
                    for (var text : List.of(
                            "UPDATE users SET hash = ? WHERE username = ? AND hash = ? -- newHash, username, verifiedHash",
                            "Lika tid för okända användare gäller bara inom en algoritm och kostnad.",
                            "tills användarna har loggat in en gång",
                            "En lagrad hash som inte går att tolka ger `BAD_CREDENTIALS`.",
                            "med bara undantagets klassnamn",
                            "`upgrader()` för uppgradering som compare-and-set på den verifierade hashen",
                            "med högst 32 byte hash")) {
                        assertTrue(readme.contains(text), "the README does not say: " + text);
                    }
                    assertFalse(readme.contains("upgrader(hasher)"), "the old signature is gone");
                }),
                test("the README has the Spring side, the comparison table and the deviation rows", () -> {
                    var readme = Files.readString(README);
                    for (var text : List.of("PasswordEncoder", "Pbkdf2PasswordEncoder", "InMemoryUserDetailsManager",
                            "DaoAuthenticationProvider", "UserDetailsPasswordService", "## Spring Security och Spola",
                            "## Avvikelser från Spring Security")) {
                        assertTrue(readme.contains(text), "the README does not mention " + text);
                    }
                    var rows = new ArrayList<String>();
                    for (var line : readme.split("\n")) {
                        if (line.startsWith("| ")) {
                            rows.add(line);
                        }
                    }
                    for (var topic : List.of("Ingen SCrypt, Argon2", "PHC-format", "`{pbkdf2}`", "`DISABLED` bara efter rätt lösenord",
                            "undantag i stället för `false`", "Ingen normalisering", "72 byte", "U+0000", "kanonisk Base64",
                            "`needsRehash` bara vid lägre kostnad", "Alltid `$2a$`", "Exakta id", "ingen reservhasher",
                            "`{pbkdf2}` är Spolas format")) {
                        assertTrue(rows.stream().anyMatch(r -> r.contains(topic)), "no table row about " + topic);
                    }
                    assertFalse(rows.stream().anyMatch(r -> r.contains("Bara PBKDF2-SHA256")), "the old row is replaced");
                    // 15c: the compare-and-set, the 32-byte limit and the malformed-hash answer have rows.
                    for (var topic : List.of("compare-and-set på den verifierade hashen", "högst 32 byte",
                            "svarar `BAD_CREDENTIALS`", "`PasswordUpgrade.upgrade(username, verifiedHash, newHash)`",
                            "lika tid bara inom en algoritm och kostnad")) {
                        assertTrue(rows.stream().anyMatch(r -> r.contains(topic)), "no table row about " + topic);
                    }
                    // New comparison rows: the Spring cell starts with the first text, the row has the second.
                    for (var pair : List.of(List.of("`BCryptPasswordEncoder", "`BCryptPasswordHasher"),
                            List.of("`DelegatingPasswordEncoder", "`PasswordHasher.delegating"),
                            List.of("`upgradeEncoding`", "`needsRehash`"))) {
                        assertTrue(rows.stream().anyMatch(r -> r.startsWith("| " + pair.get(0)) && r.contains(pair.get(1))),
                                "no comparison row " + pair);
                    }
                }));
    }
}
