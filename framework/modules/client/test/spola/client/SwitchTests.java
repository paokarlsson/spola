package spola.client;

import static spola.test.Assertions.assertEquals;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.time.Duration;
import spola.http.Response;
import spola.test.Suite;

/** Criterion 16: a switch over a caught RestClientException without default compiles and is exhaustive. */
final class SwitchTests {

    private SwitchTests() {
    }

    static String describe(RestClient client, String path) {
        try {
            return "ok " + client.get(path).retrieveJson();
        } catch (RestClientException e) {
            return switch (e) {
                case RestClientResponseException r -> "status " + r.status() + ": " + r.bodyExcerpt();
                case RestClientTransportException t -> t.timedOut() ? "timed out" : "no response";
            };
        }
    }

    static Suite tests() {
        return suite("switch over RestClientException",
                test("the two cases, without default", () -> {
                    try (var server = Support.serve(request -> request.path().equals("/ok") ? Response.text(200, "1")
                            : Response.text(503, "busy"));
                            var client = RestClient.builder().baseUri(Support.base(server.port()))
                                    .timeout(Duration.ofSeconds(5)).build();
                            var refused = RestClient.builder().baseUri(Support.base(Failures.closedPort())).build()) {
                        assertEquals("ok 1", describe(client, "/ok"));
                        assertEquals("status 503: busy", describe(client, "/busy"));
                        assertEquals("no response", describe(refused, "/x"));
                    }
                }));
    }
}
