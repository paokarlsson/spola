package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertNull;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import spola.test.Suite;

/**
 * The strict IP literals behind {@code X-Forwarded-For} and {@link TrustedProxies#of}: what is
 * taken and what is refused, agreement with {@link InetAddress#ofLiteral} on everything taken,
 * and the RFC 5952 text of the suggestions.
 */
final class IpLiteralTests {

    private IpLiteralTests() {
    }

    static final List<String> VALID = List.of("0.0.0.0", "1.2.3.4", "10.0.0.1", "127.0.0.1", "255.255.255.255",
            "203.0.113.7", "199.99.9.0", "::", "::1", "1::", "1:2:3:4:5:6:7:8", "1:2:3:4:5:6:7::", "::2:3:4:5:6:7:8",
            "1::8", "2001:db8::1", "2001:DB8::1", "2001:0db8:0000:0000:0000:0000:0000:0001", "fe80::1", "FFFF::",
            "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "::ffff:203.0.113.7", "::ffff:cb00:7107", "::FFFF:10.0.0.1",
            "::1.2.3.4", "1:2:3:4:5:6:1.2.3.4", "1::1.2.3.4", "::0:0:0:0:0:0:1", "0:0:0:0:0:0:0:0", "a:b:c:d:e:f:0:1",
            "1:2:3:4:5:6::8", "1:2::7:8");

    static final List<String> INVALID = List.of("", " ", ".", ":", ":::", "1", "1.2", "1.2.3", "1.2.3.4.5",
            "1.2.3.", ".1.2.3", "1..2.3", "127.1", "2130706433", "010.0.0.1", "0.00.0.0", "1.2.3.04", "00.0.0.0",
            "256.0.0.0", "1.2.3.256", "999.1.1.1", "1000.1.1.1", "0x7f.0.0.1", "1.2.3.4 ", " 1.2.3.4", "1.2.3.4\t",
            "1. 2.3.4", "+1.2.3.4", "-1.2.3.4", "1.2.3.4/32", "1.2.3.4:80", "a.b.c.d", "١.٢.٣.٤", "１.2.3.4",
            "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7:8::", "::1:2:3:4:5:6:7:8", "1:2:3:4:5:6:7::8",
            "1::2::3", "2001:db8::1::2", ":1:2:3:4:5:6:7", "1:2:3:4:5:6:7:", ":1::", "1::2:", "12345::", "::12345",
            "g::", "::g", "[::1]", "[::1]:80", "fe80::1%1", "fe80::1%eth0", "::1 ", " ::1", ":: 1", "::1/128",
            "1:2:3:4:5:6:7:1.2.3.4", "1:2:3:4:5:6:1.2.3", "::1.2.3.4:5", "::1.2.3.4.5", "::ffff:010.0.0.1",
            "::ffff:1.2.3.04", "::1.2.3", "1.2.3.4::", "::1.2.3.4::", "::-1", "::+1", "localhost", "example.com",
            "unknown", "_hidden", "::ä", "1.2.3.4\u00a0");

