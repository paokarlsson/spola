package spola.security.web;

import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.file.Files;
import java.util.List;
import spola.test.Suite;

/**
 * 13b in the README: the section "CSRF-skydd för en SPA" shows how the application runs behind
 * Caddy ({@code ForwardedHeaders} first, both cookies {@code Secure} without
 * {@code secure(true)}), says when the cookie gets {@code Secure}, and has the new comparison
 * and deviation rows. The router it shows is the one {@link SchemeFlowTests} runs.
 */
final class SchemeReadmeTests {

    private SchemeReadmeTests() {
    }

    static Suite tests() {
        return suite("README Secure from the scheme (13b)",
                test("the CSRF section shows the application behind Caddy: ForwardedHeaders first, no secure(true),"
                        + " both cookies Secure", () -> {
                    var section = CsrfReadmeTests.section();
                    int caddy = section.indexOf("\n### Bakom Caddy\n");
                    assertTrue(caddy > section.indexOf("### Spola\n"), "a part Bakom Caddy after the example");
                    var part = section.substring(caddy, section.indexOf("\n### ", caddy + 1));
                    for (var text : List.of(
                            "var sessions = CsrfExample.sessions(false, InstantSource.system()); // no secure(true)",
                            "Router.builder().filter(ForwardedHeaders.trusting(TrustedProxies.privateNetworks())), // first",
                            "CsrfExample.security(sessions)",
                            "Set-Cookie: XSRF-TOKEN=<token>; Path=/; Secure; SameSite=Lax",
                            "Set-Cookie: SESSION=<id>; Path=/; Secure; HttpOnly; SameSite=Lax",
                            "Set-Cookie: SESSION=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax",
                            "båda cookierna blir `Secure` utan `secure(true)`", "**Ordningen.**",
                            "**Bara från en betrodd proxy.**", "`Responses.withCookie`")) {
                        assertTrue(part.contains(text), "the part Bakom Caddy lacks " + text);
                    }
                }),
                test("the CSRF section says when XSRF-TOKEN gets Secure, and no longer tells to write secure(true)"
                        + " behind a TLS proxy", () -> {
                    var section = CsrfReadmeTests.section();
                    assertTrue(section.contains("- Cookien får `Secure`, som i Spring, när förfrågans schema är `https`:"));
                    assertTrue(section.contains("Regeln går inte att stänga av."));
                    for (var old : List.of("Bakom en TLS-proxy skrivs", "Bakom en TLS-proxy blir raden", "och utan `Secure`")) {
                        assertFalse(section.contains(old), "the section still says " + old);
                    }
                }),
                test("the comparison and deviation rows", () -> {
                    var rows = ReadmeTests.tableRows(CsrfReadmeTests.section());
                    for (var row : List.of(
                            "| `CookieCsrfTokenRepository` sätter `secure` efter `request.isSecure()` |",
                            "| `server.forward-headers-strategy=native` bakom en TLS-proxy |",
                            "| `Secure` efter schemat kan inte stängas av |",
                            "| `ForwardedHeaders` före säkerhetsfiltret |")) {
                        assertTrue(rows.stream().anyMatch(r -> r.startsWith(row)), "no row " + row);
                    }
                }),
                test("the introduction: security.filter() first, behind a proxy only ForwardedHeaders before it", () -> {
                    var readme = Files.readString(ReadmeTests.README);
                    var head = readme.substring(0, readme.indexOf("\n## "));
                    assertTrue(head.contains("Bakom en omvänd proxy kommer bara `ForwardedHeaders` före det"));
                    assertTrue(head.contains("Sessions- och CSRF-cookien får `Secure` när förfrågans schema är `https`"));
                }));
    }
}
