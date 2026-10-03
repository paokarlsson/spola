package spola.http;

/**
 * Whether an HTTPS server asks clients for a certificate (mutual TLS). The certificates are
 * checked against the {@link TlsTrust} given with {@link TlsOptions#withClientAuth(ClientAuth, TlsTrust)};
 * the handler sees the chain in {@link Request#tls()}.
 */
public enum ClientAuth {
    /** No client certificate is requested (the default). */
    NONE,
    /**
     * A client certificate is requested but not required: a client without one is served, with an
     * empty {@link TlsInfo#clientCertificates()}. A certificate the trust does not accept still
     * fails the handshake.
     */
    WANT,
    /** A client certificate is required: the handshake fails without a trusted one. */
    NEED
}
