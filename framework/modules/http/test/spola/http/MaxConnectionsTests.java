package spola.http;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import spola.test.Suite;

/**
 * {@code maxConnections}: at the limit the server stops accepting; a new connection waits in the
 * backlog (the TCP handshake succeeds, nothing is read) until an open connection closes.
 */
final class MaxConnectionsTests {

    private MaxConnectionsTests() {
    }

    static Suite tests() {
        return suite("maxConnections",
                test("with 5 open idle connections a sixth is not served until one closes", () -> {
                    var options = Support.options().withLimits(HttpLimits.DEFAULT.withMaxConnections(5));
                    var calls = new AtomicInteger();
                    try (var server = Support.start(options, request -> Response.text(200, "#" + calls.incrementAndGet()))) {
                        var open = new ArrayList<Support.Client>();
                        try {
                            for (int i = 0; i < 5; i++) {
                                var c = new Support.Client(server.port());
                                open.add(c);
                                assertEquals(200, c.send(Support.get("/")).read().status());
                            }
                            assertEquals(5, server.openConnections());
                            // the sixth: connected by the operating system, but not served
                            try (var sixth = new Support.Client(server.port())) {
                                sixth.send(Support.get("/sixth"));
                                assertTrue(sixth.silentFor(700), "the sixth connection was served at the limit");
                                assertEquals(5, calls.get());
                                // the five are still served
                                assertEquals(200, open.getFirst().send(Support.get("/")).read().status());
                                // one closes: the sixth is accepted and answered
                                open.removeFirst().close();
                                var reply = sixth.read();
                                assertEquals(200, reply.status());
                            }
                        } finally {
                            for (var c : open) {
                                c.close();
                            }
                        }
                        // afterwards the server works as usual, for many connections in turn
                        for (int i = 0; i < 20; i++) {
                            try (var c = new Support.Client(server.port())) {
                                assertEquals(200, c.send(Support.get("/", "Connection: close")).read().status());
                            }
                        }
                    }
                }));
    }
}
