package spola.http;

import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;

/**
 * The server's identity for TLS: its private key and certificate chain, read and checked when
 * the identity is created (never at the first connection), or a ready {@link SSLContext}.
 * Usually created through {@link TlsOptions#pkcs12}, {@link TlsOptions#pem} or
 * {@link TlsOptions#of}.
 *
 * <ul>
 *   <li>{@link #pkcs12(Path, char[])}: a PKCS#12 keystore ({@code .p12}/{@code .pfx}) with one
 *       private key, or {@link #pkcs12(Path, char[], String)} with an alias when it has several.</li>
 *   <li>{@link #pem(Path, Path)}: a PEM certificate chain (the server's certificate first, then
 *       its issuers, as in Let's Encrypt's {@code fullchain.pem}) and an unencrypted PKCS#8 key
 *       ({@code -----BEGIN PRIVATE KEY-----}, as in {@code privkey.pem}): RSA, EC, Ed25519 or
 *       Ed448. Encrypted keys and PKCS#1 ({@code BEGIN RSA PRIVATE KEY}) are refused with a
 *       message that says how to convert them.</li>
 *   <li>{@link #of(SSLContext)}: an initialized context built by the caller (JKS, HSM, a custom
 *       key manager); it also brings its own trust for client certificates.</li>
 * </ul>
 *
 * <p>Passwords are copied, used and cleared; the caller's array is never changed. Neither
 * {@link #toString()} nor any exception holds a password or key bytes.
 */
public final class TlsIdentity {

    private final String description;
    private final List<X509Certificate> chain;
    private final KeyManager[] keyManagers; // null for a context
    private final SSLContext context;       // null unless of(SSLContext)

    private TlsIdentity(String description, List<X509Certificate> chain, KeyManager[] keyManagers, SSLContext context) {
        this.description = description;
        this.chain = chain;
        this.keyManagers = keyManagers;
        this.context = context;
    }

    /**
     * The only private key of a PKCS#12 keystore, with its certificate chain.
     *
     * @param keystore the keystore file
     * @param password the keystore password (also the key's password, as keytool and openssl
     *                 write PKCS#12); copied and cleared after use, the caller's array is unchanged
     * @return the identity
     * @throws TlsMaterialException if the file is missing or unreadable, the password is wrong,
     *                              the keystore holds no private key or more than one, or the key
     *                              does not match its certificate
     * @throws NullPointerException if an argument is {@code null}
     */
    public static TlsIdentity pkcs12(Path keystore, char[] password) {
        return pkcs12(keystore, password, Optional.empty());
    }

    /**
     * The private key with {@code alias} in a PKCS#12 keystore, with its certificate chain.
     *
     * @param keystore the keystore file
     * @param password the keystore password; copied and cleared after use
     * @param alias    the alias of the key entry (the server certificate to use)
     * @return the identity
     * @throws TlsMaterialException if the file is missing or unreadable, the password is wrong,
     *                              there is no private key with that alias, or the key does not
     *                              match its certificate
     * @throws NullPointerException if an argument is {@code null}
     */
    public static TlsIdentity pkcs12(Path keystore, char[] password, String alias) {
        return pkcs12(keystore, password, Optional.of(Objects.requireNonNull(alias, "alias")));
    }

    private static TlsIdentity pkcs12(Path file, char[] password, Optional<String> alias) {
        Objects.requireNonNull(file, "keystore");
        var copy = Objects.requireNonNull(password, "password").clone();
        var what = "keystore";
        try {
            var store = TlsMaterial.loadPkcs12(file, copy, what);
            var keys = TlsMaterial.keyAliases(store, file);
            String chosen;
            if (alias.isPresent()) {
                if (!keys.contains(alias.get())) {
                    throw new TlsMaterialException(file, what + " " + file + " has no private key with the alias '"
                            + Syntax.escape(alias.get()) + "'" + (keys.isEmpty() ? " (it holds no private key)"
                            : "; private keys: " + TlsMaterial.quoted(keys)));
                }
                chosen = alias.get();
            } else if (keys.size() == 1) {
                chosen = keys.getFirst();
            } else if (keys.isEmpty()) {
                throw new TlsMaterialException(file, what + " " + file + " holds no private key (only certificates?"
                        + " a truststore is not a server identity)");
            } else {
                throw new TlsMaterialException(file, what + " " + file + " holds " + keys.size() + " private keys ("
                        + TlsMaterial.quoted(keys) + "); choose one with TlsOptions.pkcs12(keystore, password, alias)");
            }
            var key = privateKey(store, chosen, copy, file);
            var chain = chain(store, chosen, file);
            TlsMaterial.checkKeyMatches(key, chain.getFirst(), file, what);
            return new TlsIdentity("PKCS#12 " + Syntax.escape(file.toString()) + " (alias '" + Syntax.escape(chosen)
                    + "')", chain, TlsMaterial.keyManagers(key, chain), null);
        } finally {
            Arrays.fill(copy, '\0');
        }
    }

