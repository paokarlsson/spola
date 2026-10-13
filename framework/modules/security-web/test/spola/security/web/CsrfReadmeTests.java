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
 * Criterion 10: the README's section "CSRF-skydd för en SPA" shows {@link CsrfExample} as it is
 * compiled and tested here, next to the need's Spring code and Angular's configuration, with the
 * deviation rows. The example itself runs in {@link CsrfFlowTests} and {@link CsrfConcurrencyTests}.
 */
final class CsrfReadmeTests {

    private CsrfReadmeTests() {
    }

    static final String HEADING = "\n## CSRF-skydd för en SPA\n";

    /** The section from its heading to the next {@code ## } heading, or to the end. */
    static String section() throws Exception {
        var readme = Files.readString(ReadmeTests.README);
        int start = readme.indexOf(HEADING);
        assertTrue(start >= 0, "the README has a section CSRF-skydd för en SPA");
        int end = readme.indexOf("\n## ", start + HEADING.length());
        return readme.substring(start, end < 0 ? readme.length() : end);
    }

    static Suite tests() {
        return suite("README CSRF protection",
                test("the section shows CsrfExample.java as it is compiled and tested here", () -> {
                    var source = Files.readString(Path.of("modules/security-web/test/spola/security/web/CsrfExample.java"));
                    var header = "package spola.security.web;\n\n";
                    assertTrue(source.startsWith(header));
                    assertTrue(source.contains(".csrf(Csrf.cookie())"), "the example turns the protection on");
                    var block = "### Spola\n\n```java\n" + source.substring(header.length()) + "```\n";
                    assertTrue(section().contains(block), "the README's Spola CSRF example differs from CsrfExample.java");
                }),
                test("the need's Spring code: csrf(...) with CookieCsrfTokenRepository.withHttpOnlyFalse() and"
                        + " CsrfTokenRequestAttributeHandler, and CsrfCookieFilter", () -> {
                    var spring = LoginReadmeTests.between(section(), "### Spring Security\n", "### Spola\n");
                    for (var text : List.of(".csrf(csrf -> csrf", "CookieCsrfTokenRepository.withHttpOnlyFalse()",
                            "new CsrfTokenRequestAttributeHandler()", ".addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)",
                            "class CsrfCookieFilter extends OncePerRequestFilter", "\"/api/auth/login\"",
                            "SessionCreationPolicy.IF_REQUIRED")) {
                        assertTrue(spring.contains(text), "the Spring code lacks " + text);
                    }
                }),
                test("Angular's withXsrfConfiguration", () -> {
                    var angular = LoginReadmeTests.between(section(), "### Angular\n", "### Spring Security\n");
                    assertTrue(angular.contains("withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' })"),
                            "the Angular configuration");
                    assertTrue(angular.contains("provideHttpClient("));
                }),
                test("the deviation rows", () -> {
                    var section = section();
                    var deviations = ReadmeTests.tableRows(section.substring(section.indexOf("### Avvikelser")));
                    for (var row : List.of(
                            List.of("| Fasta namn |", "XSRF-TOKEN", "X-XSRF-TOKEN"),
                            List.of("| Ingen `_csrf`-formulärparameter |"),
                            List.of("| Ingen maskering av token |", "XorCsrfTokenRequestAttributeHandler"),
                            List.of("| Inga undantag per väg |", "ignoringRequestMatchers"),
                            List.of("| Ingen token i sessionen |", "HttpSessionCsrfTokenRepository"),
                            List.of("| Kontrollen före autentiseringen |"),
                            List.of("| Cookien sätts på första svaret utan ett eget filter |", "CsrfCookieFilter"))) {
                        var found = deviations.stream().filter(r -> r.startsWith(row.getFirst())).findFirst();
                        assertTrue(found.isPresent(), "no deviation row " + row.getFirst());
                        for (var text : row.subList(1, row.size())) {
                            assertTrue(found.get().contains(text), row.getFirst() + " does not mention " + text);
                        }
                    }
                }),
                test("the rest of the README knows about the CSRF protection", () -> {
                    var readme = Files.readString(ReadmeTests.README);
                    var head = readme.substring(0, readme.indexOf("\n## "));
                    assertTrue(head.contains("csrf(Csrf.cookie())"), "the introduction lacks csrf(Csrf.cookie())");
                    for (var old : List.of("kommer i uppdrag 11c", "Inget CSRF-skydd ännu")) {
                        assertFalse(readme.contains(old), "the README still says " + old);
                    }
                }));
    }
}
