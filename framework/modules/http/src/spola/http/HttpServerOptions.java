package spola.http;

import java.net.InetAddress;
import java.util.Objects;
import java.util.Optional;

/**
 * Where and how {@link HttpServer} listens, as a value.
 *
 * <pre>{@code
 * var options = HttpServerOptions.DEFAULT.withPort(8080)
 *         .withLimits(HttpLimits.DEFAULT.withMaxBodySize(1 << 20));
 *
 * // HTTPS: the key and certificates are read and checked here, not at the first connection
 * var https = HttpServerOptions.DEFAULT.withPort(8443)
 *         .withTls(TlsOptions.pkcs12(Path.of("server.p12"), password));
 * }</pre>
 *
 * <p>A server listens either with TLS or without it. A program that wants both (say, a
 * redirect from port 80 to 443) starts two servers.
 *
 * @param address  the local address to bind; {@link #DEFAULT} binds the loopback address, so a
 *                 server is not reachable from other machines unless you choose an address
 *                 (for example {@code InetAddress.getByName("0.0.0.0")} for every IPv4 address)
 * @param port     the port, from 0 to 65 535; 0 lets the operating system choose one, which
 *                 {@link HttpServer#port()} then reports
 * @param limits   the size and count limits
 * @param timeouts the timeouts
 * @param tls      HTTPS: the TLS settings, or empty for plain HTTP ({@link #DEFAULT})
 */
public record HttpServerOptions(InetAddress address, int port, HttpLimits limits, HttpTimeouts timeouts,
                                Optional<TlsOptions> tls) {

    /** Loopback address, port 8080, {@link HttpLimits#DEFAULT}, {@link HttpTimeouts#DEFAULT}, no TLS. */
    public static final HttpServerOptions DEFAULT = new HttpServerOptions(InetAddress.getLoopbackAddress(), 8080,
            HttpLimits.DEFAULT, HttpTimeouts.DEFAULT, Optional.empty());

    /**
     * Checks the options.
     *
     * @throws NullPointerException     if a component is {@code null}
     * @throws IllegalArgumentException if the port is outside 0–65 535
     */
    public HttpServerOptions {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(timeouts, "timeouts");
        Objects.requireNonNull(tls, "tls");
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535, was " + port);
        }
    }

    /**
     * Options for plain HTTP (no TLS).
     *
     * @param address  the local address to bind
     * @param port     the port; 0 lets the operating system choose
     * @param limits   the size and count limits
     * @param timeouts the timeouts
     * @throws NullPointerException     if a component is {@code null}
     * @throws IllegalArgumentException if the port is outside 0–65 535
     */
    public HttpServerOptions(InetAddress address, int port, HttpLimits limits, HttpTimeouts timeouts) {
        this(address, port, limits, timeouts, Optional.empty());
    }

    /**
     * A copy with another address.
     *
     * @param address the local address to bind
     * @return the copy
     */
    public HttpServerOptions withAddress(InetAddress address) {
        return new HttpServerOptions(address, port, limits, timeouts, tls);
    }

    /**
     * A copy with another port.
     *
     * @param port the port; 0 lets the operating system choose
     * @return the copy
     */
    public HttpServerOptions withPort(int port) {
        return new HttpServerOptions(address, port, limits, timeouts, tls);
    }

    /**
     * A copy with other limits.
     *
     * @param limits the size and count limits
     * @return the copy
     */
    public HttpServerOptions withLimits(HttpLimits limits) {
        return new HttpServerOptions(address, port, limits, timeouts, tls);
    }

    /**
     * A copy with other timeouts.
     *
     * @param timeouts the timeouts
     * @return the copy
     */
    public HttpServerOptions withTimeouts(HttpTimeouts timeouts) {
        return new HttpServerOptions(address, port, limits, timeouts, tls);
    }

    /**
     * A copy that serves HTTPS with {@code tls}.
     *
     * @param tls the TLS settings (key material, protocols, client authentication)
     * @return the copy
     */
    public HttpServerOptions withTls(TlsOptions tls) {
        return new HttpServerOptions(address, port, limits, timeouts, Optional.of(Objects.requireNonNull(tls, "tls")));
    }

    /**
     * A copy that serves plain HTTP.
     *
     * @return the copy
     */
    public HttpServerOptions withoutTls() {
        return new HttpServerOptions(address, port, limits, timeouts, Optional.empty());
    }
}
