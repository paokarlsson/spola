package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertSame;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;
import spola.test.Suite;

/** Criterion 8: {@link TrustedProxies} without a network, with addresses from {@code InetAddress.ofLiteral}. */
final class TrustedProxiesTests {

    private TrustedProxiesTests() {
    }

    static InetAddress at(String literal) {
        return InetAddress.ofLiteral(literal);
    }

    static void assertTrusts(TrustedProxies proxies, String... literals) {
        for (var literal : literals) {
            assertTrue(proxies.trusts(at(literal)), proxies + " should trust " + literal);
        }
    }

    static void assertDistrusts(TrustedProxies proxies, String... literals) {
        for (var literal : literals) {
            assertFalse(proxies.trusts(at(literal)), proxies + " should not trust " + literal);
        }
    }

    /** Entries that {@code of} refuses. */
    static final List<String> INVALID = List.of("10.0.0.0/33", "::/129", "10.0.0.1/8", "10.0.0.0/08", "10.0.0.0/",
            "/8", "10.0.0.0/8/8", "10.0.0.0 /8", "10.0.0.0/ 8", " 10.0.0.0/8", "10.0.0.0/-1", "10.0.0.0/+8",
            "10.0.0.0/1000", "10.0.0.0/8a", "::ffff:10.0.0.0/104", "::ffff:a00:0/104", "::ffff:10.0.0.1",
            "localhost", "example.com", "[::1]", "[::1]/128", "fe80::1%1", "fe80::%eth0/10", "127.1", "2130706433",
            "010.0.0.0/8", "0x0a.0.0.0/8", "", "10.0.0.0/8 ", "10.0.0.0\n/8", "::1/0128", "2001:db8::/32/",
            "fc00::/7 ", "１０.0.0.0/8");

