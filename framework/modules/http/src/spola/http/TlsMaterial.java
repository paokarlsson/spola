package spola.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * Reading and checking key material for {@link TlsIdentity} and {@link TlsTrust}: PKCS#12
 * keystores through {@link KeyStore}, PEM through {@link Pem}, {@link CertificateFactory} and
 * {@link KeyFactory}. Every failure is a {@link TlsMaterialException} that names the file; none
 * holds a password or key bytes. Passwords are copies that the callers clear.
 */
final class TlsMaterial {

    /** How to turn other key formats into the unencrypted PKCS#8 this server reads. */
    static final String CONVERT_KEY = "convert it with: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem";

    /** The key algorithms a PEM key may have, tried in this order. EdDSA covers Ed25519 and Ed448. */
    private static final List<String> KEY_ALGORITHMS = List.of("RSA", "EC", "EdDSA");

    private TlsMaterial() {
    }

    // ---- PKCS#12 ------------------------------------------------------------------------

    /** Opens a PKCS#12 keystore with {@code password} (a copy the caller clears). */
    static KeyStore loadPkcs12(Path file, char[] password, String what) {
        var bytes = Pem.readFile(file, what);
        try {
            var store = KeyStore.getInstance("PKCS12");
            store.load(new ByteArrayInputStream(bytes), password);
            return store;
        } catch (IOException e) {
            if (e.getCause() instanceof UnrecoverableKeyException) {
                throw new TlsMaterialException(file, what + " " + file + " cannot be opened: the password is wrong", e);
            }
            throw new TlsMaterialException(file, what + " " + file + " cannot be opened as a PKCS#12 keystore"
                    + " (wrong password, damaged, or another format; a JKS keystore can be converted with"
                    + " keytool -importkeystore -deststoretype pkcs12)", e);
        } catch (GeneralSecurityException e) {
            throw new TlsMaterialException(file, what + " " + file + " cannot be opened as a PKCS#12 keystore", e);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /** The aliases of private key entries, in the keystore's order. */
    static List<String> keyAliases(KeyStore store, Path file) {
        try {
            var aliases = new ArrayList<String>();
            for (var alias : java.util.Collections.list(store.aliases())) {
                if (store.isKeyEntry(alias)) {
                    aliases.add(alias);
                }
            }
            return aliases;
        } catch (GeneralSecurityException e) {
            throw new TlsMaterialException(file, "keystore " + file + " cannot be listed", e);
        }
    }

    static String quoted(List<String> aliases) {
        return aliases.stream().map(a -> "'" + Syntax.escape(a) + "'").collect(Collectors.joining(", "));
    }

    // ---- PEM ----------------------------------------------------------------------------

    /** The certificates of PEM blocks, in order; every block must be a {@code CERTIFICATE}. */
    static List<X509Certificate> certificates(Path file, String what, List<Pem.Block> blocks) {
        var certificates = new ArrayList<X509Certificate>();
        try {
            for (var block : blocks) {
                if (block.label().endsWith("PRIVATE KEY")) {
                    throw new TlsMaterialException(file, what + " " + file + " holds a private key (block at line "
                            + block.line() + "); give the certificates and the key in separate files");
                }
                if (!block.label().equals("CERTIFICATE")) {
                    throw new TlsMaterialException(file, what + " " + file + ": the block at line " + block.line()
                            + " is " + Syntax.escape(block.label()) + ", expected CERTIFICATE");
                }
                certificates.add(certificate(file, what, block));
            }
        } finally {
            blocks.forEach(Pem.Block::clear);
        }
        if (certificates.isEmpty()) {
            throw new TlsMaterialException(file, what + " " + file + " holds no certificate"
                    + " (expected one or more -----BEGIN CERTIFICATE----- blocks)");
        }
        return List.copyOf(certificates);
    }

    private static X509Certificate certificate(Path file, String what, Pem.Block block) {
        try {
            var factory = CertificateFactory.getInstance("X.509");
            var certificate = factory.generateCertificate(new ByteArrayInputStream(block.der()));
            if (certificate instanceof X509Certificate x509) {
                return x509;
            }
        } catch (CertificateException e) { // certificates are public: the cause may stay
            throw new TlsMaterialException(file, what + " " + file + ": the certificate at line " + block.line()
                    + " is not a valid X.509 certificate", e);
        }
        throw new TlsMaterialException(file, what + " " + file + ": the certificate at line " + block.line()
                + " is not an X.509 certificate");
    }

    /**
     * The private key of a PEM file with exactly one {@code PRIVATE KEY} block (unencrypted
     * PKCS#8). The algorithm (RSA, EC, Ed25519/Ed448) is the one whose {@link KeyFactory}
     * accepts the key. No exception from the JDK is attached as a cause: key parsing is where key
     * bytes could end up in a message.
     */
    static PrivateKey privateKey(Path file, List<Pem.Block> blocks) {
        var what = "private key";
        try {
            if (blocks.isEmpty()) {
                throw new TlsMaterialException(file, what + " " + file
                        + " holds no PEM block (expected -----BEGIN PRIVATE KEY-----)");
            }
            if (blocks.size() > 1) {
                throw new TlsMaterialException(file, what + " " + file + " holds " + blocks.size()
                        + " PEM blocks (" + blocks.stream().map(b -> Syntax.escape(b.label())).collect(Collectors.joining(", "))
                        + "); expected exactly one PRIVATE KEY block");
            }
            var block = blocks.getFirst();
            switch (block.label()) {
                case "PRIVATE KEY" -> {
                    // below
                }
                case "ENCRYPTED PRIVATE KEY" -> throw new TlsMaterialException(file, what + " " + file
                        + " is encrypted (ENCRYPTED PRIVATE KEY), which is not supported; " + CONVERT_KEY);
                case "RSA PRIVATE KEY", "EC PRIVATE KEY", "DSA PRIVATE KEY" -> throw new TlsMaterialException(file,
                        what + " " + file + " is in the legacy " + block.label().substring(0, block.label().indexOf(' '))
                                + " format (" + block.label() + ", e.g. PKCS#1), which is not supported; " + CONVERT_KEY);
                default -> throw new TlsMaterialException(file, what + " " + file + ": the block at line "
                        + block.line() + " is " + Syntax.escape(block.label()) + ", expected PRIVATE KEY");
            }
            var spec = new PKCS8EncodedKeySpec(block.der());
            for (var algorithm : KEY_ALGORITHMS) {
                try {
                    return KeyFactory.getInstance(algorithm).generatePrivate(spec);
                } catch (GeneralSecurityException | RuntimeException e) {
                    // not this algorithm (or not PKCS#8 at all); try the next
                }
            }
            throw new TlsMaterialException(file, what + " " + file + " is not an unencrypted PKCS#8 RSA, EC, Ed25519"
                    + " or Ed448 private key");
        } finally {
            blocks.forEach(Pem.Block::clear);
        }
    }

    // ---- checks -------------------------------------------------------------------------

    /**
     * Checks that {@code key} belongs to the certificate: signs random bytes with the key and
     * verifies the signature with the certificate's public key.
     */
    static void checkKeyMatches(PrivateKey key, X509Certificate certificate, Path file, String what) {
        var data = new byte[32];
        new SecureRandom().nextBytes(data);
        boolean matches;
        try {
            var algorithm = signatureAlgorithm(key);
            var signer = Signature.getInstance(algorithm);
            signer.initSign(key);
            signer.update(data);
            var signature = signer.sign();
            var verifier = Signature.getInstance(algorithm);
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(data);
            matches = verifier.verify(signature);
        } catch (GeneralSecurityException | RuntimeException e) {
            matches = false;
        }
        if (!matches) {
            throw new TlsMaterialException(file, what + " " + file + ": the private key does not belong to the"
                    + " certificate " + Syntax.escape(certificate.getSubjectX500Principal().getName())
                    + " (key " + key.getAlgorithm() + ", certificate " + certificate.getPublicKey().getAlgorithm() + ")");
        }
    }

    private static String signatureAlgorithm(PrivateKey key) throws GeneralSecurityException {
        return switch (key.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            case "EdDSA", "Ed25519", "Ed448" -> "EdDSA";
            default -> throw new GeneralSecurityException("unsupported key algorithm");
        };
    }

    /**
     * Checks that each certificate of a chain is issued by the next one (by name), so that a
     * chain in the wrong order fails here and not in every client.
     */
    static void checkChainOrder(List<X509Certificate> chain, Path file, String what) {
        for (int i = 0; i + 1 < chain.size(); i++) {
            var issuer = chain.get(i).getIssuerX500Principal();
            var next = chain.get(i + 1).getSubjectX500Principal();
            if (!issuer.equals(next)) {
                throw new TlsMaterialException(file, what + " " + file + ": certificate " + (i + 2)
                        + " is not the issuer of certificate " + (i + 1) + "; the chain must start with the server's"
                        + " certificate, each one followed by its issuer");
            }
        }
    }

    // ---- JSSE managers ------------------------------------------------------------------

    /** Key managers that offer exactly {@code key} with {@code chain}. */
    static KeyManager[] keyManagers(PrivateKey key, List<X509Certificate> chain) {
        var password = randomPassword(); // protects the key inside this in-memory store only
        try {
            var store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry("server", key, password, chain.toArray(new X509Certificate[0]));
            // SunX509 copies the key out at init, so no handshake decrypts it again
            var factory = KeyManagerFactory.getInstance("SunX509");
            factory.init(store, password);
            return factory.getKeyManagers();
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("the JDK could not set up a key manager: " + LoggedThrowable.describe(e));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /** Trust managers (PKIX) that accept chains ending in one of {@code anchors}. */
    static TrustManager[] trustManagers(List<X509Certificate> anchors) {
        try {
            var store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            int i = 0;
            for (var anchor : anchors) {
                store.setCertificateEntry("anchor-" + i++, anchor);
            }
            var factory = TrustManagerFactory.getInstance("PKIX");
            factory.init(store);
            return factory.getTrustManagers();
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("the JDK could not set up a trust manager: " + LoggedThrowable.describe(e));
        }
    }

    private static char[] randomPassword() {
        var random = new SecureRandom();
        var password = new char[24];
        for (int i = 0; i < password.length; i++) {
            password[i] = (char) ('a' + random.nextInt(26));
        }
        return password;
    }
}
