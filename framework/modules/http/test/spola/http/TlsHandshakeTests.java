package spola.http;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import spola.test.Suite;

/**
 * The TLS handshake: protocol versions, ALPN, client certificates, the handshake timeout and
 * handshakes that fail. Clients are {@link SSLSocket}s from a client {@link SSLContext} that
 * trusts only the test CA (or raw sockets for exact bytes).
 */
final class TlsHandshakeTests {

    private TlsHandshakeTests() {
    }

    /** Answers with what the handler saw of TLS: scheme, protocol, suite and the client's certificate subjects. */
    static Response describe(Request request) {
        var tls = request.tls();
        var subjects = tls.map(t -> t.clientCertificates().stream()
                .map(c -> c.getSubjectX500Principal().getName()).toList()).orElse(List.of());
        return Response.text(200, request.scheme() + "|" + tls.map(TlsInfo::protocol).orElse("-") + "|"
                + tls.map(TlsInfo::cipherSuite).orElse("-") + "|" + subjects);
    }

    static HttpServer start(TlsOptions tls, Handler handler) throws IOException {
        return Support.start(Support.options().withTls(tls), handler);
    }

    /** A TLS client with {@code tweak} applied to its parameters (name checking is on), after its handshake. */
    static SSLSocket connect(SSLContext context, int port, Consumer<SSLParameters> tweak) throws IOException {
        var socket = (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", port);
        try {
            var parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tweak.accept(parameters);
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(5_000);
            socket.startHandshake();
            return socket;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    /** GET / on a fresh TLS connection; the response text. */
    static String get(SSLContext context, int port, Consumer<SSLParameters> tweak) throws Exception {
        try (var c = Support.Client.over(connect(context, port, tweak), 5_000)) {
            var reply = c.send(Support.get("/")).read();
            assertEquals(200, reply.status());
            return reply.text();
        }
    }

    /** Fails unless connecting, sending GET / and reading the response fails (in the handshake or right after it). */
    static void refused(SSLContext context, int port, Consumer<SSLParameters> tweak) {
        try (var c = Support.Client.over(connect(context, port, tweak), 5_000)) {
            c.send(Support.get("/"));
            var reply = c.read(); // TLS 1.3: a refused client certificate shows after the client's handshake
            throw new AssertionError("expected a failed handshake, got " + reply.statusLine());
        } catch (IOException e) {
            // refused
        }
    }

    /** The first TLS record a client with {@code context} sends: its ClientHello. */
    static byte[] clientHello(SSLContext context) throws Exception {
        var engine = context.createSSLEngine("127.0.0.1", 443);
        engine.setUseClientMode(true);
        var out = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
        engine.beginHandshake();
        engine.wrap(ByteBuffer.allocate(0), out);
        out.flip();
        var bytes = new byte[out.remaining()];
        out.get(bytes);
        return bytes;
    }

    /** A ClientHello record for an old version, without extensions (so without supported_versions). */
    static byte[] legacyClientHello(int major, int minor) {
        var body = new java.io.ByteArrayOutputStream();
        body.write(major);
        body.write(minor);
        body.writeBytes(new byte[32]);              // random
        body.write(0);                              // no session id
        body.writeBytes(new byte[] {0, 4, 0x00, 0x2f, 0x00, 0x35}); // two RSA/AES CBC suites
        body.writeBytes(new byte[] {1, 0});         // null compression
        var b = body.toByteArray();
        var handshake = new java.io.ByteArrayOutputStream();
        handshake.write(1);                         // ClientHello
        handshake.writeBytes(new byte[] {0, (byte) (b.length >> 8), (byte) b.length});
        handshake.writeBytes(b);
        var h = handshake.toByteArray();
        var record = new java.io.ByteArrayOutputStream();
        record.writeBytes(new byte[] {0x16, (byte) major, (byte) Math.min(minor, 1), (byte) (h.length >> 8), (byte) h.length});
        record.writeBytes(h);
        return record.toByteArray();
    }

    /** Everything the server sends on a raw socket after {@code bytes}, until it closes (at most 5 s). */
    static byte[] answerTo(int port, byte[] bytes) throws IOException {
        try (var c = new Support.Client(port)) {
            c.send(bytes);
            return c.rest();
        }
    }

    /** A TCP socket whose output, once {@link #dribble} is set, goes out one byte every 20 ms. */
    static final class DribbleSocket extends Socket {
        static final int GAP_MILLIS = 20;
        volatile boolean dribble;

        DribbleSocket(int port) throws IOException {
            super(Support.LOCALHOST, port);
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            var raw = super.getOutputStream();
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    write(new byte[] {(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (!dribble) {
                        raw.write(bytes, offset, length);
                        return;
                    }
                    for (int i = 0; i < length; i++) {
                        raw.write(bytes[offset + i]);
                        raw.flush();
                        try {
                            Thread.sleep(GAP_MILLIS);
                        } catch (InterruptedException e) {
                            throw new IOException(e);
                        }
                    }
                }

                @Override
                public void flush() throws IOException {
                    raw.flush();
                }
            };
        }
    }

    /** A TLS client over a {@link DribbleSocket}, after its handshake (at full speed). */
    static SSLSocket dribbling(DribbleSocket raw) throws Exception {
        var tls = (SSLSocket) TestPki.get().clientContext().getSocketFactory()
                .createSocket(raw, "127.0.0.1", raw.getPort(), true);
        tls.setSoTimeout(10_000);
        tls.startHandshake();
        return tls;
    }

    /**
     * Sends {@code text} as one TLS record, one byte every {@link DribbleSocket#GAP_MILLIS}, and
     * returns after how many milliseconds the server cut the connection (a write failed); fails
     * if the whole record went through.
     */
    static long cutAfter(SSLSocket tls, DribbleSocket raw, String text) {
        var bytes = text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        raw.dribble = true;
        long start = System.nanoTime();
        try {
            for (int i = 0; i < 3; i++) { // the cut shows on a later write at the latest
                tls.getOutputStream().write(bytes);
                tls.getOutputStream().flush();
            }
        } catch (IOException e) {
            return millisSince(start);
        }
        throw new AssertionError("the server read the whole record sent one byte at a time ("
                + millisSince(start) + " ms)");
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    static Suite tests() {
        return suite("handshake",
                test("TLS 1.3 and TLS 1.2 both work; the handler sees the negotiated protocol", () -> {
                    var pki = TestPki.get();
                    try (var server = start(pki.serverOptions(), TlsHandshakeTests::describe)) {
                        var tls13 = get(pki.clientContext(), server.port(), p -> p.setProtocols(new String[] {"TLSv1.3"}));
                        assertTrue(tls13.startsWith("https|TLSv1.3|TLS_"), tls13);
                        var tls12 = get(pki.clientContext(), server.port(), p -> p.setProtocols(new String[] {"TLSv1.2"}));
                        assertTrue(tls12.startsWith("https|TLSv1.2|TLS_ECDHE_"), tls12);
                        var preferred = get(pki.clientContext(), server.port(), p -> { });
                        assertTrue(preferred.startsWith("https|TLSv1.3|"), preferred);
                    }
                }),
                test("a client that allows only TLS 1.1 or older gets no handshake", () -> {
                    var pki = TestPki.get();
                    var calls = new AtomicInteger();
                    try (var server = start(pki.serverOptions(), request -> {
                        calls.incrementAndGet();
                        return Response.text(200, "ok");
                    })) {
                        // a JSSE client limited to TLS 1.1 / 1.0
                        for (var old : List.of("TLSv1.1", "TLSv1")) {
                            refused(pki.clientContext(), server.port(), p -> p.setProtocols(new String[] {old}));
                        }
                        // the exact bytes of old ClientHellos (TLS 1.1, TLS 1.0, SSL 3.0): no ServerHello comes back
                        for (int minor : new int[] {2, 1, 0}) {
                            var answer = answerTo(server.port(), legacyClientHello(3, minor));
                            assertTrue(answer.length == 0 || answer[0] == 0x15,
                                    "version 3." + minor + ": expected an alert or nothing, got record type " + answer[0]);
                        }
                        // a server with TLS 1.3 only refuses a TLS 1.2 client
                        try (var only13 = start(pki.serverOptions().withProtocols("TLSv1.3"), TlsHandshakeTests::describe)) {
                            refused(pki.clientContext(), only13.port(), p -> p.setProtocols(new String[] {"TLSv1.2"}));
                            assertTrue(get(pki.clientContext(), only13.port(), p -> { }).startsWith("https|TLSv1.3|"));
                        }
                        // the server still works
                        assertEquals("ok", get(pki.clientContext(), server.port(), p -> { }));
                        assertEquals(1, calls.get());
                    }
                }),
                test("an explicit cipher suite is the one negotiated", () -> {
                    var pki = TestPki.get();
                    var tls = pki.serverOptions().withCipherSuites("TLS_CHACHA20_POLY1305_SHA256", "TLS_AES_128_GCM_SHA256");
                    try (var server = start(tls, TlsHandshakeTests::describe)) {
                        var text = get(pki.clientContext(), server.port(), p -> { });
                        assertTrue(text.startsWith("https|TLSv1.3|TLS_CHACHA20_POLY1305_SHA256|"), text);
                    }
                }),
                test("ALPN: h2,http/1.1 gets http/1.1; only h2 fails; no ALPN works", () -> {
                    var pki = TestPki.get();
                    var calls = new AtomicInteger();
                    try (var server = start(pki.serverOptions(), request -> {
                        calls.incrementAndGet();
                        return Response.text(200, "ok");
                    })) {
                        try (var socket = connect(pki.clientContext(), server.port(),
                                p -> p.setApplicationProtocols(new String[] {"h2", "http/1.1"}));
                             var c = Support.Client.over(socket, 5_000)) {
                            assertEquals("http/1.1", socket.getApplicationProtocol());
                            assertEquals("ok", c.send(Support.get("/")).read().text());
                        }
                        refused(pki.clientContext(), server.port(), p -> p.setApplicationProtocols(new String[] {"h2"}));
                        try (var socket = connect(pki.clientContext(), server.port(), p -> { });
                             var c = Support.Client.over(socket, 5_000)) {
                            assertEquals("", socket.getApplicationProtocol()); // none negotiated
                            assertEquals("ok", c.send(Support.get("/")).read().text());
                        }
                        assertEquals(2, calls.get());
                    }
                }),
                test("mTLS NEED: no certificate or an untrusted one fails; a valid one reaches the handler", () -> {
                    var pki = TestPki.get();
                    var calls = new AtomicInteger();
                    var seen = new AtomicReference<List<X509Certificate>>();
                    var tls = pki.serverOptions().withClientAuth(ClientAuth.NEED, TlsTrust.pem(pki.caPem));
                    try (var server = start(tls, request -> {
                        calls.incrementAndGet();
                        seen.set(request.tls().orElseThrow().clientCertificates());
                        return Response.status(204);
                    })) {
                        for (var protocol : List.of("TLSv1.3", "TLSv1.2")) {
                            refused(pki.clientContext(), server.port(), p -> p.setProtocols(new String[] {protocol}));
                            refused(pki.forcingClientContext(pki.rogue), server.port(),
                                    p -> p.setProtocols(new String[] {protocol}));
                        }
                        assertEquals(0, calls.get(), "the handler was called");
                        try (var c = Support.Client.over(connect(pki.clientContext(pki.client), server.port(), p -> { }),
                                5_000)) {
                            assertEquals(204, c.send(Support.get("/")).read().status());
                        }
                        assertEquals(1, calls.get());
                        assertEquals(pki.client.getCertificate(), seen.get().getFirst());
                        assertEquals(TestPki.certificates(pki.client.getCertificateChain()), seen.get());
                    }
                    // the same with the CA from a PKCS#12 truststore
                    var p12 = pki.serverOptions().withClientAuth(ClientAuth.NEED,
                            TlsTrust.pkcs12(pki.caP12, TestPki.PASSWORD.toCharArray()));
                    try (var server = start(p12, TlsHandshakeTests::describe)) {
                        refused(pki.clientContext(), server.port(), p -> { });
                        assertTrue(get(pki.clientContext(pki.client), server.port(), p -> { }).endsWith("[CN=Test Client, CN=Spola Test CA]"));
                    }
                }),
                test("mTLS WANT: without a certificate the chain is empty; with one it is there; NONE asks for none", () -> {
                    var pki = TestPki.get();
                    var want = pki.serverOptions().withClientAuth(ClientAuth.WANT, TlsTrust.pem(pki.caPem));
                    try (var server = start(want, TlsHandshakeTests::describe)) {
                        assertTrue(get(pki.clientContext(), server.port(), p -> { }).endsWith("|[]"));
                        assertTrue(get(pki.clientContext(pki.client), server.port(), p -> { })
                                .endsWith("|[CN=Test Client, CN=Spola Test CA]"));
                        // a certificate the trust does not accept fails even with WANT (it must really be
                        // sent: the JDK's own client key manager sends none when the server names other CAs)
                        for (var protocol : List.of("TLSv1.3", "TLSv1.2")) {
                            refused(pki.forcingClientContext(pki.rogue), server.port(),
                                    p -> p.setProtocols(new String[] {protocol}));
                        }
                    }
                    // NONE: a client that would send its certificate is not asked for it
                    try (var server = start(pki.serverOptions(), TlsHandshakeTests::describe)) {
                        for (var protocol : List.of("TLSv1.3", "TLSv1.2")) {
                            assertTrue(get(pki.clientContext(pki.client), server.port(),
                                    p -> p.setProtocols(new String[] {protocol})).endsWith("|[]"));
                        }
                    }
                }),
                test("handshakeTimeout is a total: a ClientHello one byte at a time and a silent client are cut off", () -> {
                    var pki = TestPki.get();
                    var timeouts = Support.FAST.withHeaderTimeout(Duration.ofSeconds(10)).withIdleTimeout(Duration.ofSeconds(10))
                            .withReadTimeout(Duration.ofSeconds(10)).withHandshakeTimeout(Duration.ofMillis(800));
                    var options = Support.options().withTimeouts(timeouts).withTls(pki.serverOptions());
                    var hello = clientHello(pki.clientContext());
                    assertTrue(hello.length > 100, "ClientHello of " + hello.length + " bytes");
                    try (var server = Support.start(options, request -> Response.text(200, "served"));
                         var slow = new Support.Client(server.port(), 10_000);
                         var silent = new Support.Client(server.port(), 10_000)) {
                        long start = System.nanoTime();
                        var slowClosed = new AtomicLong();
                        var writer = Thread.ofVirtual().start(() -> {
                            try {
                                for (byte b : hello) {
                                    slow.out.write(b);
                                    slow.out.flush();
                                    Thread.sleep(10); // far below every per-read limit
                                }
                            } catch (IOException | InterruptedException e) {
                                // the server closed the connection
                            }
                        });
                        var slowReader = Thread.ofVirtual().start(() -> {
                            try {
                                slow.rest();
                            } catch (IOException e) {
                                // closed
                            }
                            slowClosed.set(millisSince(start));
                        });
                        Thread.sleep(150);
                        // meanwhile another client is served: the acceptor is not blocked by the two
                        try (var c = Transport.Tls.standard().client(server.port())) {
                            assertEquals("served", c.send(Support.get("/")).read().text());
                        }
                        long served = millisSince(start);
                        assertTrue(silent.closedByServer(), "the silent client was closed");
                        long silentClosed = millisSince(start);
                        assertTrue(slowReader.join(Duration.ofSeconds(5)));
                        writer.interrupt();
                        writer.join();
                        assertTrue(served < slowClosed.get() && served < silentClosed,
                                "served at " + served + " ms, before the cut-offs at " + slowClosed.get() + " and "
                                        + silentClosed + " ms");
                        for (long closed : new long[] {slowClosed.get(), silentClosed}) {
                            assertTrue(closed >= 750 && closed < 2_500, "cut off after " + closed + " ms");
                        }
                        assertTrue(slowClosed.get() < 10L * hello.length, "the slow client was not allowed to finish");
                    }
                }),
                test("header, idle and linger timeouts are totals over TLS: a record sent one byte at a time is cut off", () -> {
                    // 20 ms between bytes is far below every per-read limit; a request of ~300 bytes takes ~6 s
                    var request = "GET / HTTP/1.1\r\nHost: x\r\nX-Pad: " + "p".repeat(250) + "\r\n\r\n";
                    var options = Support.options().withTls(TestPki.get().serverOptions());
                    var slowHeader = Support.FAST.withHeaderTimeout(Duration.ofMillis(800))
                            .withIdleTimeout(Duration.ofSeconds(10)).withReadTimeout(Duration.ofSeconds(10));
                    try (var server = Support.start(options.withTimeouts(slowHeader), r -> Response.text(200, "served"));
                         var raw = new DribbleSocket(server.port()); var tls = dribbling(raw)) {
                        long cut = cutAfter(tls, raw, request);
                        assertTrue(cut >= 600 && cut < 2_500, "header: cut off after " + cut + " ms");
                    }
                    var slowIdle = Support.FAST.withHeaderTimeout(Duration.ofSeconds(10))
                            .withIdleTimeout(Duration.ofMillis(800)).withReadTimeout(Duration.ofSeconds(10));
                    try (var server = Support.start(options.withTimeouts(slowIdle), r -> Response.text(200, "served"));
                         var raw = new DribbleSocket(server.port()); var tls = dribbling(raw)) {
                        var c = Support.Client.over(tls, 10_000);
                        assertEquals("served", c.send(Support.get("/")).read().text()); // the first request at full speed
                        long cut = cutAfter(tls, raw, request);
                        assertTrue(cut >= 400 && cut < 2_500, "idle: cut off after " + cut + " ms");
                    }
                    var slowNothing = Support.FAST.withHeaderTimeout(Duration.ofSeconds(10))
                            .withIdleTimeout(Duration.ofSeconds(10)).withReadTimeout(Duration.ofSeconds(10));
                    try (var server = Support.start(options.withTimeouts(slowNothing), r -> Response.text(200, "served"));
                         var raw = new DribbleSocket(server.port()); var tls = dribbling(raw)) {
                        var c = Support.Client.over(tls, 10_000);
                        var reply = c.send("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").read();
                        assertEquals("served", reply.text());
                        long cut = cutAfter(tls, raw, request); // the server lingers at most LINGER_MILLIS
                        assertTrue(cut >= Connection.LINGER_MILLIS - 500 && cut < Connection.LINGER_MILLIS + 2_000,
                                "linger: cut off after " + cut + " ms");
                    }
                }),
                test("failed handshakes close only their connection, and log no raw control characters", () -> {
                    var pki = TestPki.get();
                    var log = new Support.CapturedLog();
                    var hello = clientHello(pki.clientContext());
                    try (var server = HttpServer.start(Support.options().withTls(pki.serverOptions()),
                            request -> Response.text(200, "fine"), log)) {
                        int port = server.port();
                        var https = Transport.Tls.standard();
                        // plain HTTP to the TLS port, with control characters in it
                        var answer = answerTo(port, "GET /\u001b[31m HTTP/1.1\r\nHost: a\u0000\r\n\r\n"
                                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                        assertFalse(Support.ascii(answer).startsWith("HTTP/"), "an HTTP answer on the TLS port");
                        assertFine(https, port);
                        // random bytes, control characters included
                        var random = new byte[2_000];
                        new Random(7).nextBytes(random);
                        answerTo(port, random);
                        assertFine(https, port);
                        // random bytes after a TLS record header
                        random[0] = 0x16;
                        random[1] = 3;
                        random[2] = 3;
                        answerTo(port, random);
                        assertFine(https, port);
                        // an aborted handshake: half a ClientHello, then the client goes away
                        try (var c = new Support.Client(port)) {
                            c.send(java.util.Arrays.copyOf(hello, hello.length / 2));
                        }
                        assertFine(https, port);
                        // a client that does not trust the server
                        try {
                            connect(pki.untrustingContext(), port, p -> { }).close();
                            throw new AssertionError("a client that does not trust the server finished its handshake");
                        } catch (IOException e) {
                            // expected
                        }
                        assertFine(https, port);
                        long deadline = System.nanoTime() + 2_000_000_000L;
                        while (server.openConnections() > 0 && System.nanoTime() < deadline) {
                            Thread.sleep(10);
                        }
                        assertEquals(0, server.openConnections());
                    }
                    var records = log.records();
                    assertTrue(records.stream().filter(r -> r.contains("TLS handshake")).count() >= 4,
                            "the failed handshakes are logged: " + records);
                    for (var record : records) {
                        assertFalse(ErrorTests.hasRawControl(record, true), "log record: " + Support.visible(record));
                        assertFalse(ErrorTests.hasRawControl(record.lines().findFirst().orElse(""), false));
                        assertFalse(record.contains("GET /"), "raw input in the log: " + record);
                    }
                }),
                test("maxConnections counts connections that are still in their handshake", () -> {
                    var pki = TestPki.get();
                    var options = Support.options().withLimits(HttpLimits.DEFAULT.withMaxConnections(2))
                            .withTls(pki.serverOptions());
                    try (var server = Support.start(options, request -> Response.text(200, "ok"))) {
                        var silent = new ArrayList<Socket>();
                        try {
                            for (int i = 0; i < 2; i++) {
                                silent.add(new Socket(Support.LOCALHOST, server.port())); // never sends a ClientHello
                            }
                            long deadline = System.nanoTime() + 2_000_000_000L;
                            while (server.openConnections() < 2 && System.nanoTime() < deadline) {
                                Thread.sleep(5);
                            }
                            assertEquals(2, server.openConnections());
                            // a third client's handshake is not answered while the two are in theirs
                            var third = (SSLSocket) pki.clientContext().getSocketFactory().createSocket();
                            third.connect(new InetSocketAddress(Support.LOCALHOST, server.port()));
                            third.setSoTimeout(700);
                            var started = Thread.ofVirtual().start(() -> {
                                try {
                                    third.startHandshake();
                                } catch (IOException e) {
                                    // the timeout below
                                }
                            });
                            assertFalse(started.join(Duration.ofMillis(500)), "the third handshake finished at the limit");
                            third.close();
                            started.join();
                            // one leaves: a new client gets its handshake and its answer
                            silent.removeFirst().close();
                            try (var c = Transport.Tls.standard().client(server.port())) {
                                assertEquals("ok", c.send(Support.get("/")).read().text());
                            }
                        } finally {
                            for (var socket : silent) {
                                socket.close();
                            }
                        }
                    }
                }),
                test("an absolute-form target with http:// over TLS is served; the scheme stays https", () -> {
                    try (var server = start(TestPki.get().serverOptions(), request -> Response.text(200,
                            request.scheme() + " " + request.host() + " " + request.path()));
                         var c = Transport.Tls.standard().client(server.port())) {
                        assertEquals("https a.example /x", c.send("GET http://a.example/x HTTP/1.1\r\nHost: b\r\n\r\n")
                                .read().text());
                    }
                    try (var server = Support.start(TlsHandshakeTests::describe); var c = new Support.Client(server.port())) {
                        assertEquals("http|-|-|[]", c.send(Support.get("/")).read().text()); // plain HTTP: no TLS
                    }
                }));
    }

    private static void assertFine(Transport https, int port) throws Exception {
        try (var c = https.client(port)) {
            assertEquals("fine", c.send(Support.get("/")).read().text());
        }
    }
}