    private static PrivateKey privateKey(KeyStore store, String alias, char[] password, Path file) {
        try {
            if (store.getKey(alias, password) instanceof PrivateKey key) {
                return key;
            }
        } catch (GeneralSecurityException e) {
            throw new TlsMaterialException(file, "keystore " + file + ": the key '" + Syntax.escape(alias)
                    + "' cannot be read with the keystore password (a separate key password is not supported)", e);
        }
        throw new TlsMaterialException(file, "keystore " + file + ": the entry '" + Syntax.escape(alias)
                + "' is not a private key");
    }

    private static List<X509Certificate> chain(KeyStore store, String alias, Path file) {
        Certificate[] certificates;
        try {
            certificates = store.getCertificateChain(alias);
        } catch (GeneralSecurityException e) {
            throw new TlsMaterialException(file, "keystore " + file + ": the certificate chain of '"
                    + Syntax.escape(alias) + "' cannot be read", e);
        }
        if (certificates == null || certificates.length == 0) {
            throw new TlsMaterialException(file, "keystore " + file + ": the key '" + Syntax.escape(alias)
                    + "' has no certificate chain");
        }
        var chain = new ArrayList<X509Certificate>();
        for (var certificate : certificates) {
            if (!(certificate instanceof X509Certificate x509)) {
                throw new TlsMaterialException(file, "keystore " + file + ": the chain of '" + Syntax.escape(alias)
                        + "' holds a certificate that is not X.509");
            }
            chain.add(x509);
        }
        return List.copyOf(chain);
    }

    /**
     * A PEM certificate chain and an unencrypted PKCS#8 private key.
     *
     * @param certificateChain the certificates, the server's own first, each followed by its issuer
     *                         (for example Let's Encrypt's {@code fullchain.pem})
     * @param privateKey       the private key, {@code -----BEGIN PRIVATE KEY-----} (for example
     *                         Let's Encrypt's {@code privkey.pem})
     * @return the identity
     * @throws TlsMaterialException if a file is missing or unreadable, is not well-formed PEM,
     *                              holds no certificate, holds the chain in the wrong order, holds
     *                              an encrypted or PKCS#1 key, or the key does not belong to the
     *                              first certificate
     * @throws NullPointerException if an argument is {@code null}
     */
    public static TlsIdentity pem(Path certificateChain, Path privateKey) {
        Objects.requireNonNull(certificateChain, "certificateChain");
        Objects.requireNonNull(privateKey, "privateKey");
        var what = "certificate chain";
        var chain = TlsMaterial.certificates(certificateChain, what, Pem.read(certificateChain, what));
        TlsMaterial.checkChainOrder(chain, certificateChain, what);
        var key = TlsMaterial.privateKey(privateKey, Pem.read(privateKey, "private key"));
        TlsMaterial.checkKeyMatches(key, chain.getFirst(), privateKey, "private key");
        return new TlsIdentity("PEM " + Syntax.escape(certificateChain.toString()) + " with key "
                + Syntax.escape(privateKey.toString()), chain, TlsMaterial.keyManagers(key, chain), null);
    }

    /**
     * A ready {@link SSLContext}: its key managers choose the server certificate and its trust
     * managers check client certificates (with {@link ClientAuth#WANT} or {@link ClientAuth#NEED}).
     *
     * @param context an initialized context
     * @return the identity
     * @throws IllegalArgumentException if the context is not initialized
     * @throws NullPointerException     if {@code context} is {@code null}
     */
    public static TlsIdentity of(SSLContext context) {
        Objects.requireNonNull(context, "context");
        try {
            context.getServerSocketFactory(); // throws if the context is not initialized
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException("the SSLContext is not initialized (call init first)", e);
        }
        return new TlsIdentity("SSLContext " + Syntax.escape(context.getProtocol()) + " from "
                + Syntax.escape(context.getProvider().getName()), List.of(), null, context);
    }

    /**
     * The server's certificate chain, its own certificate first.
     *
     * @return the chain; empty for an identity from an {@link SSLContext}
     */
    public List<X509Certificate> certificateChain() {
        return chain;
    }

    /** The caller's context, or null when this identity holds key material. */
    SSLContext context() {
        return context;
    }

    /** The key managers for a new context, or null for an identity from a context. */
    KeyManager[] keyManagers() {
        return keyManagers == null ? null : keyManagers.clone();
    }

    /**
     * Where the identity comes from: the file names and the alias, or the context's protocol and
     * provider. Never a password or key bytes.
     */
    @Override
    public String toString() {
        return description;
    }
}
