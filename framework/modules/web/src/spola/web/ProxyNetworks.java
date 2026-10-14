package spola.web;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** The networks of {@link TrustedProxies#of} and {@link TrustedProxies#privateNetworks()}: immutable, thread-safe. */
final class ProxyNetworks implements TrustedProxies {

    static final ProxyNetworks PRIVATE = of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8",
            "::1/128", "fc00::/7");

    /** One network: its address bytes (4 or 16, zero after the prefix), the prefix length and the entry. */
    private static final class Network {
        private final byte[] bytes;
        private final int prefix;
        private final String entry;

        Network(byte[] bytes, int prefix, String entry) {
            this.bytes = bytes;
            this.prefix = prefix;
            this.entry = entry;
        }

        /** Whether {@code address} (4 or 16 bytes) is in this network. */
        boolean contains(byte[] address) {
            if (address.length != bytes.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (address[i] != bytes[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (address[full] & mask) == (bytes[full] & mask);
        }
    }

    private final List<Network> networks;

    private ProxyNetworks(List<Network> networks) {
        this.networks = networks;
    }

    /** See {@link TrustedProxies#of}. */
    static ProxyNetworks of(String... entries) {
        Objects.requireNonNull(entries, "networks");
        var copy = entries.clone();
        for (var entry : copy) {
            Objects.requireNonNull(entry, "a network is null");
        }
        if (copy.length == 0) {
            throw new IllegalArgumentException("TrustedProxies.of needs at least one network, such as \"172.18.0.5\""
                    + " or \"10.0.0.0/8\"");
        }
        var problems = new ArrayList<String>();
        var networks = new ArrayList<Network>(copy.length);
        for (var entry : copy) {
            var network = parse(entry, problems);
            if (network != null) {
                networks.add(network);
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", problems));
        }
        return new ProxyNetworks(List.copyOf(networks));
    }

    /** {@code entry} as a network, or {@code null} after adding the reason to {@code problems}. */
    private static Network parse(String entry, List<String> problems) {
        var quoted = "\"" + Text.escape(entry, 100) + "\"";
        int slash = entry.indexOf('/');
        var literal = slash < 0 ? entry : entry.substring(0, slash);
        var address = IpLiteral.address(literal);
        if (address == null) {
            problems.add("trusted proxy " + quoted + " is not an IP address with an optional /prefix (an IP literal"
                    + " such as 10.0.0.0/8 or fc00::/7; no host name, brackets, zone, port or spaces)");
            return null;
        }
        if (literal.indexOf(':') >= 0 && address instanceof Inet4Address) {
            problems.add("trusted proxy " + quoted + " is an IPv4-mapped IPv6 address; write an IPv4 network as IPv4,"
                    + " such as 10.0.0.0/8");
            return null;
        }
        var bytes = address.getAddress();
        int max = bytes.length * 8;
        int prefix = slash < 0 ? max : prefixLength(entry.substring(slash + 1), max);
        if (prefix < 0) {
            problems.add("trusted proxy " + quoted + " has an invalid prefix length: decimal 0 to " + max
                    + " without a sign or a leading zero");
            return null;
        }
        var network = bytes.clone();
        for (int bit = prefix; bit < max; bit++) {
            network[bit / 8] = (byte) (network[bit / 8] & ~(0x80 >>> (bit % 8)));
        }
        if (!Arrays.equals(network, bytes)) {
            problems.add("trusted proxy " + quoted + " has bits set after the first " + prefix + " bits; did you mean \""
                    + IpLiteral.format(network) + "/" + prefix + "\"?");
            return null;
        }
        return new Network(bytes, prefix, entry);
    }

    /** The decimal prefix length 0 to {@code max} without sign or leading zero, or -1. */
    private static int prefixLength(String text, int max) {
        if (text.isEmpty() || text.length() > 3 || (text.length() > 1 && text.charAt(0) == '0')) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value <= max ? value : -1;
    }

    @Override
    public boolean trusts(InetAddress address) {
        Objects.requireNonNull(address, "address");
        // 4 bytes for an Inet4Address and 16 for an Inet6Address (without its zone), so a network
        // matches only addresses of its own family
        var bytes = address.getAddress();
        for (var network : networks) {
            if (network.contains(bytes)) {
                return true;
            }
        }
        return false;
    }

    /** {@code TrustedProxies[entry, ...]}, the entries as given, in order. */
    @Override
    public String toString() {
        var out = new StringBuilder("TrustedProxies[");
        for (int i = 0; i < networks.size(); i++) {
            out.append(i == 0 ? "" : ", ").append(networks.get(i).entry);
        }
        return out.append(']').toString();
    }
}
