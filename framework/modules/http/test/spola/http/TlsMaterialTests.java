package spola.http;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import javax.net.ssl.SSLContext;
import spola.test.Suite;

/**
 * Reading and checking key material when {@link TlsOptions} is created: a table of bad
 * material, each with a clear {@link TlsMaterialException} that names the file and holds neither
 * a password nor key bytes; password handling; and the checks of the options themselves.
 */
final class TlsMaterialTests {

    private TlsMaterialTests() {
    }

    private static final String WRONG_PASSWORD = "Wrong-Secret-0815";

    /**
     * The names of the cases, known before the material exists (the suite is built before its
     * fixture creates the PKI); {@link #cases()} builds them in this order.
     */
    static final List<String> NAMES = List.of("missing keystore file", "missing PEM key file",
            "wrong keystore password", "unknown alias", "several keys and no alias", "keystore without a private key",
            "a file that is not a PKCS#12 keystore", "PEM key (EC) that does not belong to the certificate (RSA)",
            "PEM key (EC) that does not belong to the certificate (EC)", "encrypted PKCS#8 (ENCRYPTED PRIVATE KEY)",
            "PKCS#1 (RSA PRIVATE KEY)", "SEC1 (EC PRIVATE KEY)", "PEM key without END line",
            "PEM certificate chain without END line", "END line with another label", "invalid Base64 character",
            "invalid Base64 length", "legacy encrypted PEM header", "empty certificate chain (text only)",
            "empty certificate file", "certificate chain that is not PEM (DER)", "key file that is not PEM (DER key)",
            "key file that is not PEM (text)", "the key file given as the certificate chain",
            "certificate chain in the wrong order", "certificate block that is not a certificate",
            "key block that is not a key", "two keys in the key file", "trust: wrong truststore password",
            "trust: truststore without trusted certificates", "trust: PEM without certificates");

    /** What creates the material. */
    interface Create {
        Object create() throws Exception;
    }

    /**
     * A bad piece of material.
     *
     * @param name     what is wrong
     * @param file     the file the message must name (its file name)
     * @param expected a part of the message
     * @param create   creates the options (and must throw)
     */
    record Bad(String name, String file, String expected, Create create) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static List<Bad> cases;

    /** The case named {@code name}; the files of all cases are written on first use (the PKI exists by then). */
    private static synchronized Bad named(String name) throws Exception {
        if (cases == null) {
            cases = cases();
        }
        return cases.stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no case " + name));
    }

