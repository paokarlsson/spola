package spola.http;

import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.TrustManager;

/**
 * The certificates an HTTPS server trusts for client certificates (mutual TLS, with
 * {@link ClientAuth#WANT} or {@link ClientAuth#NEED}): a client's chain must end in one of them.
 * Read and checked when created, like {@link TlsIdentity}.
 *
 * <pre>{@code
 * var tls = TlsOptions.pkcs12(Path.of("server.p12"), password)
 *         .withClientAuth(ClientAuth.NEED, TlsTrust.pem(Path.of("client-ca.pem")));
 * }</pre>
 *
 * <p>Revocation (CRL, OCSP) is not checked beyond what the JDK's PKIX trust manager does by
 * default (nothing).
 */
public final class TlsTrust {

    private final String description;
    private final List<X509Certificate> certificates;
    private final TrustManager[] trustManagers;

    private TlsTrust(String description, List<X509Certificate> certificates) {
        this.description = description;
        this.certificates = certificates;
        this.trustManagers = TlsMaterial.trustManagers(certificates);
    }

    /**
     * The trusted certificate entries of a PKCS#12 keystore (as {@code keytool -importcert}
     * writes them). Private key entries are not trusted.
     *
     * @param truststore the keystore file
     * @param password   the keystore password; copied and cleared after use, the caller's array is
     *                   unchanged
     * @return the trust
     * @throws TlsMaterialException if the file is missing or unreadable, the password is wrong or
     *                              the keystore holds no trusted certificate entry
     * @throws NullPointerException if an argument is {@code null}
     */
    public static TlsTrust pkcs12(Path truststore, char[] password) {
        Objects.requireNonNull(truststore, "truststore");
        var copy = Objects.requireNonNull(password, "password").clone();
        var what = "truststore";
        try {
            var store = TlsMaterial.loadPkcs12(truststore, copy, what);
            var certificates = new ArrayList<X509Certificate>();
            try {
                for (var alias : Collections.list(store.aliases())) {
                    if (store.isCertificateEntry(alias) && store.getCertificate(alias) instanceof X509Certificate x509) {
                        certificates.add(x509);
                    }
                }
            } catch (GeneralSecurityException e) {
                throw new TlsMaterialException(truststore, what + " " + truststore + " cannot be listed", e);
            }
            if (certificates.isEmpty()) {
                throw new TlsMaterialException(truststore, what + " " + truststore
                        + " holds no trusted certificate entry (import one with keytool -importcert)");
            }
            return new TlsTrust("PKCS#12 " + Syntax.escape(truststore.toString()), List.copyOf(certificates));
        } finally {
            Arrays.fill(copy, '\0');
        }
    }

    /**
     * The certificates of a PEM file, one or more {@code -----BEGIN CERTIFICATE-----} blocks.
     *
     * @param certificates the file
     * @return the trust
     * @throws TlsMaterialException if the file is missing or unreadable, is not well-formed PEM or
     *                              holds no certificate
     * @throws NullPointerException if {@code certificates} is {@code null}
     */
    public static TlsTrust pem(Path certificates) {
        Objects.requireNonNull(certificates, "certificates");
        var what = "trusted certificates";
        var list = TlsMaterial.certificates(certificates, what, Pem.read(certificates, what));
        return new TlsTrust("PEM " + Syntax.escape(certificates.toString()), list);
    }

    /**
     * The trusted certificates.
     *
     * @return the certificates, in the order of the file
     */
    public List<X509Certificate> certificates() {
        return certificates;
    }

    TrustManager[] trustManagers() {
        return trustManagers.clone();
    }

    /** Where the certificates come from (the file name). */
    @Override
    public String toString() {
        return description;
    }
}
