package spola.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * The tests' certificate material, created once per test run in a temporary directory. The JDK
 * has no public API that issues X.509 certificates, so {@code keytool} (from {@code java.home})
 * does it, in two rounds of parallel processes: a CA and a self-signed "rogue" client, then the
 * certificates the CA signs ({@code -genkeypair -signer ca}). The {@link KeyStore} API then
 * splits the keystores and writes the PEM files ({@code getEncoded()} and Base64). Nothing of it
 * is in the repository, and no internal JDK class is used.
 */
final class TestPki {

    /** The password of every keystore the tests create. */
    static final String PASSWORD = "Pki-Test-Secret-4711";

    final Path dir;
    final KeyStore.PrivateKeyEntry ca;
    final KeyStore.PrivateKeyEntry serverRsa;
    final KeyStore.PrivateKeyEntry serverEc;
    final KeyStore.PrivateKeyEntry serverEd;
    final KeyStore.PrivateKeyEntry client;
    /** A server certificate from the CA for another name only (SAN other.example). */
    final KeyStore.PrivateKeyEntry otherName;
    final KeyStore.PrivateKeyEntry rogue;

    /** PKCS#12 with only the RSA server key (alias "server") and its chain. */
    final Path serverP12;
    /** PKCS#12 with two server keys, "rsa" and "ec". */
    final Path twoKeysP12;
    /** PKCS#12 with the CA as the only trusted certificate entry (no private key). */
    final Path caP12;
    final Path caPem;
    final Path rsaChainPem;
    final Path rsaKeyPem;
    final Path ecChainPem;
    final Path ecKeyPem;
    final Path edChainPem;
    final Path edKeyPem;

    private final TlsOptions serverOptions;

    private static TestPki instance;

    /** The material of this test run, created on first use. */
    static synchronized TestPki get() throws Exception {
        if (instance == null) {
            instance = new TestPki(Files.createTempDirectory("spola-http-tls-"));
        }
        return instance;
    }

