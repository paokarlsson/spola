package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.web.ForwardedSupport.TRUSTING;
import static spola.web.ForwardedSupport.describe;
import static spola.web.ForwardedSupport.xff;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import spola.http.Response;
import spola.test.Suite;
import spola.web.ForwardedSupport.Case;

/**
 * Criterion 7: the order in the chain. Filters after {@code ForwardedHeaders}, a group's filters
 * and the handler see the client; a filter before it sees the connection; {@code http()} stays
 * the connection's; two filters give what one gives; the router's own answers pass through it.
 */
final class ForwardedChainTests {

    private ForwardedChainTests() {
    }

    static final AttributeKey<String> MARK = AttributeKey.of("mark", String.class);

    static final Case CLIENT = new Case("https 203.0.113.7 through 10.0.0.2", "X-Forwarded-Proto: https",
            "X-Forwarded-For: 203.0.113.7, 10.0.0.2");

    /** A filter that records {@code name: scheme address class} and passes the request on. */
    static Filter seeing(String name, List<String> seen) {
        return (request, next) -> {
            synchronized (seen) {
                seen.add(name + ": " + describe(request));
            }
            return next.proceed(request);
        };
    }

    static Suite tests() {
        return suite("ForwardedHeaders in the chain",
                test("after it: filters, a group's filter, a request passed on with withAttribute and the handler"
                        + " see the client; before it: the connection; http() is the connection", () -> {
                    var seen = new ArrayList<String>();
                    var router = Router.builder()
                            .filter(seeing("before", seen))
                            .filter(ForwardedHeaders.trusting(TRUSTING))
                            .filter(seeing("after", seen))
                            .filter((request, next) -> next.proceed(request.withAttribute(MARK, "marked")))
                            .group("/g", g -> g
                                    .filter(seeing("group", seen))
                                    .get("/who", request -> {
                                        synchronized (seen) {
                                            seen.add("handler: " + describe(request) + " " + request.attribute(MARK)
                                                    .orElseThrow());
                                            seen.add("http: " + request.http().scheme() + " "
                                                    + request.http().remoteAddress());
                                        }
                                        return Response.text(200, "ok");
                                    }))
                            .build();
                    try (var server = Support.serve(router); var socket = new Socket(Support.LOCALHOST, server.port())) {
                        socket.setSoTimeout(10_000);
                        var request = "GET /g/who HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                                + String.join("\r\n", CLIENT.lines()) + "\r\n\r\n";
                        socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
                        var response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
                        assertTrue(response.startsWith("HTTP/1.1 200 "), response);
                        assertEquals(List.of(
                                "before: http 127.0.0.1 Inet4Address",
                                "after: https 203.0.113.7 Inet4Address",
                                "group: https 203.0.113.7 Inet4Address",
                                "handler: https 203.0.113.7 Inet4Address marked",
                                "http: http /127.0.0.1:" + socket.getLocalPort()), seen);
                    }
                }),
                test("two ForwardedHeaders in a row give what one gives", () -> {
                    var one = Router.builder()
                            .filter(ForwardedHeaders.trusting(TRUSTING))
                            .get("/who", request -> Response.text(200, describe(request)))
                            .build();
                    var two = Router.builder()
                            .filter(ForwardedHeaders.trusting(TRUSTING))
                            .filter(ForwardedHeaders.trusting(TRUSTING))
                            .get("/who", request -> Response.text(200, describe(request)))
                            .build();
                    var cases = List.of(CLIENT, xff("10.0.0.5, 10.0.0.2"), xff("203.0.113.7"),
                            xff("2001:db8::1, fd00::2"), xff("10.0.0.5, 10.0.0.2, 10.0.0.3"), xff(""),
                            new Case("proto only", "X-Forwarded-Proto: HTTPS"), new Case("none", new String[0]));
                    try (var single = Support.serve(one); var twice = Support.serve(two)) {
                        for (var header : cases) {
                            var expected = ForwardedSupport.send(single, "/who", header);
                            var actual = ForwardedSupport.send(twice, "/who", header);
                            assertEquals(200, expected.status(), header.toString());
                            assertEquals(expected.body(), actual.body(), header.toString());
                        }
                        assertEquals("http 10.0.0.5 Inet4Address",
                                ForwardedSupport.send(twice, "/who", xff("10.0.0.5, 10.0.0.2")).body());
                        var invalid = ForwardedSupport.send(twice, "/who", xff("unknown"));
                        assertEquals(400, invalid.status());
                    }
                }),
                test("a second ForwardedHeaders that does not trust the sender keeps what the first one found", () -> {
                    var router = Router.builder()
                            .filter(ForwardedHeaders.trusting(TRUSTING))
                            .filter(ForwardedHeaders.trusting(address -> false))
                            .get("/who", request -> Response.text(200, describe(request)))
                            .build();
                    try (var server = Support.serve(router)) {
                        assertEquals("https 203.0.113.7 Inet4Address",
                                ForwardedSupport.send(server, "/who", CLIENT).body());
                    }
                }),
                test("the router's 404, 405 and automatic OPTIONS pass through the filter with the client's values;"
                        + " an invalid value is a 400 also for a path or a method that does not exist", () -> {
                    var seen = new ArrayList<String>();
                    var calls = new AtomicInteger();
                    var router = Router.builder()
                            .filter(ForwardedHeaders.trusting(TRUSTING))
                            .filter((request, next) -> {
                                var response = next.proceed(request);
                                synchronized (seen) {
                                    seen.add(request.method() + " " + response.status() + " " + describe(request));
                                }
                                return response;
                            })
                            .get("/who", request -> {
                                calls.incrementAndGet();
                                return Response.text(200, describe(request));
                            })
                            .build();
                    try (var server = Support.serve(router)) {
                        assertEquals(404, Support.send(server.port(), "GET", "/missing",
                                CLIENT.lines().toArray(String[]::new)).status());
                        assertEquals(405, Support.send(server.port(), "POST", "/who",
                                CLIENT.lines().toArray(String[]::new)).status());
                        assertEquals(204, Support.send(server.port(), "OPTIONS", "/who",
                                CLIENT.lines().toArray(String[]::new)).status());
                        assertEquals(List.of("GET 404 https 203.0.113.7 Inet4Address",
                                "POST 405 https 203.0.113.7 Inet4Address",
                                "OPTIONS 204 https 203.0.113.7 Inet4Address"), seen);
                        seen.clear();
                        for (var target : List.of(new String[] {"GET", "/missing"}, new String[] {"POST", "/who"},
                                new String[] {"GET", "/a%2Fb"}, new String[] {"OPTIONS", "*"})) {
                            for (var header : List.of(xff("unknown"), new Case("proto", "X-Forwarded-Proto: ftp"))) {
                                var reply = Support.send(server.port(), target[0], target[1],
                                        header.lines().toArray(String[]::new));
                                assertEquals(400, reply.status(), target[0] + " " + target[1] + " " + header);
                                var detail = reply.json().string("detail");
                                assertTrue(detail.contains(header.lines().getFirst().substring(0,
                                        header.lines().getFirst().indexOf(':'))), detail);
                            }
                        }
                        assertEquals(List.of(), seen, "no filter after ForwardedHeaders ran for a 400");
                        assertEquals(0, calls.get());
                    }
                }));
    }
}
