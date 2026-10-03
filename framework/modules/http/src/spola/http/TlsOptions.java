package spola.http;

import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;

/**
 * HTTPS for {@link HttpServer}, as a value for {@link HttpServerOptions#withTls(TlsOptions)}:
 * the server's key and certificates, the TLS versions, the cipher suites and client
 * certificates (mutual TLS). The TLS protocol itself is the JDK's ({@code javax.net.ssl}).
 *
 * <pre>{@code
 * // PKCS#12 (server.ssl.key-store=server.p12)
 * var tls = TlsOptions.pkcs12(Path.of("server.p12"), password);
 *
 * // PEM, for example from Let's Encrypt (server.ssl.certificate, server.ssl.certificate-private-key)
 * var tls = TlsOptions.pem(Path.of("/etc/letsencrypt/live/example.com/fullchain.pem"),
 *         Path.of("/etc/letsencrypt/live/example.com/privkey.pem"));
 *
 * // mutual TLS: clients need a certificate issued by client-ca.pem
 * var mtls = tls.withClientAuth(ClientAuth.NEED, TlsTrust.pem(Path.of("client-ca.pem")));
 * }</pre>
 *
 * <p>Key material is read and checked when the {@link TlsIdentity} (and {@link TlsTrust}) is
 * created, that is in {@link #pkcs12}, {@link #pem} and {@link TlsTrust#pem}: a missing file, a
 * wrong password or a key that does not belong to its certificate fails there with a
 * {@link TlsMaterialException}, not at the first connection.
 *
 * <p>The server always offers ALPN {@code http/1.1} and nothing else: a client that offers only
 * {@code h2} fails the handshake. The handshake runs on the connection's own virtual thread
 * within {@link HttpTimeouts#handshakeTimeout()}.
 *
 * @param identity     the server's key and certificate chain, or a ready {@link SSLContext}
 * @param clientAuth   whether clients are asked for a certificate
 * @param clientTrust  the certificates that client certificates must chain to; required with
 *                     {@link ClientAuth#WANT} and {@link ClientAuth#NEED}, empty with
 *                     {@link ClientAuth#NONE} and with an identity from an {@link SSLContext}
 *                     (whose own trust managers are used)
 * @param protocols    the TLS versions, {@code TLSv1.3} and/or {@code TLSv1.2}, in order of
 *                     preference; {@link #DEFAULT_PROTOCOLS} by default. Anything older or unknown
 *                     is refused.
 * @param cipherSuites the cipher suites in order of preference, or empty for the JDK's default
 *                     suites for the chosen protocols (the default, recommended)
 */
