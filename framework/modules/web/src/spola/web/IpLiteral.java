package spola.web;

import java.net.InetAddress;
import java.util.Arrays;

/**
 * Strict IP literals, for {@code X-Forwarded-For} and {@link TrustedProxies#of}. The text is
 * checked here first, and only then turned into an address with {@link InetAddress#ofLiteral},
 * which never looks up a name. {@code ofLiteral} alone is too lenient for data from outside: it
 * takes {@code 127.1}, {@code 2130706433}, {@code 1.2.3} (as {@code 1.2.0.3}), {@code 010.0.0.1}
 * (as {@code 10.0.0.1}), {@code [2001:db8::1]} and {@code fe80::1%1}.
 *
 * <ul>
 *   <li><b>IPv4</b>: four decimal parts separated by {@code .}; each part 1 to 3 ASCII digits,
 *       0 to 255, without a leading zero (except {@code 0} itself).</li>
 *   <li><b>IPv6</b> (RFC 4291 §2.2): groups of 1 to 4 hex digits in either case separated by
 *       {@code :}, at most one {@code ::} (one or more groups of zeros), and optionally an IPv4
 *       part by the rule above instead of the last two groups. No brackets, zone ({@code %}),
 *       port or whitespace.</li>
 * </ul>
 */
final class IpLiteral {

    private IpLiteral() {
    }

    /**
     * The address of {@code text} if it is a strict IP literal, otherwise {@code null}. An
     * IPv4-mapped IPv6 literal ({@code ::ffff:203.0.113.7}) gives an {@code Inet4Address}, as
     * {@link InetAddress#ofLiteral} does.
     */
    static InetAddress address(String text) {
        var bytes = parse(text);
        if (bytes == null) {
            return null;
        }
        InetAddress address;
        try {
            address = InetAddress.ofLiteral(text);
        } catch (IllegalArgumentException e) {
            return null; // the JDK refuses what the strict check took: refuse it too
        }
        return sameAddress(address.getAddress(), bytes) ? address : null; // never another reading
    }

    /** The 4 or 16 bytes of a strict IPv4 or IPv6 literal, or {@code null}. */
    static byte[] parse(String text) {
        return text.indexOf(':') >= 0 ? ipv6(text) : ipv4(text, 0, text.length());
    }

    /** Whether 16 bytes are an IPv4-mapped IPv6 address ({@code ::ffff:0:0/96}, RFC 4291 §2.5.5.2). */
    static boolean isMapped(byte[] bytes) {
        if (bytes.length != 16 || bytes[10] != (byte) 0xFF || bytes[11] != (byte) 0xFF) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /** Whether the JDK's bytes are those the strict check read (the last 4 for a mapped address). */
    private static boolean sameAddress(byte[] actual, byte[] expected) {
        if (actual.length == expected.length) {
            return Arrays.equals(actual, expected);
        }
        return actual.length == 4 && isMapped(expected) && Arrays.equals(actual, 0, 4, expected, 12, 16);
    }

    /** {@code text[from, to)} as four decimal parts, or {@code null}. */
    private static byte[] ipv4(String text, int from, int to) {
        var bytes = new byte[4];
        int i = from;
        for (int part = 0; part < 4; part++) {
            if (part > 0) {
                if (i >= to || text.charAt(i) != '.') {
                    return null;
                }
                i++;
            }
            int start = i;
            int value = 0;
            while (i < to && i - start < 3 && isDigit(text.charAt(i))) {
                value = value * 10 + (text.charAt(i) - '0');
                i++;
            }
            int digits = i - start;
            if (digits == 0 || value > 255 || (digits > 1 && text.charAt(start) == '0')) {
                return null;
            }
            bytes[part] = (byte) value;
        }
        return i == to ? bytes : null;
    }

    /** {@code text} as an IPv6 literal, or {@code null}. */
    private static byte[] ipv6(String text) {
        int n = text.length();
        var groups = new int[8];
        int count = 0;
        int gap = -1; // the number of groups before "::", or -1 without one
        int i = 0;
        if (text.startsWith("::")) {
            gap = 0;
            i = 2;
        }
        while (i < n) {
            if (count == 8) {
                return null; // more than eight groups
            }
            int start = i;
            int value = 0;
            while (i < n && i - start < 4 && hex(text.charAt(i)) >= 0) {
                value = value * 16 + hex(text.charAt(i));
                i++;
            }
            if (i < n && text.charAt(i) == '.') { // the IPv4 part, which must end the literal
                var ipv4 = count <= 6 ? ipv4(text, start, n) : null;
                if (ipv4 == null) {
                    return null;
                }
                groups[count++] = ((ipv4[0] & 0xFF) << 8) | (ipv4[1] & 0xFF);
                groups[count++] = ((ipv4[2] & 0xFF) << 8) | (ipv4[3] & 0xFF);
                break;
            }
            if (i == start) {
                return null; // an empty group or a character that is not a hex digit
            }
            groups[count++] = value;
            if (i == n) {
                break;
            }
            if (text.charAt(i) != ':') {
                return null; // a fifth hex digit, or a character such as %, ], / or a space
            }
            i++;
            if (i < n && text.charAt(i) == ':') {
                if (gap >= 0) {
                    return null; // a second "::"
                }
                gap = count;
                i++;
            } else if (i == n) {
                return null; // a single colon at the end
            }
        }
        if (gap < 0 ? count != 8 : count > 7) {
            return null; // too few groups without "::", or "::" standing for no group at all
        }
        var bytes = new byte[16];
        for (int k = 0; k < count; k++) {
            int position = gap >= 0 && k >= gap ? k + 8 - count : k;
            bytes[2 * position] = (byte) (groups[k] >> 8);
            bytes[2 * position + 1] = (byte) groups[k];
        }
        return bytes;
    }

    /**
     * {@code bytes} as text: IPv4 dotted, IPv6 after RFC 5952 (lower case, no leading zeros, the
     * longest run of two or more zero groups as {@code ::}, the first on a tie).
     */
    static String format(byte[] bytes) {
        if (bytes.length == 4) {
            return (bytes[0] & 0xFF) + "." + (bytes[1] & 0xFF) + "." + (bytes[2] & 0xFF) + "." + (bytes[3] & 0xFF);
        }
        var groups = new int[8];
        for (int k = 0; k < 8; k++) {
            groups[k] = ((bytes[2 * k] & 0xFF) << 8) | (bytes[2 * k + 1] & 0xFF);
        }
        int bestStart = -1;
        int bestLength = 1; // a single zero group is not shortened
        for (int k = 0; k < 8; k++) {
            int length = 0;
            while (k + length < 8 && groups[k + length] == 0) {
                length++;
            }
            if (length > bestLength) {
                bestStart = k;
                bestLength = length;
            }
            k += length;
        }
        var out = new StringBuilder();
        for (int k = 0; k < 8; k++) {
            if (k == bestStart) {
                out.append("::");
                k += bestLength - 1;
            } else {
                if (!out.isEmpty() && out.charAt(out.length() - 1) != ':') {
                    out.append(':');
                }
                out.append(Integer.toHexString(groups[k]));
            }
        }
        return out.toString();
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
