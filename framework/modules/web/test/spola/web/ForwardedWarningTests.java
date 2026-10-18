package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.web.ForwardedSupport.NOT_TRUSTING;
import static spola.web.ForwardedSupport.TRUSTING;
import static spola.web.ForwardedSupport.xff;
import static spola.web.ForwardedSupport.xfp;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import spola.http.Response;
import spola.test.Suite;
import spola.web.ForwardedSupport.Case;

/**
 * 15b, P1: {@link ForwardedHeaders} logs one {@code WARNING} per filter the first time a sender
 * that it does not trust sends {@code X-Forwarded-Proto} or {@code X-Forwarded-For}. The line
 * names the sender's address and never a value; a trusted sender and a request without the
 * headers give no line. The filter logs to a {@link Support.CapturedLog} through the
 * package-private {@code trusting(proxies, log)}; the router has a log of its own, which stays
 * empty.
 */
final class ForwardedWarningTests {

    private ForwardedWarningTests() {
    }

    /** The line for the sender 127.0.0.1, as {@link Support.CapturedLog} records it. */
    static final String LINE = "WARNING X-Forwarded-Proto or X-Forwarded-For from 127.0.0.1 ignored: ForwardedHeaders"
            + " does not trust this sender, so the request keeps the connection's scheme and address (behind a TLS"
            + " proxy the scheme is then http, and a cookie that follows the scheme gets no Secure). If the sender"
            + " is the proxy, trust its address in TrustedProxies. Logged once per filter.";

    /** A filter that logs to {@code filterLog}, and a router with its own log. */
    record Setup(ForwardedHeaders filter, Support.CapturedLog filterLog, Support.CapturedLog routerLog, Router router) {

        static Setup of(TrustedProxies proxies) {
            var filterLog = new Support.CapturedLog();
            return of(ForwardedHeaders.trusting(proxies, filterLog), filterLog);
        }

        static Setup of(ForwardedHeaders filter, Support.CapturedLog filterLog) {
            var routerLog = new Support.CapturedLog();
            var router = Router.builder()
                    .filter(filter)
                    .get("/who", request -> Response.text(200, ForwardedSupport.describe(request)))
                    .log(routerLog)
                    .build();
            return new Setup(filter, filterLog, routerLog, router);
        }
    }

    /** Requests from 127.0.0.1 with one of the headers, which a filter that does not trust 127.0.0.1 ignores. */
    static final List<Case> IGNORED = List.of(
            xfp("https"),
            xff("203.0.113.7"),
            new Case("both", "X-Forwarded-Proto: https", "X-Forwarded-For: 203.0.113.7"),
            new Case("lower-case names", "x-forwarded-proto: https", "x-forwarded-for: 203.0.113.7"),
            xfp(""),
            xff(""),
            xff("unknown"),
            new Case("two X-Forwarded-Proto fields", "X-Forwarded-Proto: https", "X-Forwarded-Proto: http"));

    /** Requests that wait for each other in {@code TrustedProxies.trusts}, so that they check the flag together. */
    static final int GATED = 50;

    /** Requests without either header: no line, also with the headers the filter does not read. */
    static final List<Case> WITHOUT = List.of(
            new Case("no header", new String[0]),
            new Case("X-Forwarded-Host", "X-Forwarded-Host: example.com"),
            new Case("X-Forwarded-Port", "X-Forwarded-Port: 443"),
            new Case("Forwarded (RFC 7239)", "Forwarded: for=203.0.113.7;proto=https"),
            new Case("X-Real-IP", "X-Real-IP: 203.0.113.7"),
            new Case("X-Forwarded-Protocol", "X-Forwarded-Protocol: https"));

