package spola.security.web;

import static spola.security.web.LoginSupport.ANNA;
import static spola.security.web.LoginSupport.ANNA_PASSWORD;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import spola.http.HttpServerOptions;
import spola.test.Suite;
import spola.web.SessionOptions;
import spola.web.Sessions;
import spola.web.TrustedProxies;

/**
 * 15b, P1 and P3: the production setting of the README part "Bakom Caddy" ({@link CaddyExample}):
 * {@code __Host-SESSION} with {@code secure(true)} and {@code XSRF-TOKEN} with
 * {@code secure(true)}. The flow of {@link SchemeFlowTests} through a real server over plain
 * {@code http} gives every cookie {@code Secure}, whether the request comes directly, from a
 * sender that {@code ForwardedHeaders} does not trust (Caddy's address changed) or from a trusted
 * proxy with {@code X-Forwarded-Proto: http} or {@code https}. The README shows the example as it
 * is compiled here.
 */
final class CaddyExampleTests {

    private CaddyExampleTests() {
    }

    /** {@code XSRF-TOKEN} with {@code Secure}, as on every response of the example. */
    static final String XSRF = "XSRF-TOKEN=%s; Path=/; Secure; SameSite=Lax";
    /** {@code __Host-SESSION} with {@code Secure}. */
    static final String SESSION = "__Host-SESSION=%s; Path=/; Secure; HttpOnly; SameSite=Lax";
    /** The removal of {@code __Host-SESSION}. */
    static final String REMOVAL = "__Host-SESSION=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax";

    /** The flow with {@code Secure} on every cookie and the session cookie {@code __Host-SESSION}. */
    static final SchemeFlowTests.Flow PRODUCTION = new SchemeFlowTests.Flow(List.of(XSRF), sorted(SESSION, XSRF),
            List.of(), sorted(REMOVAL, XSRF), List.of());

    static List<String> sorted(String... lines) {
        return Stream.of(lines).sorted().toList();
    }

    /** The flow of {@link CaddyExample#router} with {@code proxies}, over plain {@code http}, with {@code headers} on every request. */
    static SchemeFlowTests.Flow flow(TrustedProxies proxies, List<String> headers) throws Exception {
        Sessions sessions = CaddyExample.sessions(InstantSource.system());
        try (var server = Support.serve(CaddyExample.router(proxies, sessions, LoginSupport.authenticator()));
                var client = client()) {
            return SchemeFlowTests.flow(client, "http://127.0.0.1:" + server.port(), sessions, headers, "__Host-SESSION");
        }
    }