    private static List<Bad> cases() throws Exception {
        var pki = TestPki.get();
        var password = TestPki.PASSWORD;
        var rsaKey = pki.serverRsa.getPrivateKey().getEncoded();
        var rsaKeyPem = TestPki.pem("PRIVATE KEY", rsaKey);
        var rsaChain = Files.readString(pki.rsaChainPem);
        var lines = rsaKeyPem.split("\n");
        var noEnd = String.join("\n", Arrays.copyOf(lines, lines.length - 1)) + "\n";
        var badChar = rsaKeyPem.replaceFirst("\n([A-Za-z0-9+/]{10})[A-Za-z0-9+/]", "\n$1*");
        var badLength = rsaKeyPem.replaceFirst("\n([A-Za-z0-9+/]{10})[A-Za-z0-9+/]", "\n$1");
        var cases = new ArrayList<Bad>();
        cases.add(new Bad("missing keystore file", "missing.p12", "does not exist",
                () -> TlsOptions.pkcs12(pki.dir.resolve("missing.p12"), password.toCharArray())));
        cases.add(new Bad("missing PEM key file", "missing-key.pem", "does not exist",
                () -> TlsOptions.pem(pki.rsaChainPem, pki.dir.resolve("missing-key.pem"))));
        cases.add(new Bad("wrong keystore password", "server.p12", "password is wrong",
                () -> TlsOptions.pkcs12(pki.serverP12, WRONG_PASSWORD.toCharArray())));
        cases.add(new Bad("unknown alias", "two-keys.p12", "no private key with the alias 'nope'",
                () -> TlsOptions.pkcs12(pki.twoKeysP12, password.toCharArray(), "nope")));
        cases.add(new Bad("several keys and no alias", "two-keys.p12", "holds 2 private keys",
                () -> TlsOptions.pkcs12(pki.twoKeysP12, password.toCharArray())));
        cases.add(new Bad("keystore without a private key", "ca-trust.p12", "holds no private key",
                () -> TlsOptions.pkcs12(pki.caP12, password.toCharArray())));
        cases.add(new Bad("a file that is not a PKCS#12 keystore", "rsa-chain.pem", "cannot be opened as a PKCS#12",
                () -> TlsOptions.pkcs12(pki.rsaChainPem, password.toCharArray())));
        cases.add(new Bad("PEM key (EC) that does not belong to the certificate (RSA)", "ec-key.pem", "does not belong",
                () -> TlsOptions.pem(pki.rsaChainPem, pki.ecKeyPem)));
        cases.add(new Bad("PEM key (EC) that does not belong to the certificate (EC)", "client-key.pem",
                "does not belong", () -> TlsOptions.pem(pki.ecChainPem,
                pki.writePem("client-key.pem", "PRIVATE KEY", pki.client.getPrivateKey().getEncoded()))));
        cases.add(new Bad("encrypted PKCS#8 (ENCRYPTED PRIVATE KEY)", "encrypted.pem", "openssl pkcs8 -topk8 -nocrypt",
                () -> TlsOptions.pem(pki.rsaChainPem, pki.writePem("encrypted.pem", "ENCRYPTED PRIVATE KEY",
                        encrypt(rsaKey, password)))));
        cases.add(new Bad("PKCS#1 (RSA PRIVATE KEY)", "pkcs1.pem", "openssl pkcs8 -topk8 -nocrypt",
                () -> TlsOptions.pem(pki.rsaChainPem, pki.writePem("pkcs1.pem", "RSA PRIVATE KEY", pkcs1(rsaKey)))));
        cases.add(new Bad("SEC1 (EC PRIVATE KEY)", "sec1.pem", "openssl pkcs8 -topk8 -nocrypt",
                () -> TlsOptions.pem(pki.ecChainPem, pki.writePem("sec1.pem", "EC PRIVATE KEY",
                        pki.serverEc.getPrivateKey().getEncoded()))));
        cases.add(new Bad("PEM key without END line", "no-end.pem", "has no END line",
                () -> TlsOptions.pem(pki.rsaChainPem, pki.write("no-end.pem", noEnd))));
        cases.add(new Bad("PEM certificate chain without END line", "chain-no-end.pem", "has no END line",
                () -> TlsOptions.pem(pki.write("chain-no-end.pem", rsaChain.substring(0,
                        rsaChain.lastIndexOf("-----END"))), pki.rsaKeyPem)));
        cases.add(new Bad("END line with another label", "other-end.pem", "should be -----END PRIVATE KEY-----",
                () -> TlsOptions.pem(pki.rsaChainPem, pki.write("other-end.pem",
                        rsaKeyPem.replace("END PRIVATE KEY", "END CERTIFICATE")))));
        cases.add(new Bad("invalid Base64 character", "bad-char.pem", "is not Base64",
                () -> TlsOptions.pem(pki.rsaChainPem, pki.write("bad-char.pem", badChar))));
        cases.add(new Bad("invalid Base64 length", "bad-length.pem", "is not valid Base64",
                () -> TlsOptions.pem(pki.rsaChainPem, pki.write("bad-length.pem", badLength))));
        cases.add(new Bad("legacy encrypted PEM header", "proc-type.pem", "Proc-Type",
                () -> TlsOptions.pem(pki.rsaChainPem, pki.write("proc-type.pem", rsaKeyPem.replace(
                        "-----BEGIN PRIVATE KEY-----\n", "-----BEGIN PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\n")))));
        cases.add(new Bad("empty certificate chain (text only)", "empty-chain.pem", "holds no certificate",
                () -> TlsOptions.pem(pki.write("empty-chain.pem", "no certificates in here\n"), pki.rsaKeyPem)));
        cases.add(new Bad("empty certificate file", "empty.pem", "is empty",
                () -> TlsOptions.pem(pki.write("empty.pem", ""), pki.rsaKeyPem)));
        cases.add(new Bad("certificate chain that is not PEM (DER)", "chain.der", "not ASCII",
                () -> TlsOptions.pem(pki.write("chain.der", pki.serverRsa.getCertificate().getEncoded()), pki.rsaKeyPem)));
        cases.add(new Bad("key file that is not PEM (DER key)", "key.der", "not ASCII",
                () -> TlsOptions.pem(pki.rsaChainPem, pki.write("key.der", rsaKey))));
        cases.add(new Bad("key file that is not PEM (text)", "key.txt", "holds no PEM block",
                () -> TlsOptions.pem(pki.rsaChainPem, pki.write("key.txt", "this is not a key\n"))));
        cases.add(new Bad("the key file given as the certificate chain", "rsa-key.pem", "holds a private key",
                () -> TlsOptions.pem(pki.rsaKeyPem, pki.rsaKeyPem)));
        cases.add(new Bad("certificate chain in the wrong order", "reversed.pem", "is not the issuer",
                () -> TlsOptions.pem(pki.write("reversed.pem", TestPki.pem("CERTIFICATE",
                        pki.ca.getCertificate().getEncoded()) + TestPki.pem("CERTIFICATE",
                        pki.serverRsa.getCertificate().getEncoded())), pki.rsaKeyPem)));
        cases.add(new Bad("certificate block that is not a certificate", "garbage-cert.pem", "not a valid X.509",
                () -> TlsOptions.pem(pki.writePem("garbage-cert.pem", "CERTIFICATE", new byte[] {1, 2, 3, 4}),
                        pki.rsaKeyPem)));
        cases.add(new Bad("key block that is not a key", "garbage-key.pem", "not an unencrypted PKCS#8",
                () -> TlsOptions.pem(pki.rsaChainPem, pki.writePem("garbage-key.pem", "PRIVATE KEY",
                        Arrays.copyOf(rsaKey, rsaKey.length / 2)))));
        cases.add(new Bad("two keys in the key file", "two-keys.pem", "expected exactly one PRIVATE KEY",
                () -> TlsOptions.pem(pki.rsaChainPem, pki.write("two-keys.pem", rsaKeyPem + rsaKeyPem))));
        cases.add(new Bad("trust: wrong truststore password", "ca-trust.p12", "password is wrong",
                () -> TlsTrust.pkcs12(pki.caP12, WRONG_PASSWORD.toCharArray())));
        cases.add(new Bad("trust: truststore without trusted certificates", "server.p12",
                "holds no trusted certificate entry", () -> TlsTrust.pkcs12(pki.serverP12, password.toCharArray())));
        cases.add(new Bad("trust: PEM without certificates", "empty-trust.pem", "holds no certificate",
                () -> TlsTrust.pem(pki.write("empty-trust.pem", "nothing\n"))));
        return cases;
    }