public record TlsOptions(TlsIdentity identity, ClientAuth clientAuth, Optional<TlsTrust> clientTrust,
                         List<String> protocols, List<String> cipherSuites) {

    /** TLS 1.3 and TLS 1.2, the only versions the server allows. */
    public static final List<String> DEFAULT_PROTOCOLS = List.of("TLSv1.3", "TLSv1.2");

    /** The protocol names that are older than TLS 1.2 (JSSE names). */
    private static final Set<String> TOO_OLD = Set.of("SSLv2Hello", "SSLv2", "SSLv3", "TLSv1", "TLSv1.1");

    /**
     * Parts of a cipher suite name that make it weak: no authentication, no encryption, broken
     * ciphers. This matches the JSSE standard names (IANA names, e.g.
     * {@code TLS_DH_anon_WITH_AES_128_CBC_SHA}, {@code TLS_RSA_WITH_NULL_SHA256}), compared in
     * upper case; a name the JDK does not know is refused later by {@code SSLContext} anyway.
     */
    private static final List<String> WEAK = List.of("_ANON_", "_NULL_", "_EXPORT", "_RC4_", "_DES_", "_DES40_",
            "_3DES_", "_MD5", "SCSV");

    /**
     * Checks the options.
     *
     * @throws NullPointerException     if a component or an element is {@code null}
     * @throws IllegalArgumentException if there is no protocol, a protocol is older than TLS 1.2 or
     *                                  unknown, a cipher suite is unknown to the JDK, weak, or
     *                                  none fits the protocols, a name is repeated, or
     *                                  {@code clientTrust} does not fit {@code clientAuth} and the
     *                                  identity (see {@link #clientTrust()})
     */
    public TlsOptions {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(clientAuth, "clientAuth");
        Objects.requireNonNull(clientTrust, "clientTrust");
        protocols = List.copyOf(protocols);
        cipherSuites = List.copyOf(cipherSuites);
        var supported = Supported.of(identity);
        checkProtocols(protocols, supported);
        checkCipherSuites(cipherSuites, protocols, supported);
        boolean ownContext = identity.context() != null;
        if (clientAuth == ClientAuth.NONE && clientTrust.isPresent()) {
            throw new IllegalArgumentException("clientTrust is only used with ClientAuth.WANT or NEED");
        }
        if (clientAuth != ClientAuth.NONE && !ownContext && clientTrust.isEmpty()) {
            throw new IllegalArgumentException("ClientAuth." + clientAuth + " needs a TlsTrust for the client"
                    + " certificates: withClientAuth(" + clientAuth + ", TlsTrust.pem(...))");
        }
        if (ownContext && clientTrust.isPresent()) {
            throw new IllegalArgumentException("an identity from an SSLContext checks client certificates with the"
                    + " context's own trust managers; give no TlsTrust");
        }
    }

    /**
     * HTTPS with the only private key of a PKCS#12 keystore; see {@link TlsIdentity#pkcs12(Path, char[])}.
     *
     * @param keystore the keystore file
     * @param password the keystore password; copied and cleared after use, the caller's array is
     *                 unchanged
     * @return the options, with the defaults for everything else
     * @throws TlsMaterialException if the keystore cannot be used (missing, wrong password, no or
     *                              several keys, key and certificate do not match)
     */
    public static TlsOptions pkcs12(Path keystore, char[] password) {
        return of(TlsIdentity.pkcs12(keystore, password));
    }

    /**
     * HTTPS with the private key {@code alias} of a PKCS#12 keystore; see
     * {@link TlsIdentity#pkcs12(Path, char[], String)}.
     *
     * @param keystore the keystore file
     * @param password the keystore password; copied and cleared after use
     * @param alias    the alias of the key entry
     * @return the options, with the defaults for everything else
     * @throws TlsMaterialException if the keystore cannot be used or has no key {@code alias}
     */
    public static TlsOptions pkcs12(Path keystore, char[] password, String alias) {
        return of(TlsIdentity.pkcs12(keystore, password, alias));
    }

    /**
     * HTTPS with a PEM certificate chain and an unencrypted PKCS#8 private key; see
     * {@link TlsIdentity#pem(Path, Path)}.
     *
     * @param certificateChain the certificates, the server's own first (Let's Encrypt's
     *                         {@code fullchain.pem})
     * @param privateKey       the key, {@code -----BEGIN PRIVATE KEY-----} (Let's Encrypt's
     *                         {@code privkey.pem})
     * @return the options, with the defaults for everything else
     * @throws TlsMaterialException if a file cannot be used
     */
    public static TlsOptions pem(Path certificateChain, Path privateKey) {
        return of(TlsIdentity.pem(certificateChain, privateKey));
    }

    /**
     * HTTPS with a ready, initialized {@link SSLContext}; the context's trust managers check
     * client certificates when {@link #withClientAuth(ClientAuth)} asks for them.
     *
     * @param context the context
     * @return the options, with the defaults for everything else
     * @throws IllegalArgumentException if the context is not initialized
     */
    public static TlsOptions of(SSLContext context) {
        return of(TlsIdentity.of(context));
    }

    /**
     * HTTPS with {@code identity}, {@link ClientAuth#NONE}, {@link #DEFAULT_PROTOCOLS} and the
     * JDK's default cipher suites.
     *
     * @param identity the server's key and certificates
     * @return the options
     */
    public static TlsOptions of(TlsIdentity identity) {
        return new TlsOptions(identity, ClientAuth.NONE, Optional.empty(), DEFAULT_PROTOCOLS, List.of());
    }

    /**
     * A copy with other protocols.
     *
     * @param protocols {@code TLSv1.3} and/or {@code TLSv1.2}, in order of preference
     * @return the copy
     * @throws IllegalArgumentException if a protocol is older than TLS 1.2 or unknown
     */
    public TlsOptions withProtocols(String... protocols) {
        return new TlsOptions(identity, clientAuth, clientTrust, List.of(protocols), cipherSuites);
    }

    /**
     * A copy with an explicit list of cipher suites, checked against those the JDK supports. With
     * no argument, the JDK's defaults.
     *
     * @param cipherSuites JSSE names such as {@code TLS_AES_128_GCM_SHA256}, in order of preference
     * @return the copy
     * @throws IllegalArgumentException if a suite is unknown or weak, or none fits the protocols
     */
    public TlsOptions withCipherSuites(String... cipherSuites) {
        return new TlsOptions(identity, clientAuth, clientTrust, protocols, List.of(cipherSuites));
    }

    /**
     * A copy that asks clients for certificates and checks them against {@code trust}.
     *
     * @param clientAuth {@link ClientAuth#WANT} or {@link ClientAuth#NEED}
     * @param trust      the certificates a client's chain must end in
     * @return the copy
     * @throws IllegalArgumentException if {@code clientAuth} is {@link ClientAuth#NONE}, or the
     *                                  identity is an {@link SSLContext}
     */
    public TlsOptions withClientAuth(ClientAuth clientAuth, TlsTrust trust) {
        return new TlsOptions(identity, clientAuth, Optional.of(Objects.requireNonNull(trust, "trust")), protocols,
                cipherSuites);
    }

    /**
     * A copy with {@code clientAuth} and no {@link TlsTrust}: {@link ClientAuth#NONE}, or, for an
     * identity from an {@link SSLContext}, any value (the context's trust managers check).
     *
     * @param clientAuth whether clients are asked for a certificate
     * @return the copy
     * @throws IllegalArgumentException if {@code clientAuth} needs a {@link TlsTrust}
     */
    public TlsOptions withClientAuth(ClientAuth clientAuth) {
        return new TlsOptions(identity, clientAuth, Optional.empty(), protocols, cipherSuites);
    }

    /** Where the key material comes from, the protocols, the suites and the client authentication; never a password. */
    @Override
    public String toString() {
        return "TlsOptions[identity=" + identity + ", protocols=" + protocols + ", cipherSuites="
                + (cipherSuites.isEmpty() ? "JDK default" : cipherSuites.toString()) + ", clientAuth=" + clientAuth
                + clientTrust.map(t -> ", clientTrust=" + t).orElse("") + "]";
    }

    // ---- checks -------------------------------------------------------------------------

    private static void checkProtocols(List<String> protocols, Supported supported) {
        if (protocols.isEmpty()) {
            throw new IllegalArgumentException("protocols is empty; use TLSv1.3 and/or TLSv1.2");
        }
        var seen = new HashSet<String>();
        for (var protocol : protocols) {
            if (TOO_OLD.contains(protocol)) {
                throw new IllegalArgumentException("protocol " + protocol + " is older than TLS 1.2 and not allowed;"
                        + " use TLSv1.3 and/or TLSv1.2");
            }
            if (!DEFAULT_PROTOCOLS.contains(protocol)) {
                throw new IllegalArgumentException("unknown protocol '" + Syntax.escape(protocol)
                        + "'; use TLSv1.3 and/or TLSv1.2");
            }
            if (!supported.protocols().contains(protocol)) {
                throw new IllegalArgumentException("protocol " + protocol + " is not supported by the SSLContext");
            }
            if (!seen.add(protocol)) {
                throw new IllegalArgumentException("protocol " + protocol + " is given twice");
            }
        }
    }

    private static void checkCipherSuites(List<String> suites, List<String> protocols, Supported supported) {
        if (suites.isEmpty()) {
            return;
        }
        var seen = new HashSet<String>();
        boolean fits = false;
        for (var suite : suites) {
            if (!supported.cipherSuites().contains(suite)) {
                throw new IllegalArgumentException("cipher suite '" + Syntax.escape(suite)
                        + "' is not supported by the JDK (use JSSE names such as TLS_AES_128_GCM_SHA256)");
            }
            var upper = suite.toUpperCase(Locale.ROOT);
            for (var weak : WEAK) {
                if (upper.contains(weak)) {
                    throw new IllegalArgumentException("cipher suite " + suite + " is weak and not allowed");
                }
            }
            if (!seen.add(suite)) {
                throw new IllegalArgumentException("cipher suite " + suite + " is given twice");
            }
            // JSSE/IANA standard names: TLS 1.3 suites name no key exchange (TLS_AES_128_GCM_SHA256),
            // TLS 1.2 suites always do (TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256)
            boolean tls13 = !suite.contains("_WITH_");
            fits |= protocols.contains(tls13 ? "TLSv1.3" : "TLSv1.2");
        }
        if (!fits) {
            throw new IllegalArgumentException("none of the cipher suites can be used with " + protocols
                    + " (TLS 1.3 suites are named TLS_AES_* or TLS_CHACHA20_*, TLS 1.2 suites TLS_ECDHE_*_WITH_*)");
        }
    }

    /** The protocols and cipher suites a context supports. */
    private record Supported(List<String> protocols, List<String> cipherSuites) {

        /** What the JDK's own TLS context supports; computed once. */
        private static final class Jdk {
            static final Supported SUPPORTED = compute();

            private static Supported compute() {
                try {
                    var context = SSLContext.getInstance("TLS");
                    context.init(new KeyManager[0], new TrustManager[0], null);
                    return from(context.getSupportedSSLParameters());
                } catch (GeneralSecurityException e) {
                    throw new IllegalStateException("the JDK has no TLS: " + LoggedThrowable.describe(e));
                }
            }
        }

        static Supported of(TlsIdentity identity) {
            var context = identity.context();
            return context == null ? Jdk.SUPPORTED : from(context.getSupportedSSLParameters());
        }

        private static Supported from(SSLParameters parameters) {
            return new Supported(List.of(parameters.getProtocols()), List.of(parameters.getCipherSuites()));
        }
    }
}