    static HttpClient client() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** The part "Bakom Caddy" of the CSRF section, from its heading to the next {@code ###} heading. */
    static String part() throws Exception {
        var section = CsrfReadmeTests.section();
        int caddy = section.indexOf("\n### Bakom Caddy\n");
        assertTrue(caddy > section.indexOf("### Spola\n"), "a part Bakom Caddy after the example");
        return section.substring(caddy, section.indexOf("\n### ", caddy + 1));
    }

    static Suite tests() {
        return suite("the production setting behind Caddy (15b)",
                test("directly over http, without a proxy: __Host-SESSION and XSRF-TOKEN with Secure on every"
                        + " response", () -> {
                    assertEquals(PRODUCTION, flow(TrustedProxies.of("172.18.0.5"), List.of()));
                }),
                test("X-Forwarded-Proto: https from a sender ForwardedHeaders does not trust (Caddy got a new"
                        + " address): still Secure", () -> {
                    assertEquals(PRODUCTION, flow(TrustedProxies.of("172.18.0.5"), SchemeFlowTests.CADDY));
                }),
                test("from a trusted proxy with X-Forwarded-Proto: https, and with http: Secure either way", () -> {
                    assertEquals(PRODUCTION, flow(TrustedProxies.of("127.0.0.1"), SchemeFlowTests.CADDY));
                    assertEquals(PRODUCTION, flow(TrustedProxies.of("127.0.0.1"),
                            List.of("X-Forwarded-Proto: http", "X-Forwarded-For: 203.0.113.7")));
                }),
                test("the lines are exactly those of CsrfExample behind a trusted Caddy with https, but for the"
                        + " session cookie's name", () -> {
                    var plainName = SchemeFlowTests.behind(TrustedProxies.privateNetworks(), SchemeFlowTests.CADDY);
                    assertEquals(SchemeFlowTests.SECURE, plainName);
                    assertEquals(PRODUCTION.health(), plainName.health());
                    assertEquals(PRODUCTION.login(), plainName.login().stream()
                            .map(line -> line.startsWith("SESSION=") ? "__Host-" + line : line).sorted().toList());
                    assertEquals(PRODUCTION.logout(), plainName.logout().stream()
                            .map(line -> line.startsWith("SESSION=") ? "__Host-" + line : line).sorted().toList());
                }),
                test("only __Host-SESSION is read: the logged-in id under the default name SESSION gives 401", () -> {
                    Sessions sessions = CaddyExample.sessions(InstantSource.system());
                    try (var server = Support.serve(CaddyExample.router(TrustedProxies.of("172.18.0.5"), sessions,
                            LoginSupport.authenticator())); var client = client()) {
                        var base = "http://127.0.0.1:" + server.port();
                        var health = SchemeFlowTests.send(client, base, "GET", "/actuator/health", null, List.of());
                        var token = SchemeFlowTests.value(health, Csrf.COOKIE_NAME);
                        var login = SchemeFlowTests.send(client, base, "POST", "/api/auth/login",
                                LoginSupport.loginJson(ANNA, ANNA_PASSWORD),
                                List.of("Cookie: XSRF-TOKEN=" + token, "X-XSRF-TOKEN: " + token));
                        assertEquals(200, login.statusCode(), login.body());
                        var id = SchemeFlowTests.value(login, "__Host-SESSION");
                        var me = SchemeFlowTests.send(client, base, "GET", "/api/auth/me", null,
                                List.of("Cookie: __Host-SESSION=" + id));
                        assertEquals("{\"username\":\"anna\"}", me.body());
                        var other = SchemeFlowTests.send(client, base, "GET", "/api/auth/me", null,
                                List.of("Cookie: SESSION=" + id));
                        assertEquals(401, other.statusCode(), other.body());
                    }
                }),
                test("__Host-SESSION needs secure(true): without it Sessions.inMemory fails at startup", () -> {
                    var e = assertThrows(IllegalArgumentException.class,
                            () -> Sessions.inMemory(SessionOptions.DEFAULT.withCookieName("__Host-SESSION")));
                    assertTrue(e.getMessage().contains("__Host-"), e.getMessage());
                }),
                test("options(): 0.0.0.0 port 8080, plain http; the default listens on loopback", () -> {
                    var options = CaddyExample.options();
                    assertTrue(options.address().isAnyLocalAddress(), "listens on " + options.address());
                    assertEquals("0.0.0.0", options.address().getHostAddress());
                    assertEquals(8080, options.port());
                    assertEquals(Optional.empty(), options.tls());
                    assertTrue(HttpServerOptions.DEFAULT.address().isLoopbackAddress());
                }),
                test("the README shows CaddyExample.java as it is compiled and tested here", () -> {
                    var source = Files.readString(Path.of("modules/security-web/test/spola/security/web/CaddyExample.java"));
                    var header = "package spola.security.web;\n\n";
                    assertTrue(source.startsWith(header));
                    var block = "```java\n" + source.substring(header.length()) + "```\n";
                    assertTrue(part().contains(block), "the part Bakom Caddy differs from CaddyExample.java");
                    for (var text : List.of(".withCookieName(\"__Host-SESSION\")", ".withCookie(cookie -> cookie.secure(true))",
                            ".csrf(Csrf.cookie().withCookie(cookie -> cookie.secure(true)))",
                            "ForwardedHeaders.trusting(proxies)), // first", "TrustedProxies.of(\"172.18.0.5\")",
                            "InetAddress.ofLiteral(\"0.0.0.0\")")) {
                        assertTrue(source.contains(text), "the example lacks " + text);
                    }
                }),
                test("the part Bakom Caddy: the production setting for an app reached only over https, the lines,"
                        + " the warning, 0.0.0.0, and the corrected sentence about secure(true)", () -> {
                    var part = part();
                    for (var text : List.of("för en app som bara nås över https",
                            "Set-Cookie: XSRF-TOKEN=<token>; Path=/; Secure; SameSite=Lax",
                            "Set-Cookie: __Host-SESSION=<id>; Path=/; Secure; HttpOnly; SameSite=Lax",
                            "Set-Cookie: __Host-SESSION=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax",
                            "**Produktionsinställningen.**", "**`__Host-SESSION`.**", "cookie tossing", "`WARNING`",
                            "**Lyssna på `0.0.0.0`.**", "**Utan `secure(true)`**",
                            "beror `Secure` på att proxyns adress stämmer", "**Ordningen.**",
                            "**Bara från en betrodd proxy.**", "`Responses.withCookie`")) {
                        assertTrue(part.contains(text), "the part Bakom Caddy lacks " + text);
                    }
                    for (var old : List.of("behövs bara när cookierna ska vara `Secure` också över `http`",
                            "bakom en proxy utan `ForwardedHeaders`", "// no secure(true)")) {
                        assertFalse(part.contains(old), "the part still says " + old);
                    }
                }),
                test("the CSRF section and the session login recommend the production setting", () -> {
                    var section = CsrfReadmeTests.section();
                    assertTrue(section.contains("- **I produktion**, för en app som bara nås över https, rekommenderas\n"
                            + "  `Csrf.cookie().withCookie(c -> c.secure(true))` för CSRF-cookien"), "the CSRF section");
                    var login = LoginReadmeTests.between(Files.readString(ReadmeTests.README), "\n## Inloggning med session\n",
                            "\n## CSRF-skydd för en SPA\n");
                    assertTrue(login.contains("`withCookieName(\"__Host-SESSION\")`"), "the session login");
                    assertTrue(login.contains("[Bakom Caddy](#bakom-caddy)"), "the session login points to Bakom Caddy");
                }));
    }
}