    /** {@code key} as an EncryptedPrivateKeyInfo (PKCS#8 encrypted with a PKCS#12 PBE scheme). */
    private static byte[] encrypt(byte[] key, String password) throws Exception {
        var algorithm = "PBEWithSHA1AndDESede";
        var salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        var parameters = new PBEParameterSpec(salt, 10_000);
        var secret = SecretKeyFactory.getInstance(algorithm).generateSecret(new PBEKeySpec(password.toCharArray()));
        var cipher = Cipher.getInstance(algorithm);
        cipher.init(Cipher.ENCRYPT_MODE, secret, parameters);
        var encrypted = cipher.doFinal(key);
        var algorithmParameters = AlgorithmParameters.getInstance(algorithm);
        algorithmParameters.init(parameters);
        return new EncryptedPrivateKeyInfo(algorithmParameters, encrypted).getEncoded();
    }

    /** The PKCS#1 RSAPrivateKey inside a PKCS#8 RSA key: the content of its final OCTET STRING. */
    private static byte[] pkcs1(byte[] pkcs8) {
        // PrivateKeyInfo ::= SEQUENCE { version INTEGER, algorithm SEQUENCE, privateKey OCTET STRING }
        int i = 1 + lengthBytes(pkcs8, 1);                  // into the outer SEQUENCE
        i += 3;                                             // version: 02 01 00
        i += 1 + lengthBytes(pkcs8, i + 1) + length(pkcs8, i + 1); // skip the AlgorithmIdentifier
        int contentStart = i + 1 + lengthBytes(pkcs8, i + 1);
        return Arrays.copyOfRange(pkcs8, contentStart, contentStart + length(pkcs8, i + 1));
    }

