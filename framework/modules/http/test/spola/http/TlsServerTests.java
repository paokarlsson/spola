package spola.http;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import spola.test.Suite;

/**
 * HTTPS with a real client ({@link HttpClient}) for every kind of key material, and the
 * server's behavior over TLS: the 07a protocol tests, the write timeout, graceful stop and many
 * concurrent clients.
 */
final class TlsServerTests {

    private TlsServerTests() {
    }

    /**
     * A source of server key material.
     *
     * @param name      what it is
     * @param algorithm the public key algorithm of the server certificate
     * @param options   creates the options
     */
    record Material(String name, String algorithm, TlsMaterialTests.Create options) {
        @Override
        public String toString() {
            return name;
        }
    }

    static final List<Material> MATERIALS = List.of(
            new Material("PKCS#12 (RSA)", "RSA", () -> TlsOptions.pkcs12(TestPki.get().serverP12,
                    TestPki.PASSWORD.toCharArray())),
            new Material("PKCS#12 with an alias (EC)", "EC", () -> TlsOptions.pkcs12(TestPki.get().twoKeysP12,
                    TestPki.PASSWORD.toCharArray(), "ec")),
            new Material("PEM (RSA)", "RSA", () -> TlsOptions.pem(TestPki.get().rsaChainPem, TestPki.get().rsaKeyPem)),
            new Material("PEM (EC)", "EC", () -> TlsOptions.pem(TestPki.get().ecChainPem, TestPki.get().ecKeyPem)),
            new Material("PEM (Ed25519)", "EdDSA", () -> TlsOptions.pem(TestPki.get().edChainPem,
                    TestPki.get().edKeyPem)),
            new Material("a ready SSLContext", "RSA", () -> TlsOptions.of(serverContext(TestPki.get().serverRsa))));

