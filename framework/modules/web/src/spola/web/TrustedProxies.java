package spola.web;

import java.net.InetAddress;

/**
 * Which addresses belong to reverse proxies whose {@code X-Forwarded-Proto} and
 * {@code X-Forwarded-For} {@link ForwardedHeaders} believes, the counterpart of
 * {@code internal-proxies} of Tomcat's {@code RemoteIpValve}
 * ({@code server.tomcat.remoteip.internal-proxies} in Spring Boot). The same set decides both
 * whether the direct sender is trusted and which addresses in {@code X-Forwarded-For} are
 * skipped as proxies (see {@link ForwardedHeaders}).
 *
 * <pre>{@code
 * TrustedProxies.privateNetworks()                 // 10/8, 172.16/12, 192.168/16, 127/8, ::1, fc00::/7
 * TrustedProxies.of("172.18.0.5")                  // only the proxy's address
 * TrustedProxies.of("10.0.0.0/8", "fd00::/8")      // networks
 * address -> false                                 // nobody: the headers are never read
 * }</pre>
 *
 * <p><b>Networks.</b> An IPv4 network matches only an {@code Inet4Address} and an IPv6 network
 * only an {@code Inet6Address}; a zone on the address does not count. An IPv4-mapped literal in
 * {@code X-Forwarded-For} ({@code ::ffff:203.0.113.7}) is an {@code Inet4Address} (as the JDK
 * makes it) and is tested against the IPv4 networks.
 *
 * <p>The implementations of {@link #privateNetworks()} and {@link #of} are immutable and
 * thread-safe. A lambda must be thread-safe too: {@link #trusts} is called concurrently. An
 * exception from {@code trusts} goes to the router's exception mappers like anything else a
 * filter throws (500 without one), and the request goes no further.
 */
@FunctionalInterface
public interface TrustedProxies {

    /**
     * Whether {@code address} belongs to a trusted proxy.
     *
     * @param address the address: the direct sender, or an element of {@code X-Forwarded-For}
     * @return {@code true} if it is trusted
     * @throws NullPointerException if {@code address} is {@code null} (the implementations of
     *                              {@link #privateNetworks()} and {@link #of})
     */
    boolean trusts(InetAddress address);

    /**
     * The private networks: {@code of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
     * "127.0.0.0/8", "::1/128", "fc00::/7")}, that is RFC 1918, loopback and IPv6 unique local
     * addresses. Its {@code toString()} is
     * {@code TrustedProxies[10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 127.0.0.0/8, ::1/128, fc00::/7]}.
     *
     * <p>Every address on those networks is trusted, not just the proxy's. In a Docker network
     * every container on it, and with Docker's userland proxy (and in Docker Desktop) a direct
     * connection to a published port can come from the bridge's gateway (such as
     * {@code 172.17.0.1}): such a sender can set the headers to anything. A client on such a
     * network that goes through the proxy is skipped as a proxy too, so what it writes to the
     * left of its own address decides. Publish only the proxy's port, and trust only the
     * proxy's address with {@link #of} when clients can be on those networks.
     *
     * <p>Tomcat's default {@code internal-proxies} also trusts link-local addresses
     * ({@code 169.254.0.0/16}, {@code fe80::/10}) and {@code 100.64.0.0/10}; this set does not.
     *
     * @return the set, always the same instance
     */
    static TrustedProxies privateNetworks() {
        return ProxyNetworks.PRIVATE;
    }

    /**
     * The given networks. Each entry is an IP literal, optionally followed by {@code /} and a
     * prefix length; without one it is a single address ({@code /32} or {@code /128}).
     *
     * <ul>
     *   <li><b>The literal</b> is strict. IPv4 is four decimal parts separated by {@code .},
     *       each 1 to 3 ASCII digits, 0 to 255, without a leading zero (except {@code 0}):
     *       {@code 127.1}, {@code 2130706433}, {@code 1.2.3}, {@code 010.0.0.1} and
     *       {@code 0x7f.0.0.1} are refused. IPv6 is the text form of RFC 4291 §2.2: groups of 1
     *       to 4 hex digits in either case, at most one {@code ::}, and optionally an IPv4 part
     *       instead of the last two groups. No host names, brackets, zones ({@code %}), ports or
     *       whitespace. Nothing is looked up.</li>
     *   <li><b>The prefix length</b> is decimal without a leading zero (except {@code 0}),
     *       0 to 32 for IPv4 and 0 to 128 for IPv6, with no sign.</li>
     *   <li><b>The bits after the prefix must be zero</b>: {@code 10.0.0.1/8} is refused and the
     *       message suggests {@code 10.0.0.0/8}, so that a typo shows at startup.</li>
     *   <li><b>No IPv4-mapped entries</b> ({@code ::ffff:10.0.0.0/104}): write IPv4 networks as
     *       IPv4.</li>
     * </ul>
     *
     * <p>The array is copied. {@code toString()} lists the entries as given, in order, such as
     * {@code TrustedProxies[10.0.0.0/8, ::1/128]}.
     *
     * @param networks the networks, at least one
     * @return the set
     * @throws NullPointerException     if {@code networks} or an entry is {@code null}
     * @throws IllegalArgumentException if there is no entry, or with one line per invalid entry,
     *                                  each quoting the entry (configuration, not a secret)
     */
    static TrustedProxies of(String... networks) {
        return ProxyNetworks.of(networks);
    }
}