    private static int lengthBytes(byte[] der, int at) {
        int first = der[at] & 0xFF;
        return first < 0x80 ? 1 : 1 + (first & 0x7F);
    }

    private static int length(byte[] der, int at) {
        int first = der[at] & 0xFF;
        if (first < 0x80) {
            return first;
        }
        int length = 0;
        for (int k = 1; k <= (first & 0x7F); k++) {
            length = (length << 8) | (der[at + k] & 0xFF);
        }
        return length;
    }

    /** Everything an exception shows: messages, toString and stack trace of the whole cause chain. */
    static String everything(Throwable thrown) {
        var text = new StringWriter();
        thrown.printStackTrace(new PrintWriter(text));
        for (var t = thrown; t != null; t = t.getCause()) {
            text.append('\n').append(String.valueOf(t.getMessage())).append('\n').append(t.toString());
        }
        return text.toString();
    }

    /** Fails if {@code text} holds a password, or any 16 characters of a key's Base64 or hex, or 12 raw key bytes. */
    static void assertNoSecrets(String text, String what) throws Exception {
        var pki = TestPki.get();
        for (var password : List.of(TestPki.PASSWORD, WRONG_PASSWORD)) {
            assertFalse(text.contains(password), what + " holds a password");
        }
        for (var entry : List.of(pki.serverRsa, pki.serverEc, pki.serverEd, pki.client)) {
            var der = entry.getPrivateKey().getEncoded();
            for (var encoded : List.of(Base64.getEncoder().encodeToString(der), HexFormat.of().formatHex(der),
                    HexFormat.of().withUpperCase().formatHex(der))) {
                for (int i = 0; i + 16 <= encoded.length(); i += 4) {
                    assertFalse(text.contains(encoded.substring(i, i + 16)), what + " holds key bytes (encoded)");
                }
            }
            var raw = new String(der, StandardCharsets.ISO_8859_1);
            for (int i = 0; i + 12 <= raw.length(); i++) {
                assertFalse(text.contains(raw.substring(i, i + 12)), what + " holds raw key bytes");
            }
        }
    }

