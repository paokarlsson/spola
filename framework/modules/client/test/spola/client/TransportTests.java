package spola.client;

import static spola.client.Support.since;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertNull;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;
import spola.test.Suite;

/** Criteria 8 and 9: the total timeout, and failures where no usable response came. */
final class TransportTests {

    private TransportTests() {
    }

    static RestClient fast(URI base) {
        return RestClient.builder().baseUri(base).timeout(Duration.ofMillis(500)).build();
    }

    static Suite tests() {
        return suite("timeouts and transport failures",
                test("timeout 500 ms: a server that takes the request and never answers gives timedOut within 2 s,"
                        + " and the server sees the connection close within 5 s", () -> {
                    try (var server = new Support.RawServer(Failures.NEVER_ANSWERS); var client = fast(server.base())) {
                        long start = System.nanoTime();
                        var e = assertThrows(RestClientTransportException.class,
                                () -> client.post("/slow").json(spola.json.JsonObject.of()).retrieveJson());
                        long took = since(start);
                        assertTrue(took >= 450 && took < 2_000, "took " + took + " ms");
                        assertTrue(e.timedOut());
                        assertNull(e.getCause());
                        assertTrue(e.getMessage().contains("timed out after 500 ms"), e.getMessage());
                        LimitTests.awaitClosed(server, 1, start);
                        assertEquals(1, server.requests.size());
                    }
                }),
                test("timeout 500 ms: status line and headers, then one byte every 100 ms without end, gives"
                        + " timedOut within 2 s, and the server sees the connection close within 5 s", () -> {
                    try (var server = new Support.RawServer(Failures.TRICKLES); var client = fast(server.base())) {
                        long start = System.nanoTime();
                        var e = assertThrows(RestClientTransportException.class, () -> client.get("/slow").retrieveJson());
                        long took = since(start);
                        assertTrue(took >= 450 && took < 2_000, "took " + took + " ms");
                        assertTrue(e.timedOut());
                        LimitTests.awaitClosed(server, 1, start);
                    }
                }),
                test("the timeout also covers retrieveBodiless and a body that trickles after a 204-like status",
                        () -> {
                    try (var server = new Support.RawServer(Failures.TRICKLES); var client = fast(server.base())) {
                        var e = assertThrows(RestClientTransportException.class,
                                () -> client.delete("/slow").retrieveBodiless());
                        assertTrue(e.timedOut());
                    }
                }),
                test("a refused connection (a closed port) gives RestClientTransportException without timedOut", () -> {
                    int port = Failures.closedPort();
                    try (var client = RestClient.builder().baseUri(Support.base(port)).build()) {
                        long start = System.nanoTime();
                        var e = assertThrows(RestClientTransportException.class, () -> client.get("/x").retrieveJson());
                        assertTrue(since(start) < 5_000, "took " + since(start) + " ms");
                        assertFalse(e.timedOut());
                        assertTrue(e.getCause() instanceof ConnectException, String.valueOf(e.getCause()));
                        assertTrue(e.getMessage().contains("could not connect"), e.getMessage());
                    }
                }),
                test("a server that sends garbage instead of HTTP gives RestClientTransportException without timedOut",
                        () -> {
                    try (var server = new Support.RawServer(Failures.GARBAGE);
                            var client = RestClient.builder().baseUri(server.base()).build()) {
                        var e = assertThrows(RestClientTransportException.class, () -> client.get("/x").retrieveJson());
                        assertFalse(e.timedOut());
                        assertTrue(e.getCause() != null);
                        assertTrue(e.getMessage().contains("not valid HTTP/1.1"), e.getMessage());
                    }
                }),
                test("a server that closes inside a body with Content-Length gives RestClientTransportException", () -> {
                    try (var server = new Support.RawServer(Failures.CLOSES_IN_BODY);
                            var client = RestClient.builder().baseUri(server.base()).build()) {
                        var e = assertThrows(RestClientTransportException.class, () -> client.get("/x").retrieveJson());
                        assertFalse(e.timedOut());
                        assertTrue(e.getCause() != null);
                    }
                }),
                test("https: the client starts with a TLS handshake (first byte 0x16) and gets"
                        + " RestClientTransportException from a server that does not speak TLS", () -> {
                    try (var server = new Support.RawServer(Failures.READS_FIRST_BYTE);
                            var client = RestClient.builder().baseUri(URI.create("https://127.0.0.1:" + server.port()))
                                    .build()) {
                        var e = assertThrows(RestClientTransportException.class, () -> client.get("/x").retrieveJson());
                        assertFalse(e.timedOut());
                        // java.net.http may try the handshake twice; every connection starts with a ClientHello
                        assertFalse(server.firstBytes.isEmpty());
                        assertEquals(List.of(0x16), server.firstBytes.stream().distinct().toList());
                        assertTrue(e.getCause() instanceof SSLException || e.getCause() instanceof java.io.IOException,
                                String.valueOf(e.getCause()));
                    }
                }),
                test("a thread interrupted while it waits gets RestClientTransportException within 2 s, the cause is"
                        + " InterruptedException and the interrupt flag is set", () -> {
                    var thrown = new AtomicReference<Throwable>();
                    var flag = new AtomicBoolean();
                    try (var server = new Support.RawServer(Failures.NEVER_ANSWERS);
                            var client = RestClient.builder().baseUri(server.base()).timeout(Duration.ofSeconds(30))
                                    .build()) {
                        var caller = Thread.ofPlatform().start(() -> {
                            try {
                                client.get("/x").retrieveJson();
                            } catch (Throwable t) {
                                thrown.set(t);
                                flag.set(Thread.currentThread().isInterrupted());
                            }
                        });
                        while (server.requests.isEmpty()) {
                            Thread.sleep(5);
                        }
                        long start = System.nanoTime();
                        caller.interrupt();
                        caller.join(5_000);
                        assertTrue(since(start) < 2_000, "took " + since(start) + " ms");
                        var e = (RestClientTransportException) thrown.get();
                        assertFalse(e.timedOut());
                        assertTrue(e.getCause() instanceof InterruptedException, String.valueOf(e.getCause()));
                        assertTrue(flag.get(), "the interrupt flag is set");
                        LimitTests.awaitClosed(server, 1, start);
                    }
                }),
                test("a thread that is already interrupted sends nothing", () -> {
                    var thrown = new AtomicReference<Throwable>();
                    var flag = new AtomicBoolean();
                    try (var server = new Support.RawServer(Failures.NEVER_ANSWERS);
                            var client = RestClient.builder().baseUri(server.base()).build()) {
                        var caller = Thread.ofVirtual().start(() -> {
                            Thread.currentThread().interrupt();
                            try {
                                client.get("/x").retrieveJson();
                            } catch (Throwable t) {
                                thrown.set(t);
                                flag.set(Thread.currentThread().isInterrupted());
                            }
                        });
                        caller.join(5_000);
                        var e = (RestClientTransportException) thrown.get();
                        assertTrue(e.getCause() instanceof InterruptedException);
                        assertTrue(flag.get());
                        Thread.sleep(100);
                        assertEquals(0, server.connections.get());
                    }
                }));
    }
}
