package spola.security.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import spola.test.Suite;

/** Criterion 12: the README example, its comparison table and its deviation rows. */
final class ReadmeTests {

    private ReadmeTests() {
    }

    static final Path README = Path.of("modules/security-web/README.md");

    static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    static List<String> tableRows(String readme) {
        var rows = new ArrayList<String>();
        for (var line : readme.split("\n")) {
            if (line.startsWith("| ")) {
                rows.add(line);
            }
        }
        return rows;
    }

    static Suite tests() {
        return suite("README",
                test("the README shows SecurityExample.java as it is compiled and tested here", () -> {
                    var readme = Files.readString(README);
                    var source = Files.readString(Path.of("modules/security-web/test/spola/security/web/SecurityExample.java"));
                    var header = "package spola.security.web;\n\n";
                    assertTrue(source.startsWith(header));
                    var block = "```java\n" + source.substring(header.length()) + "```\n";
                    assertTrue(readme.contains(block), "the README's Spola example differs from SecurityExample.java");
                }),
                test("the example works as the README says", () -> {
                    var security = SecurityExample.security(SecurityExample.authenticator(Support.HASHER));
                    try (var server = Support.serve(SecurityExample.router(security))) {
                        int port = server.port();
                        var ada = basic("ada", "correct horse battery staple");
                        var bob = basic("bob", "hunter2hunter2");
                        assertEquals("hello", Support.send(port, "GET", "/public/hello").body());
                        assertEquals(401, Support.send(port, "GET", "/me").status());
                        assertEquals("you are bob", Support.send(port, "GET", "/me", bob).body());
                        assertEquals(401, Support.send(port, "GET", "/me", basic("bob", "wrong")).status());
                        assertEquals(401, Support.send(port, "GET", "/public/hello", basic("bob", "wrong")).status());
                        assertEquals(403, Support.send(port, "GET", "/admin/users", bob).status());
                        assertEquals(403, Support.send(port, "POST", "/admin/users", bob).status());
                        assertEquals(401, Support.send(port, "POST", "/admin/users").status());
                        assertEquals("ada, bob", Support.send(port, "GET", "/admin/users", ada).body());
                        assertEquals(201, Support.send(port, "POST", "/admin/users", ada).status());
                    }
                }),
                test("the README has the Spring side, the comparison table and the deviation rows", () -> {
                    var readme = Files.readString(README);
                    for (var text : List.of("SecurityFilterChain", "authorizeHttpRequests", "httpBasic", "@PreAuthorize",
                            "@AuthenticationPrincipal", "## Spring Security och Spola", "## Avvikelser från Spring Security",
                            "Säkerhetskritisk kod", "först")) {
                        assertTrue(readme.contains(text), "the README does not mention " + text);
                    }
                    var rows = tableRows(readme);
                    for (var topic : List.of("`SecurityFilterChain`", "`authorizeHttpRequests", "`httpBasic()`",
                            "`requestMatchers", "`permitAll()`", "`hasRole", "`anyRequest()`", "`@PreAuthorize",
                            "`@AuthenticationPrincipal`", "`AuthenticationEntryPoint`", "`AccessDeniedHandler`")) {
                        assertTrue(rows.stream().anyMatch(r -> r.startsWith("| " + topic) || r.contains("| " + topic)),
                                "no table row about " + topic);
                    }
                    for (var topic : List.of("Ingen formulärinloggning", "Nekat som standard", "`GET`-regler gäller även `HEAD`",
                            "Segmentvisa prefix", "Ingen `SecurityContextHolder`", "Strikt Base64")) {
                        assertTrue(rows.stream().anyMatch(r -> r.contains(topic)), "no deviation row about " + topic);
                    }
                }));
    }
}
