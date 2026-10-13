package spola.security.web;

import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import spola.test.Suite;

/**
 * Criterion 10: the README's section "Inloggning med session" shows {@link LoginExample} as it is
 * compiled and tested here, next to the Spring code, with the comparison rows and the deviation
 * rows. The example itself runs in {@link LoginFlowTests} and the other tests of the login.
 */
final class LoginReadmeTests {

    private LoginReadmeTests() {
    }

    static final String HEADING = "\n## Inloggning med session\n";

    /** The section from its heading to the next {@code ## } heading, or to the end. */
    static String section() throws Exception {
        var readme = Files.readString(ReadmeTests.README);
        int start = readme.indexOf(HEADING);
        assertTrue(start >= 0, "the README has a section Inloggning med session");
        int end = readme.indexOf("\n## ", start + HEADING.length());
        return readme.substring(start, end < 0 ? readme.length() : end);
    }

    /** The part of {@code text} from {@code from} to {@code to}. */
    static String between(String text, String from, String to) {
        int start = text.indexOf(from);
        int end = text.indexOf(to, start + 1);
        assertTrue(start >= 0 && end > start, "the section has " + from + " before " + to);
        return text.substring(start, end);
    }

    static Suite tests() {
        return suite("README login with a session",
                test("the section shows LoginExample.java as it is compiled and tested here", () -> {
                    var source = Files.readString(Path.of("modules/security-web/test/spola/security/web/LoginExample.java"));
                    var header = "package spola.security.web;\n\n";
                    assertTrue(source.startsWith(header));
                    var block = "### Spola\n\n```java\n" + source.substring(header.length()) + "```\n";
                    assertTrue(section().contains(block), "the README's Spola login example differs from LoginExample.java");
                }),
                test("the Spring code comes first: SecurityConfig with sessionManagement, HttpStatusEntryPoint, formLogin"
                        + " and httpBasic disabled, and an AuthController with changeSessionId and invalidate", () -> {
                    var spring = between(section(), "### Spring Security\n", "### Spola\n");
                    for (var text : List.of("class SecurityConfig", "sessionManagement(", "SessionCreationPolicy.IF_REQUIRED",
                            "new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)", ".formLogin(f -> f.disable())",
                            "formLogin().disable()", ".httpBasic(b -> b.disable())", "httpBasic().disable()",
                            "class AuthController", "request.changeSessionId()", "session.invalidate()",
                            "HttpSessionSecurityContextRepository", "\"/api/auth/login\"", "\"/actuator/health\"")) {
                        assertTrue(spring.contains(text), "the Spring code lacks " + text);
                    }
                }),
                test("the comparison rows and the deviation rows", () -> {
                    var section = section();
                    var comparison = ReadmeTests.tableRows(between(section, "### Spring Security och Spola",
                            "### Avvikelser"));
                    for (var topic : List.of("`HttpSessionSecurityContextRepository`", "`request.changeSessionId()`",
                            "`session.invalidate()`", "`HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)`",
                            "`sessionManagement(")) {
                        assertTrue(comparison.stream().anyMatch(row -> row.startsWith("| " + topic) || row.contains(" " + topic)),
                                "no comparison row about " + topic);
                    }
                    var deviations = ReadmeTests.tableRows(section.substring(section.indexOf("### Avvikelser")));
                    for (var topic : List.of("| Ingen `formLogin()` |", "| Ingen `unauthenticated(...)`",
                            "| Identiteten i sessionen är en ögonblicksbild |", "| Sessionen kommer före Basic |",
                            "| `login` ändrar inte förfrågans egen identitet |", "| Bara `security.filter()` registreras |")) {
                        assertTrue(deviations.stream().anyMatch(row -> row.startsWith(topic)), "no deviation row " + topic);
                    }
                    var formLogin = deviations.stream().filter(row -> row.startsWith("| Ingen `formLogin()` |")).findFirst()
                            .orElseThrow();
                    assertTrue(formLogin.contains("handler"), "no formLogin() because the login is a handler");
                }),
                test("the rest of the README knows about the sessions", () -> {
                    var readme = Files.readString(ReadmeTests.README);
                    var head = readme.substring(0, readme.indexOf("\n## "));
                    for (var text : List.of("WebSecurity.builder().sessions(sessions)", "WebSecurity.login(request, identity)",
                            "WebSecurity.logout(request)")) {
                        assertTrue(head.contains(text), "the introduction lacks " + text);
                    }
                    assertFalse(readme.contains("Ingen formulärinloggning eller utloggning ännu"),
                            "the old deviation row says that logout is missing");
                }));
    }
}
