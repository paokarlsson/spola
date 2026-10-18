package spola.security.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import spola.test.Suite;

/**
 * Uppdrag 15a: the READMEs document the limit per user, the new session for another user and the
 * {@code Cache-Control} rules, and the table in "Cachar" says what {@link CacheHeaderTests} shows.
 */
final class UserSessionReadmeTests {

    private UserSessionReadmeTests() {
    }

    static String readme() throws Exception {
        return Files.readString(Path.of("modules/security-web/README.md"));
    }

    /** The part from {@code ### Cachar} to the next heading. */
    static String caches() throws Exception {
        var text = readme();
        int start = text.indexOf("\n### Cachar\n");
        assertTrue(start > 0, "the README has a part Cachar");
        int end = text.indexOf("\n#", start + 1);
        return text.substring(start, end);
    }

    /** {@code `a`} to {@code a}, and {@code inget} to no field. */
    static List<String> fields(String cell) {
        var value = cell.strip();
        return value.equals("inget") ? List.of() : List.of(value.substring(1, value.length() - 1));
    }

    static Suite tests() {
        return suite("README",
                test("the login section and the CSRF section describe the limit, the new session and the caches", () -> {
                    var text = readme();
                    for (var part : List.of(
                            "**Högst `maximumSessions` sessioner per användare** (standard 10)",
                            "**En annan användare får en ny session.**",
                            "**Inloggade svar sparas inte i en delad cache.**",
                            ".maximumSessions(3) // a fourth login ends the user's least recently used session",
                            "| `sessionManagement(s -> s.maximumSessions(3))` | `WebSecurity.builder().maximumSessions(3)`;"
                                    + " standard 10 |",
                            "| Högst 10 sessioner per användare som standard |",
                            "| En avslutad session tas bort direkt |",
                            "| Ny, tom session när en annan användare loggar in |",
                            "| `Cache-Control` när cookien sätts |",
                            "| `Cache-Control` på inloggade svar |",
                            "- Ett svar som sätter `XSRF-TOKEN` kan aldrig sparas av en delad cache")) {
                        assertTrue(text.contains(part), "the README lacks " + part);
                    }
                    assertFalse(text.contains("(`Sessions.renew`, skydd mot session fixation)"),
                            "login uses renewFor now");
                    var web = Files.readString(Path.of("modules/web/README.md"));
                    for (var part : List.of("- **Sessioner per användare.** `Sessions.renewFor(request, name, n)`",
                            "| Sessioner per användare |", "- **`Cache-Control`.** Ett svar som sätter eller tar bort",
                            "`public, max-age=60`, `s-maxage=60` och `max-age=60` ersätts. |",
                            "en förfrågan som kommer medan en annan städar får 503 direkt")) {
                        assertTrue(web.contains(part), "the web README lacks " + part);
                    }
                    assertFalse(web.contains("får `Cache-Control: no-store` om det saknar `Cache-Control`."),
                            "the web README still has the old rule");
                }),
                test("the table in Cachar says what the tests show", () -> {
                    var rows = new ArrayList<String[]>();
                    for (var line : caches().lines().toList()) {
                        if (line.startsWith("| ") && !line.startsWith("| Handlern satte")) {
                            assertTrue(line.endsWith(" |"), "a row that does not end the table cell: " + line);
                            var cells = line.substring(2, line.length() - 2).split(" \\| ");
                            assertEquals(4, cells.length, line);
                            rows.add(cells);
                        }
                    }
                    assertEquals(7, rows.size());
                    assertTrue(caches().strip().endsWith("| `no-store` | `no-store` | `no-store` | `no-store` |"),
                            "the part ends with the table");
                    for (var row : rows) {
                        var handler = fields(row[0]);
                        var found = CacheHeaderTests.CASES.stream().filter(c -> c.handler().equals(handler)).findFirst();
                        assertTrue(found.isPresent(), "no test for " + row[0]);
                        var c = found.get();
                        assertEquals(c.withCookie(), fields(row[1]), row[0] + " with a cookie");
                        assertEquals(c.loggedIn(), fields(row[2]), row[0] + " logged in");
                        assertEquals(c.handler(), fields(row[3]), row[0] + " anonymous");
                    }
                }));
    }
}
