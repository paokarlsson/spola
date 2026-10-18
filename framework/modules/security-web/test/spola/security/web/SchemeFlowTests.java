package spola.security.web;

import static spola.security.web.LoginSupport.ANNA;
import static spola.security.web.LoginSupport.ANNA_PASSWORD;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertNotEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import spola.http.HttpServer;
import spola.http.TlsOptions;
import spola.test.Suite;
import spola.web.ForwardedHeaders;
import spola.web.Router;
import spola.web.Sessions;
import spola.web.TrustedProxies;

/**
 * 13b, criteria 3 and 4: the flow of 11c ({@link CsrfExample}) through a real {@code HttpServer}
 * (127.0.0.1, port 0) and {@code HttpClient}, with the router
 * {@code CsrfExample.router(Router.builder().filter(ForwardedHeaders.trusting(...)), ...)} and
 * sessions without {@code secure(true)}. The cookies are sent by hand: a {@code CookieManager},
 * like a browser, never sends a {@code Secure} cookie over {@code http://}, and the server is
 * reached over {@code http://} as Caddy reaches it.
 *
 * <p>With {@code X-Forwarded-Proto: https} from a trusted proxy every {@code Set-Cookie} is,
 * character for character apart from the id and the token, the line that
 * {@code withCookie(c -> c.secure(true))} gives; with {@code X-Forwarded-Proto: http}, without
 * the header and from a sender that is not trusted, they are exactly the lines of 11a and 11c.
 * Over direct TLS (a self-signed key from {@code keytool}) the scheme is {@code https} without
 * any proxy, and the lines are the ones with {@code Secure}.
 */
final class SchemeFlowTests {

    private SchemeFlowTests() {
    }

    /** What Caddy sends with every request it passes on over HTTPS. */
    static final List<String> CADDY = List.of("X-Forwarded-Proto: https", "X-Forwarded-For: 203.0.113.7");

    /** {@code XSRF-TOKEN} with {@code Secure}, as {@code Csrf.cookie().withCookie(c -> c.secure(true))} sets it. */
    static final String SECURE_XSRF = "XSRF-TOKEN=%s; Path=/; Secure; SameSite=Lax";
    /** The session cookie with {@code Secure}, as {@code withCookie(c -> c.secure(true))} sets it. */
    static final String SECURE_SESSION = "SESSION=%s; Path=/; Secure; HttpOnly; SameSite=Lax";
    /** The removal of the session cookie with {@code Secure}. */
    static final String SECURE_REMOVAL = "SESSION=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax";

    /**
     * The {@code Set-Cookie} lines of each step of the flow, sorted, with a non-empty value
     * replaced by {@code %s} (so that they compare with the templates of the tests).
     *
     * @param health the {@code GET /actuator/health}
     * @param login  the login
     * @param me     {@code GET /api/auth/me} while logged in
     * @param logout the logout
     * @param after  {@code GET /api/auth/me} after the logout
     */
    record Flow(List<String> health, List<String> login, List<String> me, List<String> logout, List<String> after) {
    }

    /** The flow as 11a and 11c set the cookies: without {@code Secure}. */
    static final Flow PLAIN = new Flow(List.of(CsrfSupport.XSRF_COOKIE),
            List.of(LoginFlowTests.SESSION_COOKIE, CsrfSupport.XSRF_COOKIE), List.of(),
            List.of(LoginFlowTests.REMOVAL, CsrfSupport.XSRF_COOKIE), List.of());

    /** The flow with {@code Secure} on every cookie. */
    static final Flow SECURE = new Flow(List.of(SECURE_XSRF), List.of(SECURE_SESSION, SECURE_XSRF), List.of(),
            List.of(SECURE_REMOVAL, SECURE_XSRF), List.of());

    /** The line with its value replaced by {@code %s}, unless the value is empty. */
    static String template(String setCookie) {
        int equals = setCookie.indexOf('=');
        int semicolon = setCookie.indexOf(';');
        return equals + 1 == semicolon ? setCookie : setCookie.substring(0, equals + 1) + "%s"
                + setCookie.substring(semicolon);
    }

    static List<String> templates(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream().map(SchemeFlowTests::template).sorted().toList();
    }

    /** The value of the one {@code Set-Cookie} of {@code response} for {@code name}, checked for its form. */
    static String value(HttpResponse<String> response, String name) {
        var lines = response.headers().allValues("Set-Cookie").stream().filter(l -> l.startsWith(name + "="))
                .toList();
        assertEquals(1, lines.size(), "one Set-Cookie for " + name + " in a " + response.statusCode());
        var value = CsrfSupport.valueOf(lines.getFirst());
        assertTrue(CsrfSupport.TOKEN.matcher(value).matches(), "a well-formed value for " + name);
        return value;
    }