    static Suite tests() {
        return suite("TrustedProxies",
                test("privateNetworks() trusts the edges of its networks, and ::ffff:10.0.0.1", () -> {
                    assertTrusts(TrustedProxies.privateNetworks(), "10.0.0.0", "10.255.255.255", "172.16.0.0",
                            "172.31.255.255", "192.168.0.0", "192.168.255.255", "127.0.0.1", "127.0.0.0",
                            "127.255.255.255", "::1", "fc00::", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "fd12::34",
                            "::ffff:10.0.0.1", "::ffff:192.168.1.1");
                }),
                test("privateNetworks() does not trust the addresses next to them, public ones or link-local ones",
                        () -> {
                    assertDistrusts(TrustedProxies.privateNetworks(), "9.255.255.255", "11.0.0.0", "172.15.255.255",
                            "172.32.0.0", "192.167.255.255", "192.169.0.0", "126.255.255.255", "128.0.0.0",
                            "169.254.1.1", "100.64.0.1", "203.0.113.7", "0.0.0.0", "255.255.255.255", "::", "::2",
                            "::0.0.0.2", "fe80::1", "fbff:ffff::1", "fbff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "fe00::1",
                            "fe80::1%1", "2001:db8::1", "::ffff:203.0.113.7", "::ffff:169.254.1.1");
                }),
                test("privateNetworks(): one instance, and its toString()", () -> {
                    assertSame(TrustedProxies.privateNetworks(), TrustedProxies.privateNetworks());
                    assertEquals("TrustedProxies[10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 127.0.0.0/8, ::1/128,"
                            + " fc00::/7]", TrustedProxies.privateNetworks().toString());
                }),
                test("0.0.0.0/0 trusts every IPv4 address and no IPv6 address; ::/0 the other way round", () -> {
                    var ipv4 = TrustedProxies.of("0.0.0.0/0");
                    var ipv6 = TrustedProxies.of("::/0");
                    var v4 = new String[] {"0.0.0.0", "255.255.255.255", "203.0.113.7", "10.0.0.1", "127.0.0.1",
                        "::ffff:203.0.113.7"};
                    var v6 = new String[] {"::", "::1", "2001:db8::1", "fe80::1%1",
                        "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "::1.2.3.4"};
                    assertTrusts(ipv4, v4);
                    assertDistrusts(ipv4, v6);
                    assertTrusts(ipv6, v6);
                    assertDistrusts(ipv6, v4);
                }),
                test("a single address trusts only itself", () -> {
                    var one = TrustedProxies.of("203.0.113.7");
                    assertTrusts(one, "203.0.113.7", "::ffff:203.0.113.7");
                    assertDistrusts(one, "203.0.113.6", "203.0.113.8", "203.0.112.7", "::203.0.113.7", "0.0.0.0",
                            "2001:db8::1");
                    var six = TrustedProxies.of("2001:db8::1");
                    assertTrusts(six, "2001:db8::1", "2001:DB8:0:0:0:0:0:1");
                    assertDistrusts(six, "2001:db8::", "2001:db8::2", "2001:db8::1:0", "203.0.113.7");
                }),
                test("prefix edges: on and just outside /12, /7, /9, /1, /31, /32, /127 and /128", () -> {
                    var twelve = TrustedProxies.of("172.16.0.0/12");
                    assertTrusts(twelve, "172.16.0.0", "172.31.255.255");
                    assertDistrusts(twelve, "172.15.255.255", "172.32.0.0");
                    var seven = TrustedProxies.of("fc00::/7");
                    assertTrusts(seven, "fc00::", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff");
                    assertDistrusts(seven, "fbff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "fe00::");
                    var nine = TrustedProxies.of("10.128.0.0/9");
                    assertTrusts(nine, "10.128.0.0", "10.255.255.255");
                    assertDistrusts(nine, "10.127.255.255", "11.0.0.0");
                    var one = TrustedProxies.of("128.0.0.0/1");
                    assertTrusts(one, "128.0.0.0", "255.255.255.255");
                    assertDistrusts(one, "127.255.255.255", "0.0.0.0");
                    var pair = TrustedProxies.of("203.0.113.6/31");
                    assertTrusts(pair, "203.0.113.6", "203.0.113.7");
                    assertDistrusts(pair, "203.0.113.5", "203.0.113.8");
                    var host = TrustedProxies.of("203.0.113.7/32");
                    assertTrusts(host, "203.0.113.7");
                    assertDistrusts(host, "203.0.113.6", "203.0.113.8");
                    var sixPair = TrustedProxies.of("2001:db8::/127");
                    assertTrusts(sixPair, "2001:db8::", "2001:db8::1");
                    assertDistrusts(sixPair, "2001:db8::2", "2001:db7:ffff:ffff:ffff:ffff:ffff:ffff");
                    var sixHost = TrustedProxies.of("2001:db8::1/128");
                    assertTrusts(sixHost, "2001:db8::1");
                    assertDistrusts(sixHost, "2001:db8::", "2001:db8::2");
                }),
                test("a zone on the address does not count", () -> {
                    var proxies = TrustedProxies.of("fe80::/10");
                    assertTrusts(proxies, "fe80::1", "fe80::1%1", "febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff");
                    assertDistrusts(proxies, "fec0::1", "fe7f:ffff:ffff:ffff:ffff:ffff:ffff:ffff");
                    var scoped = Inet6Address.getByAddress(null, at("fd00::5").getAddress(), 3);
                    assertTrue(TrustedProxies.privateNetworks().trusts(scoped), String.valueOf(scoped));
                }),
                test("several networks, in the order given, with mixed forms", () -> {
                    var proxies = TrustedProxies.of("192.0.2.1", "2001:DB8:0:0::/64", "::1.2.3.4", "198.51.100.0/24");
                    assertEquals("TrustedProxies[192.0.2.1, 2001:DB8:0:0::/64, ::1.2.3.4, 198.51.100.0/24]",
                            proxies.toString());
                    assertTrusts(proxies, "192.0.2.1", "2001:db8::abcd", "::102:304", "198.51.100.255");
                    assertDistrusts(proxies, "192.0.2.2", "2001:db8:0:1::", "1.2.3.4", "198.51.101.0");
                }),
                parameterized("an invalid entry is an IllegalArgumentException that quotes it", INVALID, entry -> {
                    var e = assertThrows(IllegalArgumentException.class, () -> TrustedProxies.of(entry));
                    assertTrue(e.getMessage().contains("\"" + Text.escape(entry, 100) + "\""), e.getMessage());
                    var withGood = assertThrows(IllegalArgumentException.class,
                            () -> TrustedProxies.of("10.0.0.0/8", entry));
                    assertEquals(e.getMessage(), withGood.getMessage());
                }),
                test("the entries criterion 8 names are all refused, and of() too", () -> {
                    for (var entry : List.of("10.0.0.0/33", "::/129", "10.0.0.1/8", "10.0.0.0/08", "10.0.0.0/", "/8",
                            "10.0.0.0/8/8", "10.0.0.0 /8", "10.0.0.0/-1", "10.0.0.0/+8", "::ffff:10.0.0.0/104",
                            "localhost", "example.com", "[::1]", "fe80::1%1", "127.1")) {
                        assertTrue(INVALID.contains(entry), entry);
                    }
                    assertThrows(IllegalArgumentException.class, () -> TrustedProxies.of());
                    assertThrows(IllegalArgumentException.class, () -> TrustedProxies.of(new String[0]));
                }),
                test("the messages: a suggestion for bits after the prefix, IPv4 as IPv4, one line per entry", () -> {
                    var bits = assertThrows(IllegalArgumentException.class, () -> TrustedProxies.of("10.0.0.1/8"));
                    assertTrue(bits.getMessage().contains("\"10.0.0.0/8\""), bits.getMessage());
                    var six = assertThrows(IllegalArgumentException.class, () -> TrustedProxies.of("2001:db8::1/32"));
                    assertTrue(six.getMessage().contains("\"2001:db8::/32\""), six.getMessage());
                    var odd = assertThrows(IllegalArgumentException.class, () -> TrustedProxies.of("fd12:3456::/7"));
                    assertTrue(odd.getMessage().contains("\"fc00::/7\""), odd.getMessage());
                    var mapped = assertThrows(IllegalArgumentException.class,
                            () -> TrustedProxies.of("::ffff:10.0.0.0/104"));
                    assertTrue(mapped.getMessage().contains("IPv4-mapped") && mapped.getMessage().contains("as IPv4"),
                            mapped.getMessage());
                    var many = assertThrows(IllegalArgumentException.class,
                            () -> TrustedProxies.of("localhost", "10.0.0.0/8", "10.0.0.1/8", "::/129"));
                    var lines = many.getMessage().split("\n");
                    assertEquals(3, lines.length, many.getMessage());
                    assertTrue(lines[0].contains("\"localhost\"") && lines[1].contains("\"10.0.0.1/8\"")
                            && lines[2].contains("\"::/129\""), many.getMessage());
                }),
                test("null as the array, an entry or an address is a NullPointerException", () -> {
                    assertThrows(NullPointerException.class, () -> TrustedProxies.of((String[]) null));
                    assertThrows(NullPointerException.class, () -> TrustedProxies.of("10.0.0.0/8", null));
                    assertThrows(NullPointerException.class, () -> TrustedProxies.of((String) null));
                    assertThrows(NullPointerException.class, () -> TrustedProxies.privateNetworks().trusts(null));
                    assertThrows(NullPointerException.class, () -> TrustedProxies.of("10.0.0.0/8").trusts(null));
                    assertThrows(NullPointerException.class, () -> ForwardedHeaders.trusting(null));
                }),
                test("the array is copied: a later change does not show", () -> {
                    var networks = new String[] {"10.0.0.0/8"};
                    var proxies = TrustedProxies.of(networks);
                    networks[0] = "0.0.0.0/0";
                    assertTrusts(proxies, "10.0.0.1");
                    assertDistrusts(proxies, "203.0.113.7");
                    assertEquals("TrustedProxies[10.0.0.0/8]", proxies.toString());
                }),
                test("a lambda is a TrustedProxies, and ForwardedHeaders names its proxies", () -> {
                    TrustedProxies nobody = address -> false;
                    assertFalse(nobody.trusts(at("127.0.0.1")));
                    assertEquals("ForwardedHeaders[TrustedProxies[10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16,"
                            + " 127.0.0.0/8, ::1/128, fc00::/7]]",
                            ForwardedHeaders.trusting(TrustedProxies.privateNetworks()).toString());
                }));
    }
}
