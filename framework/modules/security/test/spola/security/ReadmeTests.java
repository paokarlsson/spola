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

/** Criterion 10: the README example, its comparison table and its deviation rows. */
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
                    for (var topic : List.of("Bara PBKDF2-SHA256", "PHC-format", "`{pbkdf2}`", "`DISABLED` bara efter rätt lösenord",
                            "undantag i stället för `false`", "Ingen normalisering")) {
                        assertTrue(rows.stream().anyMatch(r -> r.contains(topic)), "no table row about " + topic);
                    }
                }));
    }
}
