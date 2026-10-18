package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.web.ForwardedSupport.TRUSTING;
import static spola.web.ForwardedSupport.xff;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import spola.http.HttpServer;
import spola.test.Suite;
import spola.web.ForwardedSupport.Case;

/**
 * Criteria 4 and 5: {@code X-Forwarded-For} from a trusted sender, walked from the right with
 * {@code TrustedProxies.of("127.0.0.0/8", "10.0.0.0/8", "fd00::/8")}, and the elements that give
 * 400.
 */
final class ForwardedForTests {

    private ForwardedForTests() {
    }

    /**
     * A table row: the header lines and what the handler sees.
     *
     * @param header   the request's header lines
     * @param expected {@code scheme address class}
     */
    record Row(Case header, String expected) {
        @Override
        public String toString() {
            return header + " -> " + expected;
        }
    }

    static Row row(String value, String expected) {
        return new Row(xff(value), expected);
    }

    /** Criterion 4, and more of the same kind. */
    static final List<Row> CLIENTS = List.of(
            row("203.0.113.7", "http 203.0.113.7 Inet4Address"),
            row("198.51.100.1, 203.0.113.7", "http 203.0.113.7 Inet4Address"),
            row("skräp, 203.0.113.7", "http 203.0.113.7 Inet4Address"),
            row("unknown, [::1]:80, 127.1, 203.0.113.7, 10.0.0.2", "http 203.0.113.7 Inet4Address"),
            row("203.0.113.7, 10.0.0.2, 10.0.0.3", "http 203.0.113.7 Inet4Address"),
            row("10.0.0.5, 10.0.0.2", "http 10.0.0.5 Inet4Address"),
            new Row(new Case("two fields: 203.0.113.7 and 10.0.0.2", "X-Forwarded-For: 203.0.113.7",
                    "X-Forwarded-For: 10.0.0.2"), "http 203.0.113.7 Inet4Address"),
            new Row(new Case("three fields: garbage, 198.51.100.9 and 10.0.0.2, 10.0.0.3", "X-Forwarded-For: garbage",
                    "X-Forwarded-For: 198.51.100.9", "X-Forwarded-For: 10.0.0.2, 10.0.0.3"),
                    "http 198.51.100.9 Inet4Address"),
            new Row(new Case("two fields, both trusted: the leftmost of all", "X-Forwarded-For: 10.0.0.7, 10.0.0.8",
                    "X-Forwarded-For: 10.0.0.2"), "http 10.0.0.7 Inet4Address"),
            row(" 203.0.113.7 ,, 10.0.0.2 ,", "http 203.0.113.7 Inet4Address"),
            row("\t203.0.113.7\t,\t10.0.0.2\t", "http 203.0.113.7 Inet4Address"),
            row("203.0.113.7,10.0.0.2", "http 203.0.113.7 Inet4Address"),
            row("", "http 127.0.0.1 Inet4Address"),
            row(" , ", "http 127.0.0.1 Inet4Address"),
            row(",,,", "http 127.0.0.1 Inet4Address"),
            row("2001:db8::1", "http 2001:db8:0:0:0:0:0:1 Inet6Address"),
            row("2001:DB8::1, fd00::2", "http 2001:db8:0:0:0:0:0:1 Inet6Address"),
            row("2001:0db8:0000:0000:0000:0000:0000:0001", "http 2001:db8:0:0:0:0:0:1 Inet6Address"),
            row("fd00::1, fd00::2", "http fd00:0:0:0:0:0:0:1 Inet6Address"),
            row("::ffff:203.0.113.7", "http 203.0.113.7 Inet4Address"),
            row("::FFFF:cb00:7107", "http 203.0.113.7 Inet4Address"),
            row("::ffff:10.0.0.9, 10.0.0.2", "http 10.0.0.9 Inet4Address"),
            row("203.0.113.7, ::ffff:10.0.0.9", "http 203.0.113.7 Inet4Address"),
            row("::1.2.3.4", "http 0:0:0:0:0:0:102:304 Inet6Address"),
            row("1:2:3:4:5:6:7:8", "http 1:2:3:4:5:6:7:8 Inet6Address"),
            row("1:2:3:4:5:6:7::", "http 1:2:3:4:5:6:7:0 Inet6Address"),
            row("::", "http 0:0:0:0:0:0:0:0 Inet6Address"),
            row("0.0.0.0", "http 0.0.0.0 Inet4Address"),
            row("255.255.255.255", "http 255.255.255.255 Inet4Address"),
            row("2001:db8::1, 127.0.0.1", "http 2001:db8:0:0:0:0:0:1 Inet6Address"),
            row("10.1.2.3", "http 10.1.2.3 Inet4Address"),
            row("127.0.0.2", "http 127.0.0.2 Inet4Address"));

