package spola.http;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import spola.test.Suite;

/**
 * Criterion 9: {@link Request#remoteAddress()} is the client socket's local address and port,
 * over plain TCP and over TLS (the TCP socket under TLS), the same for every request on a
 * connection and different between connections. Criterion 2 for {@code spola.http}: the main
 * code calls no name lookup.
 */
final class RemoteAddressTests {

    private RemoteAddressTests() {
    }

    /** A server whose handler records {@code remoteAddress()} and answers with it as text. */
    private static final class Recorder implements Handler {
        final ConcurrentLinkedQueue<InetSocketAddress> seen = new ConcurrentLinkedQueue<>();

        @Override
        public Response handle(Request request) {
            var remote = request.remoteAddress();
            seen.add(remote);
            return Response.text(200, remote.getAddress().getHostAddress() + " " + remote.getPort());
        }
    }

    /** The client socket's own end, which the server sees as the remote end. */
    private static InetSocketAddress localEnd(Support.Client client) {
        return new InetSocketAddress(client.socket.getLocalAddress(), client.socket.getLocalPort());
    }

    static Suite tests(Transport transport) {
        return suite("Request.remoteAddress() (" + transport + ")",
                test("is the client socket's local address and port, without a name", () -> {
                    var recorder = new Recorder();
                    try (var server = transport.start(recorder); var client = transport.client(server.port())) {
                        var reply = client.send(Support.get("/")).read();
                        assertEquals(200, reply.status());
                        var expected = localEnd(client);
                        assertEquals("127.0.0.1 " + expected.getPort(), reply.text());
                        var remote = recorder.seen.peek();
                        assertEquals(expected, remote);
                        assertTrue(remote.getAddress() instanceof Inet4Address, String.valueOf(remote));
                        assertFalse(remote.isUnresolved());
                        assertEquals("127.0.0.1", remote.getHostString(), "no host name was looked up");
                        assertEquals("/127.0.0.1:" + expected.getPort(), remote.toString());
                    }
                }),
                test("two requests on one connection see the same value", () -> {
                    var recorder = new Recorder();
                    try (var server = transport.start(recorder); var client = transport.client(server.port())) {
                        var first = client.send(Support.get("/a")).read();
                        var second = client.send(Support.get("/b")).read();
                        assertEquals(first.text(), second.text());
                        var seen = new ArrayList<>(recorder.seen);
                        assertEquals(2, seen.size());
                        assertEquals(localEnd(client), seen.get(0));
                        assertEquals(seen.get(0), seen.get(1));
                    }
                }),
                test("two connections have different ports", () -> {
                    var recorder = new Recorder();
                    try (var server = transport.start(recorder);
                            var one = transport.client(server.port());
                            var two = transport.client(server.port())) {
                        var first = one.send(Support.get("/")).read().text();
                        var second = two.send(Support.get("/")).read().text();
                        assertEquals("127.0.0.1 " + one.socket.getLocalPort(), first);
                        assertEquals("127.0.0.1 " + two.socket.getLocalPort(), second);
                        assertTrue(one.socket.getLocalPort() != two.socket.getLocalPort());
                        var seen = new ArrayList<>(recorder.seen);
                        assertEquals(List.of(localEnd(one), localEnd(two)), seen);
                    }
                }));
    }

    /** Criterion 2: no name lookup in the main code (comments may name what the code does not call). */
    static Suite sourceRules() {
        return suite("Request.remoteAddress(): source rules",
                test("the main code calls no getByName, getAllByName, getHostName or getCanonicalHostName", () -> {
                    var sources = SourceRulesTests.mainSources();
                    assertTrue(sources.size() > 10, "found " + sources.size() + " files");
                    for (var file : sources) {
                        var text = Files.readString(file).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
                        for (var forbidden : List.of("getByName", "getAllByName", "getHostName", "getCanonicalHostName",
                                "java.lang.reflect", "java.lang.invoke")) {
                            assertFalse(text.contains(forbidden), file + " mentions " + forbidden);
                        }
                    }
                }));
    }
}