    /** Deletes the material (end of the TLS suites). */
    static synchronized void delete() throws IOException {
        if (instance == null) {
            return;
        }
        try (var files = Files.walk(instance.dir)) {
            for (var file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
        instance = null;
    }

    private TestPki(Path dir) throws Exception {
        this.dir = dir;
        // round 1: the CA and a self-signed client nobody trusts
        run(List.of(
                genkeypair("ca.p12", "ca", "RSA", "CN=Spola Test CA", null, "-ext", "bc:c"),
                genkeypair("rogue.p12", "rogue", "EC", "CN=Rogue Client", null)));
        // round 2: certificates the CA issues, each in a copy of the CA's keystore
        for (var name : List.of("server-rsa.p12", "server-ec.p12", "server-ed.p12", "client.p12", "other.p12")) {
            Files.copy(dir.resolve("ca.p12"), dir.resolve(name));
        }
        var san = "san=ip:127.0.0.1,dns:localhost";
        run(List.of(
                genkeypair("server-rsa.p12", "server", "RSA", "CN=localhost", "ca", "-ext", san, "-ext", "eku=serverAuth"),
                genkeypair("server-ec.p12", "server", "EC", "CN=localhost", "ca", "-ext", san, "-ext", "eku=serverAuth"),
                genkeypair("server-ed.p12", "server", "Ed25519", "CN=localhost", "ca", "-ext", san, "-ext", "eku=serverAuth"),
                genkeypair("client.p12", "client", "EC", "CN=Test Client", "ca", "-ext", "eku=clientAuth"),
                genkeypair("other.p12", "server", "EC", "CN=other.example", "ca", "-ext", "san=dns:other.example",
                        "-ext", "eku=serverAuth")));
        ca = entry("ca.p12", "ca");
        rogue = entry("rogue.p12", "rogue");
        serverRsa = entry("server-rsa.p12", "server");
        serverEc = entry("server-ec.p12", "server");
        serverEd = entry("server-ed.p12", "server");
        client = entry("client.p12", "client");
        otherName = entry("other.p12", "server");

        serverP12 = keystore("server.p12", List.of("server"), List.of(serverRsa), false);
        twoKeysP12 = keystore("two-keys.p12", List.of("rsa", "ec"), List.of(serverRsa, serverEc), false);
        caP12 = keystore("ca-trust.p12", List.of(), List.of(), true);
        caPem = writePem("ca.pem", certificates(ca.getCertificate()));
        rsaChainPem = writePem("rsa-chain.pem", certificates(serverRsa.getCertificateChain()));
        rsaKeyPem = writePem("rsa-key.pem", "PRIVATE KEY", serverRsa.getPrivateKey().getEncoded());
        ecChainPem = writePem("ec-chain.pem", certificates(serverEc.getCertificateChain()));
        ecKeyPem = writePem("ec-key.pem", "PRIVATE KEY", serverEc.getPrivateKey().getEncoded());
        edChainPem = writePem("ed-chain.pem", certificates(serverEd.getCertificateChain()));
        edKeyPem = writePem("ed-key.pem", "PRIVATE KEY", serverEd.getPrivateKey().getEncoded());
        serverOptions = TlsOptions.pkcs12(serverP12, PASSWORD.toCharArray());
    }

    /** The server's TLS: the RSA key from {@link #serverP12}, the defaults otherwise. */
    TlsOptions serverOptions() {
        return serverOptions;
    }

    // ---- client contexts (one per use; nothing global) -----------------------------------

    /** A client that trusts only the test CA and has no certificate of its own. */
    SSLContext clientContext() throws Exception {
        return context(null, ca.getCertificate());
    }

    /** A client that trusts only the test CA and presents {@code certificate} when asked. */
    SSLContext clientContext(KeyStore.PrivateKeyEntry certificate) throws Exception {
        return context(certificate, ca.getCertificate());
    }

    /**
     * A client that trusts the test CA and always sends {@code certificate} when asked, even when
     * the server names CAs that did not issue it (the JDK's key managers would then send nothing).
     */
    SSLContext forcingClientContext(KeyStore.PrivateKeyEntry certificate) throws Exception {
        var chain = certificates(certificate.getCertificateChain()).toArray(new X509Certificate[0]);
        var key = certificate.getPrivateKey();
        var manager = new X509ExtendedKeyManager() {
            @Override
            public String[] getClientAliases(String keyType, Principal[] issuers) {
                return new String[] {"forced"};
            }

            @Override
            public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
                return "forced";
            }

            @Override
            public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
                return "forced";
            }

            @Override
            public String[] getServerAliases(String keyType, Principal[] issuers) {
                return null;
            }

            @Override
            public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                return null;
            }

            @Override
            public X509Certificate[] getCertificateChain(String alias) {
                return chain.clone();
            }

            @Override
            public PrivateKey getPrivateKey(String alias) {
                return key;
            }
        };
        var trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("trusted", ca.getCertificate());
        var trustManagers = TrustManagerFactory.getInstance("PKIX");
        trustManagers.init(trust);
        var context = SSLContext.getInstance("TLS");
        context.init(new KeyManager[] {manager}, trustManagers.getTrustManagers(), null);
        return context;
    }

    /** A client that trusts only the rogue certificate, so not the server. */
    SSLContext untrustingContext() throws Exception {
        return context(null, rogue.getCertificate());
    }

    private static SSLContext context(KeyStore.PrivateKeyEntry key, Certificate trusted) throws Exception {
        var trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("trusted", trusted);
        var trustManagers = TrustManagerFactory.getInstance("PKIX");
        trustManagers.init(trust);
        KeyManager[] keyManagers = null;
        if (key != null) {
            var keys = KeyStore.getInstance("PKCS12");
            keys.load(null, null);
            keys.setEntry("client", key, new KeyStore.PasswordProtection(PASSWORD.toCharArray()));
            var factory = KeyManagerFactory.getInstance("SunX509");
            factory.init(keys, PASSWORD.toCharArray());
            keyManagers = factory.getKeyManagers();
        }
        var context = SSLContext.getInstance("TLS");
        context.init(keyManagers, trustManagers.getTrustManagers(), null);
        return context;
    }

    // ---- files ----------------------------------------------------------------------------

    /** A new file in the material's directory with {@code text}. */
    Path write(String name, String text) throws IOException {
        return Files.writeString(dir.resolve(name), text, StandardCharsets.US_ASCII);
    }

    /** A new file in the material's directory with {@code bytes}. */
    Path write(String name, byte[] bytes) throws IOException {
        return Files.write(dir.resolve(name), bytes);
    }

    /** One PEM block, Base64 in lines of 64. */
    static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }

    Path writePem(String name, String label, byte[] der) throws IOException {
        return write(name, pem(label, der));
    }

    private Path writePem(String name, List<X509Certificate> certificates) throws Exception {
        var text = new StringBuilder();
        for (var certificate : certificates) {
            text.append(pem("CERTIFICATE", certificate.getEncoded()));
        }
        return write(name, text.toString());
    }

    static List<X509Certificate> certificates(Certificate... certificates) {
        var list = new ArrayList<X509Certificate>();
        for (var certificate : certificates) {
            list.add((X509Certificate) certificate);
        }
        return list;
    }

    // ---- keytool and KeyStore ---------------------------------------------------------------

    private List<String> genkeypair(String keystore, String alias, String algorithm, String dname, String signer,
                                    String... extra) {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-noprompt", "-keystore", dir.resolve(keystore).toString(), "-storetype", "PKCS12",
                "-storepass", PASSWORD, "-keypass", PASSWORD, "-alias", alias, "-keyalg", algorithm,
                "-dname", dname, "-validity", "2"));
        switch (algorithm) {
            case "RSA" -> command.addAll(List.of("-keysize", "2048"));
            case "EC" -> command.addAll(List.of("-groupname", "secp256r1"));
            default -> {
                // Ed25519 has one size
            }
        }
        if (signer != null) {
            command.addAll(List.of("-signer", signer));
        }
        command.addAll(List.of(extra));
        return command;
    }

    /** Runs the commands in parallel and waits for all; any failure fails with the tool's output. */
    private static void run(List<List<String>> commands) throws Exception {
        var processes = new ArrayList<Process>();
        for (var command : commands) {
            processes.add(new ProcessBuilder(command).redirectErrorStream(true).start());
        }
        for (int i = 0; i < processes.size(); i++) {
            var process = processes.get(i);
            process.getOutputStream().close();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                throw new IllegalStateException("keytool failed: " + commands.get(i) + "\n" + output);
            }
        }
    }

    private KeyStore.PrivateKeyEntry entry(String keystore, String alias) throws Exception {
        var store = KeyStore.getInstance(dir.resolve(keystore).toFile(), PASSWORD.toCharArray());
        return (KeyStore.PrivateKeyEntry) store.getEntry(alias, new KeyStore.PasswordProtection(PASSWORD.toCharArray()));
    }

    /** A PKCS#12 file with the key entries under the aliases, and the CA as trusted entry if asked. */
    private Path keystore(String name, List<String> aliases, List<KeyStore.PrivateKeyEntry> entries, boolean trustCa)
            throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        for (int i = 0; i < aliases.size(); i++) {
            store.setEntry(aliases.get(i), entries.get(i), new KeyStore.PasswordProtection(PASSWORD.toCharArray()));
        }
        if (trustCa) {
            store.setCertificateEntry("ca", ca.getCertificate());
        }
        var file = dir.resolve(name);
        try (var out = Files.newOutputStream(file)) {
            store.store(out, PASSWORD.toCharArray());
        }
        return file;
    }
}