    static Suite tests() {
        return suite("key material",
                test("the table has every case, each once", () -> {
                    assertEquals(NAMES, cases().stream().map(Bad::name).toList());
                    assertEquals((long) NAMES.size(), NAMES.stream().distinct().count());
                }),
                parameterized("bad material fails when the options are created, clearly and without secrets",
                        NAMES, name -> {
                            var bad = named(name);
                            var thrown = assertThrows(TlsMaterialException.class, () -> bad.create().create());
                            var message = thrown.getMessage();
                            assertTrue(message.contains(bad.expected()), "message: " + message);
                            assertTrue(message.contains(bad.file()), "the file is named: " + message);
                            assertEquals(Optional.of(bad.file()), thrown.file().map(f -> f.getFileName().toString()));
                            assertNoSecrets(everything(thrown), "the exception");
                            assertFalse(ErrorTests.hasRawControl(message, false), "control characters in " + message);
                        }),
                test("the caller's password array is not changed, and toString shows no password", () -> {
                    var pki = TestPki.get();
                    var password = TestPki.PASSWORD.toCharArray();
                    var options = TlsOptions.pkcs12(pki.serverP12, password);
                    assertEquals(TestPki.PASSWORD, new String(password));
                    var wrong = WRONG_PASSWORD.toCharArray();
                    assertThrows(TlsMaterialException.class, () -> TlsOptions.pkcs12(pki.serverP12, wrong));
                    assertEquals(WRONG_PASSWORD, new String(wrong));
                    var trustPassword = TestPki.PASSWORD.toCharArray();
                    var trust = TlsTrust.pkcs12(pki.caP12, trustPassword);
                    assertEquals(TestPki.PASSWORD, new String(trustPassword));
                    var withAlias = TlsOptions.pkcs12(pki.twoKeysP12, password, "ec");
                    assertEquals(TestPki.PASSWORD, new String(password));

                    var text = options.withClientAuth(ClientAuth.NEED, trust).toString();
                    assertTrue(text.contains("server.p12"), text);
                    assertTrue(text.contains("[TLSv1.3, TLSv1.2]"), text);
                    assertTrue(text.contains("clientAuth=NEED"), text);
                    assertTrue(text.contains("ca-trust.p12"), text);
                    assertTrue(withAlias.toString().contains("alias 'ec'"), withAlias.toString());
                    for (var shown : List.of(text, withAlias.toString(), options.identity().toString(),
                            TlsOptions.pem(pki.rsaChainPem, pki.rsaKeyPem).toString(),
                            HttpServerOptions.DEFAULT.withTls(options).toString())) {
                        assertNoSecrets(shown, "toString");
                    }
                    assertTrue(TlsOptions.pem(pki.ecChainPem, pki.ecKeyPem).toString().contains("ec-key.pem"));
                }),
                test("the identity holds the chain from the keystore and from PEM, the leaf first", () -> {
                    var pki = TestPki.get();
                    var p12 = TlsIdentity.pkcs12(pki.serverP12, TestPki.PASSWORD.toCharArray());
                    var pem = TlsIdentity.pem(pki.ecChainPem, pki.ecKeyPem);
                    assertEquals(TestPki.certificates(pki.serverRsa.getCertificateChain()), p12.certificateChain());
                    assertEquals(TestPki.certificates(pki.serverEc.getCertificateChain()), pem.certificateChain());
                    assertEquals(2, pem.certificateChain().size());
                    var alias = TlsIdentity.pkcs12(pki.twoKeysP12, TestPki.PASSWORD.toCharArray(), "ec");
                    assertEquals(pki.serverEc.getCertificate(), alias.certificateChain().getFirst());
                    assertEquals(List.of(pki.ca.getCertificate()), TlsTrust.pem(pki.caPem).certificates());
                }),
                test("PEM with CRLF, text around the blocks and trailing blanks is read", () -> {
                    var pki = TestPki.get();
                    var key = TestPki.pem("PRIVATE KEY", pki.serverEc.getPrivateKey().getEncoded())
                            .replace("\n", " \r\n");
                    var chain = "Subject: CN=localhost\n" + Files.readString(pki.ecChainPem) + "trailing text\n";
                    var options = TlsOptions.pem(pki.write("crlf-chain.pem", chain), pki.write("crlf-key.pem",
                            "Bag Attributes\r\n" + key.replace("----- \r\n", "-----\r\n")));
                    assertEquals(2, options.identity().certificateChain().size());
                }),
                test("protocols: only TLSv1.3 and TLSv1.2; older or unknown ones are refused", () -> {
                    var options = TestPki.get().serverOptions();
                    assertEquals(List.of("TLSv1.3", "TLSv1.2"), options.protocols());
                    assertEquals(List.of("TLSv1.3"), options.withProtocols("TLSv1.3").protocols());
                    assertEquals(List.of("TLSv1.2"), options.withProtocols("TLSv1.2").protocols());
                    for (var protocol : List.of("TLSv1.1", "TLSv1", "SSLv3", "SSLv2Hello", "TLSv1.4", "TLSv9", "tlsv1.3",
                            "DTLSv1.2", "")) {
                        var thrown = assertThrows(IllegalArgumentException.class, () -> options.withProtocols(protocol));
                        assertFalse(thrown instanceof TlsMaterialException);
                    }
                    assertTrue(assertThrows(IllegalArgumentException.class, () -> options.withProtocols("TLSv1.1"))
                            .getMessage().contains("older than TLS 1.2"));
                    assertThrows(IllegalArgumentException.class, () -> options.withProtocols("TLSv1.3", "TLSv1.1"));
                    assertThrows(IllegalArgumentException.class, () -> options.withProtocols());
                    assertThrows(IllegalArgumentException.class, () -> options.withProtocols("TLSv1.3", "TLSv1.3"));
                }),
                test("cipher suites: JDK default unless given; unknown, weak or unusable ones are refused", () -> {
                    var options = TestPki.get().serverOptions();
                    assertEquals(List.of(), options.cipherSuites());
                    assertEquals(List.of("TLS_AES_128_GCM_SHA256"),
                            options.withCipherSuites("TLS_AES_128_GCM_SHA256").cipherSuites());
                    assertThrows(IllegalArgumentException.class, () -> options.withCipherSuites("TLS_NO_SUCH_SUITE"));
                    assertThrows(IllegalArgumentException.class,
                            () -> options.withCipherSuites("TLS_ECDH_anon_WITH_AES_128_CBC_SHA"));
                    assertThrows(IllegalArgumentException.class,
                            () -> options.withCipherSuites("TLS_EMPTY_RENEGOTIATION_INFO_SCSV"));
                    assertThrows(IllegalArgumentException.class, () -> options.withProtocols("TLSv1.3")
                            .withCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"));
                    assertThrows(IllegalArgumentException.class, () -> options.withProtocols("TLSv1.2")
                            .withCipherSuites("TLS_AES_128_GCM_SHA256"));
                    assertThrows(IllegalArgumentException.class,
                            () -> options.withCipherSuites("TLS_AES_128_GCM_SHA256", "TLS_AES_128_GCM_SHA256"));
                }),
                test("client authentication needs a trust, except with an SSLContext", () -> {
                    var pki = TestPki.get();
                    var options = pki.serverOptions();
                    var trust = TlsTrust.pem(pki.caPem);
                    assertEquals(ClientAuth.NONE, options.clientAuth());
                    assertThrows(IllegalArgumentException.class, () -> options.withClientAuth(ClientAuth.NEED));
                    assertThrows(IllegalArgumentException.class, () -> options.withClientAuth(ClientAuth.WANT));
                    assertThrows(IllegalArgumentException.class, () -> options.withClientAuth(ClientAuth.NONE, trust));
                    assertEquals(Optional.of(trust), options.withClientAuth(ClientAuth.WANT, trust).clientTrust());
                    var context = TlsOptions.of(pki.clientContext());
                    assertEquals(ClientAuth.NEED, context.withClientAuth(ClientAuth.NEED).clientAuth());
                    assertThrows(IllegalArgumentException.class, () -> context.withClientAuth(ClientAuth.NEED, trust));
                    assertThrows(IllegalArgumentException.class, () -> TlsOptions.of(SSLContext.getInstance("TLS")));
                    assertThrows(NullPointerException.class, () -> TlsOptions.pkcs12(pki.serverP12, null));
                }),
                test("HttpServerOptions: no TLS by default; withTls and withoutTls", () -> {
                    var tls = TestPki.get().serverOptions();
                    assertEquals(Optional.empty(), HttpServerOptions.DEFAULT.tls());
                    assertEquals(Optional.of(tls), HttpServerOptions.DEFAULT.withTls(tls).tls());
                    assertEquals(Optional.of(tls), HttpServerOptions.DEFAULT.withTls(tls).withPort(1).tls());
                    assertEquals(HttpServerOptions.DEFAULT, HttpServerOptions.DEFAULT.withTls(tls).withoutTls());
                    assertEquals(java.time.Duration.ofSeconds(10), HttpTimeouts.DEFAULT.handshakeTimeout());
                    assertEquals(java.time.Duration.ofMillis(5),
                            HttpTimeouts.DEFAULT.withHandshakeTimeout(java.time.Duration.ofMillis(5)).handshakeTimeout());
                    assertThrows(IllegalArgumentException.class,
                            () -> HttpTimeouts.DEFAULT.withHandshakeTimeout(java.time.Duration.ZERO));
                }));
    }
}
