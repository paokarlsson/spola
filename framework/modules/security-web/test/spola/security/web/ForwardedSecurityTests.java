package spola.security.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertNull;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import spola.http.Response;
import spola.test.Suite;
import spola.web.ForwardedHeaders;
import spola.web.TrustedProxies;

/**
 * Criterion 12 of 13a: with {@code ForwardedHeaders} registered first and the security filter
 * after it, matchers and rules see the client's scheme and address.
 */
final class ForwardedSecurityTests {

    private ForwardedSecurityTests() {
    }

    /** One {@code GET} with the extra header lines over a raw socket, recorded for the leak test. */
    static Support.Reply get(int port, String target, String... headerLines) throws Exception {
        var request = new StringBuilder("GET ").append(target)
                .append(" HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n");
        for (var line : headerLines) {
            request.append(line).append("\r\n");
        }
        return Support.raw(port, request.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    static Suite tests() {
        return suite("behind a reverse proxy (ForwardedHeaders first)",
                test("a RequestMatcher lambda sees the client's scheme and address", () -> {
                    var seen = new ArrayList<String>();
                    var security = WebSecurity.builder()
                            .rule(request -> {
                                synchronized (seen) {
                                    seen.add(request.scheme() + " " + request.clientAddress().getHostAddress());
                                }
                                return request.scheme().equals("https");
                            }, AccessRule.permitAll())
                            .rule(RequestMatcher.anyRequest(), AccessRule.denyAll())
                            .build();
                    var router = Support.router()
                            .filter(ForwardedHeaders.trusting(TrustedProxies.privateNetworks()))
                            .filter(security.filter())
                            .get("/x", request -> Response.text(200, "x"))
                            .build();
                    try (var server = Support.serve(router)) {
                        var https = get(server.port(), "/x", "X-Forwarded-Proto: https",
                                "X-Forwarded-For: 203.0.113.7, 10.0.0.2");
                        assertEquals(200, https.status(), https.toString());
                        var http = get(server.port(), "/x", "X-Forwarded-For: 198.51.100.4");
                        assertEquals(401, http.status(), http.toString());
                        var direct = get(server.port(), "/x");
                        assertEquals(401, direct.status(), direct.toString());
                    }
                    assertEquals(List.of("https 203.0.113.7", "http 198.51.100.4", "http 127.0.0.1"), seen);
                }),
                test("a rule that denies addresses outside privateNetworks() denies X-Forwarded-For: 203.0.113.7 and"
                        + " lets a request without the header in", () -> {
                    var internal = TrustedProxies.privateNetworks();
                    var security = WebSecurity.builder()
                            .rule(request -> !internal.trusts(request.clientAddress()), AccessRule.denyAll())
                            .rule(RequestMatcher.anyRequest(), AccessRule.permitAll())
                            .build();
                    var router = Support.router()
                            .filter(ForwardedHeaders.trusting(TrustedProxies.privateNetworks()))
                            .filter(security.filter())
                            .get("/internal", request -> Response.text(200, "internal "
                                    + request.clientAddress().getHostAddress()))
                            .build();
                    try (var server = Support.serve(router)) {
                        var outside = get(server.port(), "/internal", "X-Forwarded-For: 203.0.113.7");
                        assertEquals(401, outside.status(), outside.toString());
                        assertEquals("application/problem+json", outside.header("Content-Type"));
                        assertNull(outside.header("WWW-Authenticate"));
                        var behindTwo = get(server.port(), "/internal", "X-Forwarded-For: 203.0.113.7, 10.0.0.2");
                        assertEquals(401, behindTwo.status());
                        var inside = get(server.port(), "/internal");
                        assertEquals(200, inside.status(), inside.toString());
                        assertEquals("internal 127.0.0.1", inside.body());
                        var proxied = get(server.port(), "/internal", "X-Forwarded-For: 10.1.2.3");
                        assertEquals("internal 10.1.2.3", proxied.body());
                    }
                }),
                test("registered after the security filter, ForwardedHeaders comes too late for the rules", () -> {
                    var internal = TrustedProxies.privateNetworks();
                    var security = WebSecurity.builder()
                            .rule(request -> !internal.trusts(request.clientAddress()), AccessRule.denyAll())
                            .rule(RequestMatcher.anyRequest(), AccessRule.permitAll())
                            .build();
                    var router = Support.router()
                            .filter(security.filter())
                            .filter(ForwardedHeaders.trusting(TrustedProxies.privateNetworks()))
                            .get("/internal", request -> Response.text(200, "internal "
                                    + request.clientAddress().getHostAddress()))
                            .build();
                    try (var server = Support.serve(router)) {
                        var reply = get(server.port(), "/internal", "X-Forwarded-For: 203.0.113.7");
                        assertEquals(200, reply.status(), "the rule saw the connection, 127.0.0.1");
                        assertEquals("internal 203.0.113.7", reply.body(), "the handler sees the client");
                    }
                }));
    }
}