    /**
     * One request through {@code client} with {@code headers} (the proxy's and the cookies, by
     * hand) and an optional JSON body; the response is recorded for the leak tests, its own
     * cookie values masked (see {@link CsrfSupport#keep}).
     */
    static HttpResponse<String> send(HttpClient client, String base, String method, String path, String json,
            List<String> headers) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(30));
        if (json == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        for (var header : headers) {
            int colon = header.indexOf(':');
            builder.header(header.substring(0, colon), header.substring(colon + 1).strip());
        }
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        var fields = new ArrayList<String[]>();
        var head = new StringBuilder("HTTP/1.1 ").append(response.statusCode());
        response.headers().map().forEach((name, values) -> {
            for (var value : values) {
                fields.add(new String[] {name, value});
                head.append("\r\n").append(name).append(": ").append(value);
            }
        });
        CsrfSupport.keep(new Support.Reply(response.statusCode(), head.toString(), List.copyOf(fields),
                response.body()));
        return response;
    }

    static List<String> with(List<String> headers, String... more) {
        var all = new ArrayList<>(headers);
        all.addAll(List.of(more));
        return all;
    }

    /** {@link #flow(HttpClient, String, Sessions, List)} over {@code http://} with a new client. */
    static Flow flow(HttpServer server, Sessions sessions, List<String> proxy) throws Exception {
        try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            return flow(client, "http://127.0.0.1:" + server.port(), sessions, proxy);
        }
    }

    /**
     * The need's flow through {@code client} against {@code base}, with the cookies sent by hand
     * and {@code proxy} on every request: the health check gives {@code XSRF-TOKEN}, the login a
     * {@code SESSION} and a new token, {@code /api/auth/me} the name, the logout the removal and
     * a new token, and {@code /api/auth/me} then 401, also with the old id.
     */
    static Flow flow(HttpClient client, String base, Sessions sessions, List<String> proxy) throws Exception {
        return flow(client, base, sessions, proxy, "SESSION");
    }

    /**
     * {@link #flow(HttpClient, String, Sessions, List)} with the session cookie named
     * {@code sessionCookieName} (15b: {@code __Host-SESSION}).
     */
    static Flow flow(HttpClient client, String base, Sessions sessions, List<String> proxy, String sessionCookieName)
            throws Exception {
        var health = send(client, base, "GET", "/actuator/health", null, proxy);
        assertEquals(200, health.statusCode());
        assertEquals("{\"status\":\"UP\"}", health.body());
        var first = value(health, Csrf.COOKIE_NAME);
        assertEquals(0, sessions.size(), "the health check creates no session");

        var login = send(client, base, "POST", "/api/auth/login", LoginSupport.loginJson(ANNA, ANNA_PASSWORD),
                with(proxy, "Cookie: XSRF-TOKEN=" + first, "X-XSRF-TOKEN: " + first));
        assertEquals(200, login.statusCode(), login.body());
        assertEquals("{\"success\":true,\"username\":\"anna\"}", login.body());
        var id = value(login, sessionCookieName);
        var second = value(login, Csrf.COOKIE_NAME);
        assertNotEquals(first, second);
        assertEquals(List.of("no-store"), login.headers().allValues("Cache-Control"));
        assertEquals(1, sessions.size());

        var me = send(client, base, "GET", "/api/auth/me", null,
                with(proxy, "Cookie: " + sessionCookieName + "=" + id + "; XSRF-TOKEN=" + second));
        assertEquals(200, me.statusCode());
        assertEquals("{\"username\":\"anna\"}", me.body());

        var logout = send(client, base, "POST", "/api/auth/logout", null,
                with(proxy, "Cookie: " + sessionCookieName + "=" + id + "; XSRF-TOKEN=" + second, "X-XSRF-TOKEN: " + second));
        assertEquals(200, logout.statusCode(), logout.body());
        assertEquals("{\"success\":true}", logout.body());
        var third = value(logout, Csrf.COOKIE_NAME);
        assertNotEquals(second, third);
        assertEquals(0, sessions.size());

        var after = send(client, base, "GET", "/api/auth/me", null, with(proxy, "Cookie: XSRF-TOKEN=" + third));
        LoginFlowTests.assertPlain401(after);
        var oldId = send(client, base, "GET", "/api/auth/me", null,
                with(proxy, "Cookie: " + sessionCookieName + "=" + id + "; XSRF-TOKEN=" + third));
        LoginFlowTests.assertPlain401(oldId);
        return new Flow(templates(health), templates(login), templates(me), templates(logout), templates(after));
    }

    /** The password of the keystore that {@link #selfSigned} makes; not a secret. */
    static final String TLS_PASSWORD = "Scheme-Test-4711";

    /**
     * A self-signed EC key for {@code 127.0.0.1} in {@code dir/server.p12}, alias {@code server}.
     * The JDK has no public API that issues certificates, so {@code keytool} from
     * {@code java.home} makes it, as {@code spola.http}'s TLS tests do.
     */
    static Path selfSigned(Path dir) throws Exception {
        var keystore = dir.resolve("server.p12");
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-noprompt", "-keystore", keystore.toString(), "-storetype", "PKCS12",
                "-storepass", TLS_PASSWORD, "-keypass", TLS_PASSWORD, "-alias", "server", "-keyalg", "EC",
                "-groupname", "secp256r1", "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1", "-validity", "2");
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IllegalStateException("keytool failed:\n" + output);
        }
        return keystore;
    }

    /** A client context that trusts exactly the certificate in {@code keystore}. */
    static SSLContext trusting(Path keystore) throws Exception {
        var store = KeyStore.getInstance(keystore.toFile(), TLS_PASSWORD.toCharArray());
        var trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        trusted.setCertificateEntry("server", store.getCertificate("server"));
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trusted);
        var context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }

    /** {@code CsrfExample.router} with {@code ForwardedHeaders.trusting(proxies)} first, and sessions without {@code secure(true)}. */
    static Flow behind(TrustedProxies proxies, List<String> headers) throws Exception {
        Sessions sessions = CsrfExample.sessions(false, InstantSource.system());
        var router = CsrfExample.router(Router.builder().filter(ForwardedHeaders.trusting(proxies)),
                CsrfExample.security(sessions), LoginSupport.authenticator());
        try (var server = Support.serve(router)) {
            return flow(server, sessions, headers);
        }
    }

    static Suite tests() {
        return suite("the need's flow behind a proxy (13b)",
                test("behind Caddy, X-Forwarded-Proto: https: health, login, me, logout and 401, every Set-Cookie"
                        + " with Secure", () -> {
                    var flow = behind(TrustedProxies.privateNetworks(), CADDY);
                    assertEquals(SECURE, flow);
                }),
                test("character for character the lines that withCookie(c -> c.secure(true)) gives, but for the id and"
                        + " the token", () -> {
                    var behindCaddy = behind(TrustedProxies.privateNetworks(), CADDY);
                    // the same application with secure(true) in both cookie functions, reached directly over http:
                    // CaddyExample.security is CsrfExample's rules with Csrf.cookie().withCookie(c -> c.secure(true))
                    Sessions sessions = CsrfExample.sessions(true, InstantSource.system());
                    try (var server = Support.serve(CsrfExample.router(Router.builder(), CaddyExample.security(sessions),
                            LoginSupport.authenticator()))) {
                        var secureTrue = flow(server, sessions, List.of());
                        assertEquals(SECURE, secureTrue);
                        assertEquals(secureTrue, behindCaddy);
                    }
                }),
                test("X-Forwarded-Proto: http gives exactly the lines of 11a and 11c", () -> {
                    assertEquals(PLAIN, behind(TrustedProxies.privateNetworks(),
                            List.of("X-Forwarded-Proto: http", "X-Forwarded-For: 203.0.113.7")));
                }),
                test("without X-Forwarded-Proto: exactly the lines of 11a and 11c", () -> {
                    assertEquals(PLAIN, behind(TrustedProxies.privateNetworks(), List.of()));
                }),
                test("X-Forwarded-Proto: https from a sender that is not trusted (TrustedProxies.of(\"10.0.0.0/8\")):"
                        + " exactly the lines of 11a and 11c", () -> {
                    assertEquals(PLAIN, behind(TrustedProxies.of("10.0.0.0/8"), CADDY));
                }),
                test("over direct TLS, without a proxy and without ForwardedHeaders, the scheme is https: every"
                        + " Set-Cookie with Secure", () -> {
                    var dir = Files.createTempDirectory("spola-security-web-tls-");
                    try {
                        var keystore = selfSigned(dir);
                        Sessions sessions = CsrfExample.sessions(false, InstantSource.system());
                        var router = CsrfExample.router(Router.builder(), CsrfExample.security(sessions),
                                LoginSupport.authenticator());
                        var options = Support.options().withTls(TlsOptions.pkcs12(keystore, TLS_PASSWORD.toCharArray()));
                        try (var server = HttpServer.start(options, router);
                                var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                                        .sslContext(trusting(keystore)).connectTimeout(Duration.ofSeconds(5)).build()) {
                            assertEquals(SECURE, flow(client, "https://127.0.0.1:" + server.port(), sessions, List.of()));
                        }
                    } finally {
                        try (var files = Files.walk(dir)) {
                            for (var file : files.sorted(Comparator.reverseOrder()).toList()) {
                                Files.deleteIfExists(file);
                            }
                        }
                    }
                }));
    }
}