    static Suite tests() {
        return suite("ForwardedHeaders warns once about a sender it does not trust",
                test("X-Forwarded-Proto: https from a sender that is not trusted: exactly one WARNING line, with the"
                        + " sender's address and without the value; the request is ignored as before", () -> {
                    var setup = Setup.of(NOT_TRUSTING);
                    try (var server = Support.serve(setup.router())) {
                        var reply = ForwardedSupport.send(server, "/who", xfp("https"));
                        assertEquals(200, reply.status(), reply.toString());
                        assertEquals("http 127.0.0.1 Inet4Address", reply.body());
                    }
                    var records = setup.filterLog().records();
                    assertEquals(List.of(LINE), records);
                    var line = records.getFirst();
                    assertTrue(line.startsWith("WARNING "), line);
                    assertTrue(line.contains(" from 127.0.0.1 "), line);
                    assertFalse(line.contains("https"), "the value is in the line: " + line);
                    assertEquals(List.of(), setup.routerLog().records(), "the router logs nothing");
                    LogTests.assertClean(records);
                }),
                test("ten such requests: still one line, and every request is ignored", () -> {
                    var setup = Setup.of(NOT_TRUSTING);
                    try (var server = Support.serve(setup.router())) {
                        for (int i = 0; i < 10; i++) {
                            var reply = ForwardedSupport.send(server, "/who", xfp("https"));
                            assertEquals("http 127.0.0.1 Inet4Address", reply.body());
                            assertEquals(1, setup.filterLog().records().size(), "after request " + (i + 1));
                        }
                    }
                    assertEquals(List.of(LINE), setup.filterLog().records());
                }),
                parameterized("either header, in any case of the name and with any value: one line for the first"
                        + " request, none for the next", IGNORED, header -> {
                    var setup = Setup.of(NOT_TRUSTING);
                    try (var server = Support.serve(setup.router())) {
                        for (int i = 0; i < 3; i++) {
                            var reply = ForwardedSupport.send(server, "/who", header);
                            assertEquals(200, reply.status(), header + ": " + reply);
                            assertEquals("http 127.0.0.1 Inet4Address", reply.body(), header.toString());
                        }
                    }
                    assertEquals(List.of(LINE), setup.filterLog().records(), header.toString());
                    assertEquals(List.of(), setup.routerLog().records());
                }),
                test("a trusted sender: no line, with valid headers, with a 400 for invalid ones and without headers",
                        () -> {
                    var setup = Setup.of(TRUSTING);
                    try (var server = Support.serve(setup.router())) {
                        for (int i = 0; i < 10; i++) {
                            assertEquals("https 203.0.113.7 Inet4Address", ForwardedSupport.send(server, "/who",
                                    new Case("both", "X-Forwarded-Proto: https", "X-Forwarded-For: 203.0.113.7")).body());
                        }
                        assertEquals(400, ForwardedSupport.send(server, "/who", xfp("ftp")).status());
                        assertEquals(400, ForwardedSupport.send(server, "/who", xff("unknown")).status());
                        assertEquals("http 127.0.0.1 Inet4Address",
                                ForwardedSupport.send(server, "/who", new Case("none", new String[0])).body());
                    }
                    assertEquals(List.of(), setup.filterLog().records());
                }),
                test("a sender that is not trusted, without X-Forwarded-Proto and X-Forwarded-For: no line (other"
                        + " proxy headers are not read); the headers afterwards: the line", () -> {
                    var setup = Setup.of(NOT_TRUSTING);
                    try (var server = Support.serve(setup.router())) {
                        for (var header : WITHOUT) {
                            for (int i = 0; i < 3; i++) {
                                var reply = ForwardedSupport.send(server, "/who", header);
                                assertEquals("http 127.0.0.1 Inet4Address", reply.body(), header.toString());
                            }
                            assertEquals(List.of(), setup.filterLog().records(), header.toString());
                        }
                        ForwardedSupport.send(server, "/who", xff("203.0.113.7"));
                    }
                    assertEquals(List.of(LINE), setup.filterLog().records());
                }),
                test("once per filter instance: two filters log a line each, one filter on two routers logs one", () -> {
                    var log = new Support.CapturedLog();
                    var shared = ForwardedHeaders.trusting(NOT_TRUSTING, log);
                    var first = Setup.of(shared, log);
                    var second = Setup.of(shared, log);
                    var other = Setup.of(NOT_TRUSTING);
                    try (var a = Support.serve(first.router()); var b = Support.serve(second.router());
                            var c = Support.serve(other.router())) {
                        for (int i = 0; i < 3; i++) {
                            ForwardedSupport.send(a, "/who", xfp("https"));
                            ForwardedSupport.send(b, "/who", xfp("https"));
                            ForwardedSupport.send(c, "/who", xfp("https"));
                        }
                    }
                    assertEquals(List.of(LINE), log.records(), "the shared filter");
                    assertEquals(List.of(LINE), other.filterLog().records(), "the other filter");
                }),
                test("200 concurrent requests with the headers from a sender that is not trusted: one line", () -> {
                    var setup = Setup.of(NOT_TRUSTING);
                    try (var server = Support.serve(setup.router());
                            var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        var replies = new ArrayList<Future<Support.Reply>>();
                        for (int i = 0; i < 200; i++) {
                            var header = IGNORED.get(i % IGNORED.size());
                            replies.add(executor.submit(() -> ForwardedSupport.send(server, "/who", header)));
                        }
                        for (var reply : replies) {
                            assertEquals("http 127.0.0.1 Inet4Address", reply.get().body());
                        }
                    }
                    assertEquals(List.of(LINE), setup.filterLog().records());
                }),
                test("50 requests that reach the check at the same moment (a barrier in TrustedProxies): one line",
                        () -> {
                    var barrier = new CyclicBarrier(GATED);
                    TrustedProxies gate = address -> {
                        try {
                            barrier.await(20, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException("the barrier broke", e);
                        }
                        return false;
                    };
                    var setup = Setup.of(gate);
                    try (var server = Support.serve(setup.router());
                            var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        var replies = new ArrayList<Future<Support.Reply>>();
                        for (int i = 0; i < GATED; i++) {
                            var header = IGNORED.get(i % IGNORED.size());
                            replies.add(executor.submit(() -> ForwardedSupport.send(server, "/who", header)));
                        }
                        for (var reply : replies) {
                            assertEquals("http 127.0.0.1 Inet4Address", reply.get().body());
                        }
                    }
                    assertEquals(List.of(LINE), setup.filterLog().records());
                }),
                test("the line names an IPv6 sender as TrustedProxies.of takes it (RFC 5952, without a zone)", () -> {
                    assertEquals(LINE.substring("WARNING ".length()),
                            ForwardedHeaders.warning(InetAddress.ofLiteral("127.0.0.1")));
                    for (var row : List.of(
                            List.of("::1", "::1"),
                            List.of("0:0:0:0:0:0:0:1", "::1"),
                            List.of("FD00:0:0:0:0:0:0:5", "fd00::5"),
                            List.of("2001:db8:0:0:1:0:0:1", "2001:db8::1:0:0:1"),
                            List.of("fe80::1%1", "fe80::1"),
                            List.of("172.18.0.6", "172.18.0.6"))) {
                        var line = ForwardedHeaders.warning(InetAddress.ofLiteral(row.get(0)));
                        assertTrue(line.startsWith("X-Forwarded-Proto or X-Forwarded-For from " + row.get(1) + " ignored: "),
                                line);
                        var parsed = TrustedProxies.of(row.get(1));
                        assertTrue(parsed.trusts(InetAddress.ofLiteral(row.get(0))), row.get(1) + " is the sender");
                    }
                }),
                test("ForwardedHeaders.trusting(proxies) logs to the System.Logger named spola.web", () -> {
                    assertEquals("spola.web", ForwardedHeaders.trusting(TRUSTING).log().getName());
                    assertEquals("spola.web", ForwardedHeaders.trusting(NOT_TRUSTING).log().getName());
                }));
    }
}