    static Suite tests() {
        var calls = new AtomicInteger();
        var log = new Support.CapturedLog();
        var server = new AtomicReference<HttpServer>();
        return suite("X-Forwarded-For",
                parameterized("from a trusted sender: the client is the first untrusted address from the right",
                        CLIENTS, row -> {
                            var reply = ForwardedSupport.send(server.get(), "/who", row.header());
                            assertEquals(200, reply.status(), reply.toString());
                            assertEquals(row.expected(), reply.body());
                        }),
                test("criterion 5 has at least 20 values, among them the ones it names", () -> {
                    var names = ForwardedSupport.INVALID_FOR.stream().map(Case::toString).toList();
                    assertTrue(names.size() >= 20, "only " + names.size());
                    for (var value : List.of("unknown", "example.com", "203.0.113.7:8080", "[2001:db8::1]", "fe80::1%1",
                            "fe80::1%eth0", "127.1", "2130706433", "1.2.3", "010.0.0.1", "0x7f.0.0.1", "256.1.1.1",
                            "1.2.3.4.5", "203.0.113.7 10.0.0.1", "2001:db8::1::2", "1:2:3:4:5:6:7:8:9",
                            "skr\\u00e4p, 10.0.0.2", "203.0.113.7\\u00a0")) {
                        assertTrue(names.contains("X-Forwarded-For: \"" + value + "\""), "missing " + value);
                    }
                }),
                parameterized("an invalid element that the walk reaches: 400, the handler not called, no value in"
                        + " the detail", ForwardedSupport.INVALID_FOR, header -> {
                            int before = calls.get();
                            var reply = ForwardedSupport.send(server.get(), "/who", header);
                            assertInvalid(reply, "X-Forwarded-For", header);
                            assertEquals(before, calls.get(), "the handler was called");
                            assertEquals(List.of(), log.records(), "the filter and the router log nothing");
                        }),
                test("an invalid element to the left of the client is never read: no 400", () -> {
                    for (var invalid : ForwardedSupport.INVALID_FOR) {
                        if (invalid.lines().size() != 1) {
                            continue;
                        }
                        var value = invalid.values().getFirst();
                        var reply = ForwardedSupport.send(server.get(), "/who", xff(value + ", 203.0.113.7, 10.0.0.2"));
                        assertEquals("http 203.0.113.7 Inet4Address", reply.body(), invalid.toString());
                    }
                }))
                .beforeAll(() -> server.set(Support.serve(ForwardedSupport.whoRouter(TRUSTING, calls, log))))
                .afterAll(() -> {
                    if (server.get() != null) {
                        server.get().close();
                    }
                });
    }

    /**
     * A 400 problem response from {@code ForwardedHeaders}: {@code application/problem+json},
     * the detail names {@code headerName} and holds no value of {@code header}.
     */
    static void assertInvalid(Support.Reply reply, String headerName, Case header) {
        assertEquals(400, reply.status(), header + ": " + reply);
        assertEquals("application/problem+json", reply.header("Content-Type"));
        var detail = reply.json().string("detail");
        assertTrue(detail.contains(headerName), detail);
        for (var value : header.values()) {
            for (var part : value.split(",")) {
                var element = part.strip();
                if (!element.isEmpty()) {
                    assertFalse(reply.body().contains(element), header + ": the response holds " + element);
                    assertFalse(detail.contains(element), header + ": the detail holds " + element);
                }
            }
        }
    }
}
