package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.web.ForwardedSupport.TRUSTING;
import static spola.web.ForwardedSupport.xfp;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import spola.http.HttpServer;
import spola.test.Suite;
import spola.web.ForwardedSupport.Case;

/** Criterion 6: {@code X-Forwarded-Proto} from a trusted sender. */
final class ForwardedProtoTests {

    private ForwardedProtoTests() {
    }

    /** A valid value and the scheme it gives. */
    record Row(Case header, String scheme) {
        @Override
        public String toString() {
            return header + " -> " + scheme;
        }
    }

    static final List<Row> VALID = List.of(
            new Row(xfp("https"), "https"),
            new Row(xfp("HTTPS"), "https"),
            new Row(xfp("Https"), "https"),
            new Row(xfp("hTtPs"), "https"),
            new Row(xfp("http"), "http"),
            new Row(xfp("HTTP"), "http"),
            new Row(xfp("\thttps\t"), "https"),
            new Row(new Case("none", new String[0]), "http"));

    static Suite tests() {
        var calls = new AtomicInteger();
        var log = new Support.CapturedLog();
        var server = new AtomicReference<HttpServer>();
        return suite("X-Forwarded-Proto",
                parameterized("from a trusted sender: http or https in any ASCII case, in lower case", VALID, row -> {
                    var reply = ForwardedSupport.send(server.get(), "/who", row.header());
                    assertEquals(200, reply.status(), reply.toString());
                    assertEquals(row.scheme() + " 127.0.0.1 Inet4Address", reply.body());
                }),
                test("https without X-Forwarded-For gives https 127.0.0.1 (the sender)", () -> {
                    assertEquals("https 127.0.0.1 Inet4Address",
                            ForwardedSupport.send(server.get(), "/who", xfp("https")).body());
                }),
                test("both headers: https 203.0.113.7", () -> {
                    var reply = ForwardedSupport.send(server.get(), "/who", new Case("both", "X-Forwarded-Proto: https",
                            "X-Forwarded-For: 203.0.113.7, 10.0.0.2"));
                    assertEquals("https 203.0.113.7 Inet4Address", reply.body());
                }),
                parameterized("anything else: 400, the handler not called, no value in the detail",
                        ForwardedSupport.INVALID_PROTO, header -> {
                            int before = calls.get();
                            var reply = ForwardedSupport.send(server.get(), "/who", header);
                            ForwardedForTests.assertInvalid(reply, "X-Forwarded-Proto", header);
                            assertEquals(before, calls.get(), "the handler was called");
                            assertEquals(List.of(), log.records(), "the filter and the router log nothing");
                        }),
                test("the cases criterion 6 names are there", () -> {
                    var names = ForwardedSupport.INVALID_PROTO.stream().map(Case::toString).toList();
                    for (var name : List.of("two fields: https and http", "X-Forwarded-Proto: \"https, http\"",
                            "X-Forwarded-Proto: \"\"", "X-Forwarded-Proto: \"ftp\"", "X-Forwarded-Proto: \"wss\"",
                            "X-Forwarded-Proto: \"https:\"", "X-Forwarded-Proto: \"httpss\"")) {
                        assertEquals(true, names.contains(name), "missing " + name);
                    }
                }))
                .beforeAll(() -> server.set(Support.serve(ForwardedSupport.whoRouter(TRUSTING, calls, log))))
                .afterAll(() -> {
                    if (server.get() != null) {
                        server.get().close();
                    }
                });
    }
}
