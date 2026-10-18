package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import spola.test.Suite;
import spola.web.ForwardedSupport.Case;

/**
 * Criteria 3 and 13: the need's code ({@link WhoamiApp}) through a real {@code HttpServer} and
 * {@code HttpClient}, and the README's section "Bakom en omvänd proxy" with its table rows.
 */
final class ForwardedReadmeTests {

    private ForwardedReadmeTests() {
    }

    static String readme() throws Exception {
        return Files.readString(Path.of("modules/web/README.md"));
    }

    /** The section from its heading to the next {@code ## } heading. */
    static String section() throws Exception {
        var text = readme();
        int start = text.indexOf("\n## Bakom en omvänd proxy\n");
        assertTrue(start > 0, "the README has a section Bakom en omvänd proxy");
        int end = text.indexOf("\n## ", start + 1);
        return text.substring(start, end);
    }

    static final Case CADDY = new Case("Caddy's headers", "X-Forwarded-Proto: https", "X-Forwarded-For: 203.0.113.7");

    static Suite tests() {
        return suite("README and the need's code",
                test("behind a trusted proxy: https 203.0.113.7; without the headers: http 127.0.0.1", () -> {
                    try (var server = Support.serve(WhoamiApp.router(TrustedProxies.privateNetworks()));
                            var client = Support.client()) {
                        var behind = ForwardedSupport.call(client, server, "/api/whoami", CADDY);
                        assertEquals(200, behind.statusCode());
                        assertEquals("https 203.0.113.7", behind.body());
                        var direct = ForwardedSupport.call(client, server, "/api/whoami", new Case("none", new String[0]));
                        assertEquals("http 127.0.0.1", direct.body());
                    }
                }),
                test("the same router trusting only 10.0.0.0/8: forged and invalid headers from 127.0.0.1 are"
                        + " ignored, never a 400", () -> {
                    var cases = ForwardedSupport.all(List.of(CADDY), ForwardedSupport.INVALID_FOR,
                            ForwardedSupport.INVALID_PROTO);
                    int raw = 0;
                    try (var server = Support.serve(WhoamiApp.router(ForwardedSupport.NOT_TRUSTING));
                            var client = Support.client()) {
                        for (var header : cases) {
                            if (header.ascii()) {
                                var response = ForwardedSupport.call(client, server, "/api/whoami", header);
                                assertEquals(200, response.statusCode(), header.toString());
                                assertEquals("http 127.0.0.1", response.body(), header.toString());
                            } else { // HttpClient sends header values as ASCII: obs-text goes over a raw socket
                                raw++;
                                var reply = ForwardedSupport.send(server, "/api/whoami", header);
                                assertEquals(200, reply.status(), header.toString());
                                assertEquals("http 127.0.0.1", reply.body(), header.toString());
                            }
                        }
                    }
                    assertEquals(4, raw, "the cases with bytes outside ASCII");
                }),
                test("the README shows WhoamiApp.java as it is compiled and tested here", () -> {
                    var source = Files.readString(Path.of("modules/web/test/spola/web/WhoamiApp.java"));
                    var header = "package spola.web;\n\n";
                    assertTrue(source.startsWith(header));
                    var block = "### Spola\n\n```java\n" + source.substring(header.length()) + "```\n";
                    assertTrue(section().contains(block), "the README's Spola example differs from WhoamiApp.java");
                }),
                test("15b: the Docker example listens on 0.0.0.0 port 8080, where Caddy in another container reaches it;"
                        + " the default listens on loopback", () -> {
                    var options = WhoamiApp.options();
                    assertTrue(options.address().isAnyLocalAddress(), "listens on " + options.address());
                    assertEquals("0.0.0.0", options.address().getHostAddress());
                    assertEquals(8080, options.port());
                    assertEquals(java.util.Optional.empty(), options.tls());
                    assertTrue(spola.http.HttpServerOptions.DEFAULT.address().isLoopbackAddress());
                    var source = Files.readString(Path.of("modules/web/test/spola/web/WhoamiApp.java"));
                    assertTrue(source.contains("HttpServer.start(options(), router)"), "main starts with options()");
                    var section = section();
                    for (var text : List.of("**Appen lyssnar på `0.0.0.0` i Docker.**", "`HttpServerOptions.DEFAULT` lyssnar bara på loopback",
                            "Caddy i en annan container")) {
                        assertTrue(section.contains(text), "the section lacks " + text);
                    }
                }),
                test("15b: the section has the warning as the filter writes it, Secure that does not depend on the"
                        + " proxy's address, and that a private client address is no authorization", () -> {
                    var section = section();
                    int at = section.indexOf("`X-Forwarded-Proto or X-Forwarded-For from 172.18.0.6 ignored:");
                    assertTrue(at > 0, "the section shows the warning");
                    var shown = section.substring(at + 1, section.indexOf(", ...`", at)).replaceAll("\\s+", " ");
                    var actual = ForwardedHeaders.warning(java.net.InetAddress.ofLiteral("172.18.0.6"));
                    assertTrue(actual.startsWith(shown), "the README's warning differs from " + actual);
                    for (var text : List.of("**En varning, en gång, när avsändaren inte är betrodd.**", "(loggern `spola.web`)",
                            "aldrig huvudenas värden", "en gång per filter, inte per förfrågan",
                            "**En app\n  som bara nås över https** sätter därför `secure(true)` på båda cookierna",
                            "`__Host-SESSION`", "**En privat klientadress är ingen behörighet.**",
                            "Släpp därför aldrig in en förfrågan bara för\n  att klientadressen är privat")) {
                        assertTrue(section.contains(text), "the section lacks " + text);
                    }
                    for (var old : List.of("och cookierna får `Secure` utan `secure(true)`", "huvudena läses inte alls")) {
                        assertFalse(section.contains(old), "the section still says " + old);
                    }
                }),
                test("the Spring Boot code comes first: forward-headers-strategy, getScheme() and getRemoteAddr()", () -> {
                    var section = section();
                    int spring = section.indexOf("### Spring Boot");
                    int spola = section.indexOf("### Spola");
                    assertTrue(spring > 0 && spring < spola, "Spring Boot before Spola");
                    var springCode = section.substring(spring, spola);
                    for (var text : List.of("server.forward-headers-strategy=native", "request.getScheme()",
                            "request.getRemoteAddr()")) {
                        assertTrue(springCode.contains(text), "the Spring Boot code lacks " + text);
                    }
                }),
                test("the section: register first, publish only the proxy's port, Docker's gateway, trust the proxy's"
                        + " address", () -> {
                    var section = section();
                    for (var text : List.of("**Registrera `ForwardedHeaders` först**", "**Bara proxyns port ska publiceras.**",
                            "**`privateNetworks()` litar på hela nätet**", "Dockers\n  userland-proxy",
                            "Docker Desktop", "`172.17.0.1`", "Den adressen är betrodd och kan förfalska huvudena.",
                            "Lita då bara på proxyns adress med `TrustedProxies.of(...)`",
                            "**`request.http()` beskriver anslutningen.**", "**`X-Forwarded-For` läses från höger.**",
                            "**400 för ogiltiga värden från en betrodd proxy**", "**Strikta IP-adresser.**",
                            "**Bara från en betrodd avsändare.**")) {
                        assertTrue(section.contains(text), "the section lacks " + text);
                    }
                }),
                test("the comparison table and the deviation table have the new rows", () -> {
                    var text = readme();
                    for (var row : List.of(
                            "| `server.forward-headers-strategy=native` (Tomcats `RemoteIpValve`) |"
                                    + " `.filter(ForwardedHeaders.trusting(TrustedProxies.privateNetworks()))`",
                            "| `request.getScheme()`, `request.isSecure()` | `request.scheme()`",
                            "| `request.getRemoteAddr()` | `request.clientAddress()` (`InetAddress`)",
                            "| `server.tomcat.remoteip.internal-proxies` | `TrustedProxies.of(\"172.18.0.5\")`",
                            "| Proxyhuvuden som läses |",
                            "`Forwarded` (RFC 7239), `X-Forwarded-Host`, `X-Forwarded-Port` och `X-Forwarded-Prefix`"
                                    + " stöds inte",
                            "| Ogiltiga värden från en betrodd proxy |", "| IP-adresser i `X-Forwarded-For` |",
                            "Strikta IP-literaler", "| Betrodda proxyer som standard |", "`100.64.0.0/10`",
                            "| `Secure` på cookies efter schemat bakom en proxy |",
                            "måste registreras före `sessions.filter()` och `security.filter()`")) {
                        assertTrue(text.contains(row), "missing " + row);
                    }
                }));
    }
}