    /** A server context built the way a caller of {@link TlsOptions#of(SSLContext)} would. */
    static SSLContext serverContext(KeyStore.PrivateKeyEntry entry) throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setEntry("server", entry, new KeyStore.PasswordProtection(TestPki.PASSWORD.toCharArray()));
        var keys = KeyManagerFactory.getInstance("PKIX");
        keys.init(store, TestPki.PASSWORD.toCharArray());
        var context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        return context;
    }

    /** Echoes method, path, scheme, TLS protocol and body. */
    static Response echo(Request request) throws Exception {
        var body = new String(request.bytes(), StandardCharsets.UTF_8);
        return Response.text(200, request.method() + " " + request.path() + " " + request.scheme() + " "
                + request.tls().map(TlsInfo::protocol).orElse("-") + " body=" + body).withHeader("X-Echo", "yes");
    }

    static Suite tests() {
        return suite("HTTPS",
                parameterized("HttpClient: GET and POST over HTTPS, the server's name checked against its SAN",
                        MATERIALS, material -> {
                            var options = Support.options().withTls((TlsOptions) material.options().create());
                            // the client's default version prefers HTTP/2; ALPN gives HTTP/1.1
                            try (var server = Support.start(options, TlsServerTests::echo);
                                 var client = HttpClient.newBuilder().sslContext(TestPki.get().clientContext())
                                         .connectTimeout(Duration.ofSeconds(5)).build()) {
                                var base = "https://127.0.0.1:" + server.port();
                                var get = client.send(HttpRequest.newBuilder(URI.create(base + "/hello")).GET().build(),
                                        HttpResponse.BodyHandlers.ofString());
                                assertEquals(200, get.statusCode());
                                assertEquals("GET /hello https TLSv1.3 body=", get.body());
                                assertEquals(Optional.of("yes"), get.headers().firstValue("X-Echo"));
                                assertEquals(Optional.of("text/plain; charset=utf-8"),
                                        get.headers().firstValue("Content-Type"));
                                assertEquals(HttpClient.Version.HTTP_1_1, get.version());
                                var certificate = get.sslSession().orElseThrow().getPeerCertificates()[0];
                                assertEquals(material.algorithm(), certificate.getPublicKey().getAlgorithm());

                                var text = "{\"qty\":2} åäö ".repeat(1_000);
                                var post = client.send(HttpRequest.newBuilder(URI.create(base + "/orders"))
                                        .POST(HttpRequest.BodyPublishers.ofString(text)).build(),
                                        HttpResponse.BodyHandlers.ofString());
                                assertEquals(200, post.statusCode());
                                assertEquals("POST /orders https TLSv1.3 body=" + text, post.body());
                            }
                        }),
                test("HttpClient checks the name: localhost is in the SAN, a CA-signed certificate for another name fails", () -> {
                    try (var server = Support.start(Support.options().withTls(TestPki.get().serverOptions()),
                            TlsServerTests::echo);
                         var other = Support.start(Support.options().withTls(TlsOptions.of(serverContext(
                                 TestPki.get().otherName))), TlsServerTests::echo);
                         var client = HttpClient.newBuilder().sslContext(TestPki.get().clientContext()).build()) {
                        var ok = client.send(HttpRequest.newBuilder(URI.create("https://localhost:" + server.port() + "/"))
                                .build(), HttpResponse.BodyHandlers.ofString());
                        assertEquals(200, ok.statusCode());
                        try {
                            client.send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + other.port() + "/"))
                                    .build(), HttpResponse.BodyHandlers.ofString());
                            throw new AssertionError("a certificate without the name 127.0.0.1 was accepted");
                        } catch (IOException e) {
                            // SSLHandshakeException: no subject alternative name matches
                        }
                    }
                }),
                ConnectionTests.tests(Transport.Tls.standard()),
                ChunkedTests.tests(Transport.Tls.standard()),
                ExpectTests.tests(Transport.Tls.standard()),
                MalformedRequestTests.selection(Transport.Tls.standard(), 3),
                test("writeTimeout: a client that stops reading a large streaming response is cut off", () -> {
                    var timeouts = Support.FAST.withWriteTimeout(Duration.ofMillis(300));
                    var options = Support.options().withTimeouts(timeouts).withTls(TestPki.get().serverOptions());
                    var done = new CountDownLatch(1);
                    var stalledAt = new AtomicLong();
                    var stalledFor = new AtomicLong();
                    var failure = new AtomicReference<Throwable>();
                    Handler handler = request -> {
                        if (request.path().equals("/ping")) {
                            return Response.text(200, "pong");
                        }
                        return Response.stream(200, "application/octet-stream", out -> {
                            var block = new byte[64 * 1024];
                            try {
                                while (true) { // endless: only the write timeout can stop it
                                    stalledAt.set(System.nanoTime());
                                    out.write(block);
                                }
                            } catch (IOException e) {
                                stalledFor.set((System.nanoTime() - stalledAt.get()) / 1_000_000);
                                failure.set(e);
                                done.countDown();
                                throw e;
                            }
                        });
                    };
                    try (var server = Support.start(options, handler); var raw = new Socket()) {
                        raw.setReceiveBufferSize(4 * 1024);
                        raw.connect(new InetSocketAddress(Support.LOCALHOST, server.port()));
                        var socket = (SSLSocket) TestPki.get().clientContext().getSocketFactory()
                                .createSocket(raw, "127.0.0.1", server.port(), true);
                        socket.startHandshake();
                        socket.getOutputStream().write(Support.get("/stream").getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                        // never read
                        assertTrue(done.await(10, TimeUnit.SECONDS), "the handler's write never failed");
                        assertTrue(failure.get() instanceof IOException);
                        assertTrue(stalledFor.get() >= 250 && stalledFor.get() < 3_000,
                                "the blocked write failed after " + stalledFor.get() + " ms");
                        // the handler's thread is free and the connection gone
                        long deadline = System.nanoTime() + 3_000_000_000L;
                        while (server.openConnections() > 0 && System.nanoTime() < deadline) {
                            Thread.sleep(10);
                        }
                        assertEquals(0, server.openConnections());
                        try (var c = Transport.Tls.standard().client(server.port())) {
                            assertEquals("pong", c.send(Support.get("/ping")).read().text());
                        }
                    }
                }),
                test("graceful stop: idle TLS connections closed, the request in progress finishes, close() returns",
                        () -> ShutdownTests.inProgressFinishes(Transport.Tls.standard())),
                test("200 concurrent HTTPS clients with 3 requests each on keep-alive get the right answers", () -> {
                    var context = TestPki.get().clientContext();
                    var transport = new Transport.Tls(() -> TestPki.get().serverOptions(), () -> context);
                    var failures = new ConcurrentLinkedQueue<String>();
                    var ok = new AtomicInteger();
                    var start = new CountDownLatch(1);
                    int clients = 200;
                    int requests = 3;
                    try (var server = transport.start(request -> Response.text(200, request.path() + "|"
                            + new String(request.bytes(), StandardCharsets.US_ASCII)))) {
                        var threads = new ArrayList<Thread>();
                        for (int i = 0; i < clients; i++) {
                            int client = i;
                            threads.add(Thread.ofVirtual().start(() -> {
                                try {
                                    start.await();
                                    try (var c = transport.client(server.port(), 20_000)) {
                                        for (int r = 0; r < requests; r++) {
                                            var body = "body-" + client + "-" + r;
                                            var reply = c.send("POST /c" + client + "/r" + r + " HTTP/1.1\r\nHost: a\r\n"
                                                    + "Content-Length: " + body.length() + "\r\n\r\n" + body).read();
                                            var expected = "/c" + client + "/r" + r + "|" + body;
                                            if (reply.status() != 200 || !reply.text().equals(expected)) {
                                                failures.add(client + "/" + r + ": " + reply.statusLine() + " " + reply.text());
                                            } else {
                                                ok.incrementAndGet();
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    failures.add(client + ": " + e);
                                }
                            }));
                        }
                        start.countDown();
                        for (var thread : threads) {
                            assertTrue(thread.join(Duration.ofSeconds(30)), "a client did not finish");
                        }
                    }
                    assertEquals(List.of(), failures.stream().limit(5).toList());
                    assertEquals(clients * requests, ok.get());
                }));
    }
}
