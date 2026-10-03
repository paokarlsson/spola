package spola.http;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/**
 * What the TLS handshake of a connection negotiated, as seen by the handler through
 * {@link Request#tls()}.
 *
 * @param protocol           the protocol, {@code TLSv1.3} or {@code TLSv1.2}
 * @param cipherSuite        the cipher suite, for example {@code TLS_AES_256_GCM_SHA384}
 * @param clientCertificates the client's certificate chain, the client's own certificate first;
 *                           empty when the client sent none (always with {@link ClientAuth#NONE})
 */
public record TlsInfo(String protocol, String cipherSuite, List<X509Certificate> clientCertificates) {

    /**
     * Checks and copies the components.
     *
     * @throws NullPointerException if a component or a certificate is {@code null}
     */
    public TlsInfo {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(cipherSuite, "cipherSuite");
        clientCertificates = List.copyOf(clientCertificates);
    }

    /** The negotiated values of {@code session}. */
    static TlsInfo of(SSLSession session) {
        var chain = new ArrayList<X509Certificate>();
        try {
            for (Certificate certificate : session.getPeerCertificates()) {
                if (certificate instanceof X509Certificate x509) {
                    chain.add(x509);
                }
            }
        } catch (SSLPeerUnverifiedException e) {
            // the client sent no certificate
        }
        return new TlsInfo(session.getProtocol(), session.getCipherSuite(), chain);
    }

    /** Protocol, cipher suite and the subject of the client's certificate, if any. */
    @Override
    public String toString() {
        return "TlsInfo[" + protocol + ", " + cipherSuite + ", client="
                + (clientCertificates.isEmpty() ? "none"
                        : Syntax.escape(clientCertificates.getFirst().getSubjectX500Principal().getName())) + "]";
    }
}