    static Suite tests() {
        return suite("IP literals",
                parameterized("taken, with the same address as InetAddress.ofLiteral", VALID, text -> {
                    var address = IpLiteral.address(text);
                    assertEquals(InetAddress.ofLiteral(text), address, text);
                    var bytes = IpLiteral.parse(text);
                    var expected = InetAddress.ofLiteral(text).getAddress();
                    assertTrue(Arrays.equals(expected, bytes)
                            || (expected.length == 4 && Arrays.equals(expected, 0, 4, bytes, 12, 16)), text);
                }),
                parameterized("refused", INVALID, text -> {
                    assertNull(IpLiteral.parse(text));
                    assertNull(IpLiteral.address(text));
                }),
                test("an IPv4-mapped literal is an Inet4Address; an IPv4-compatible one an Inet6Address", () -> {
                    assertTrue(IpLiteral.address("::ffff:203.0.113.7") instanceof Inet4Address);
                    assertTrue(IpLiteral.address("::ffff:cb00:7107") instanceof Inet4Address);
                    assertTrue(IpLiteral.address("::203.0.113.7") instanceof Inet6Address);
                    assertTrue(IpLiteral.isMapped(IpLiteral.parse("::ffff:0:0")));
                    assertTrue(!IpLiteral.isMapped(IpLiteral.parse("::fffe:0:0")));
                    assertTrue(!IpLiteral.isMapped(IpLiteral.parse("1::ffff:0:0")));
                }),
                test("format gives the RFC 5952 text", () -> {
                    for (var pair : List.of(
                            new String[] {"2001:0db8:0000:0000:0000:0000:0000:0001", "2001:db8::1"},
                            new String[] {"2001:db8:0:1:1:1:1:1", "2001:db8:0:1:1:1:1:1"},
                            new String[] {"2001:db8:0:0:1:0:0:1", "2001:db8::1:0:0:1"},
                            new String[] {"2001:0:0:1:0:0:0:1", "2001:0:0:1::1"},
                            new String[] {"::", "::"},
                            new String[] {"::1", "::1"},
                            new String[] {"1::", "1::"},
                            new String[] {"FC00::", "fc00::"},
                            new String[] {"1:0:2:0:3:0:4:0", "1:0:2:0:3:0:4:0"},
                            new String[] {"10.0.0.0", "10.0.0.0"},
                            new String[] {"255.255.255.255", "255.255.255.255"})) {
                        assertEquals(pair[1], IpLiteral.format(IpLiteral.parse(pair[0])), pair[0]);
                    }
                }),
                test("20 000 random addresses in many text forms: the strict reading and ofLiteral agree", () -> {
                    var random = new Random(4711);
                    for (int i = 0; i < 20_000; i++) {
                        var bytes = new byte[random.nextBoolean() ? 4 : 16];
                        random.nextBytes(bytes);
                        if (bytes.length == 16 && random.nextInt(4) == 0) {
                            Arrays.fill(bytes, random.nextInt(16), 16, (byte) 0); // runs of zeros for ::
                        }
                        for (var text : forms(bytes, random)) {
                            var parsed = IpLiteral.parse(text);
                            assertTrue(Arrays.equals(bytes, parsed), text + " read as " + Arrays.toString(parsed));
                            var address = IpLiteral.address(text);
                            assertEquals(InetAddress.ofLiteral(text), address, text);
                        }
                        var formatted = IpLiteral.format(bytes);
                        assertTrue(Arrays.equals(bytes, IpLiteral.parse(formatted)), formatted);
                    }
                }));
    }

    /** {@code bytes} written in the forms RFC 4291 allows, with random case and leading zeros. */
    static List<String> forms(byte[] bytes, Random random) {
        if (bytes.length == 4) {
            return List.of((bytes[0] & 0xFF) + "." + (bytes[1] & 0xFF) + "." + (bytes[2] & 0xFF) + "."
                    + (bytes[3] & 0xFF));
        }
        var groups = new String[8];
        for (int k = 0; k < 8; k++) {
            int value = ((bytes[2 * k] & 0xFF) << 8) | (bytes[2 * k + 1] & 0xFF);
            var hex = Integer.toHexString(value);
            hex = "0".repeat(random.nextInt(5 - hex.length())) + hex;
            groups[k] = random.nextBoolean() ? hex.toUpperCase(Locale.ROOT) : hex;
        }
        var full = String.join(":", groups);
        var dotted = String.join(":", Arrays.copyOf(groups, 6)) + ":" + (bytes[12] & 0xFF) + "." + (bytes[13] & 0xFF)
                + "." + (bytes[14] & 0xFF) + "." + (bytes[15] & 0xFF);
        var forms = new ArrayList<>(List.of(full, dotted, IpLiteral.format(bytes),
                HexFormat.of().formatHex(bytes).replaceAll("(.{4})(?!$)", "$1:")));
        // every run of zero groups [start, end), also a single group, may be written as ::
        for (int start = 0; start < 8; start++) {
            for (int end = start + 1; end <= 8 && isZero(groups[end - 1]); end++) {
                var left = String.join(":", Arrays.copyOfRange(groups, 0, start));
                var right = String.join(":", Arrays.copyOfRange(groups, end, 8));
                forms.add(left + "::" + right);
            }
        }
        return forms;
    }

    static boolean isZero(String group) {
        return group.chars().allMatch(c -> c == '0');
    }
}
