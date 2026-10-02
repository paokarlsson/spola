package spola.http;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import spola.test.Suite;

/** A real client ({@link HttpClient}, HTTP/1.1) against the server: GET, POST, streamed POST, HEAD. */
final class ClientTests {

    private ClientTests() {
    }

    /** Echoes what the server saw: method, path, query, framing headers and the body. */
    static Response echo(Request request) throws Exception {
        var body = request.bytes();
        var text = request.method() + " " + request.path() + " query=" + request.query().orElse("-")
                + " length=" + request.headers().first("Content-Length").orElse("-")
                + " te=" + request.headers().first("Transfer-Encoding").orElse("-")
                + " body=" + new String(body, StandardCharsets.UTF_8);
        return Response.text(200, text).withHeader("X-Echo", "yes");
    }

    static Suite tests() {
        return suite("java.net.http.HttpClient",
                test("GET, POST with Content-Length, POST streamed (chunked) and HEAD", () -> {
                    try (var server = Support.start(ClientTests::echo);
                         var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                                 .connectTimeout(Duration.ofSeconds(5)).build()) {
                        var base = "http://127.0.0.1:" + server.port();

                        var get = client.send(HttpRequest.newBuilder(URI.create(base + "/hello?x=1&y=%20")).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                        assertEquals(200, get.statusCode());
                        assertEquals("GET /hello query=x=1&y=%20 length=- te=- body=", get.body());
                        assertEquals(Optional.of("text/plain; charset=utf-8"), get.headers().firstValue("Content-Type"));
                        assertEquals(Optional.of("yes"), get.headers().firstValue("X-Echo"));
                        assertTrue(get.headers().firstValue("Date").isPresent(), "Date header");
                        assertEquals(Optional.of(String.valueOf(get.body().getBytes(StandardCharsets.UTF_8).length)),
                                get.headers().firstValue("Content-Length"));
                        assertEquals(Optional.empty(), get.headers().firstValue("Server"));

                        var post = client.send(HttpRequest.newBuilder(URI.create(base + "/orders"))
                                        .POST(HttpRequest.BodyPublishers.ofString("{\"qty\":2} åäö")).build(),
                                HttpResponse.BodyHandlers.ofString());
                        assertEquals(200, post.statusCode());
                        assertEquals("POST /orders query=- length=" + "{\"qty\":2} åäö".getBytes(StandardCharsets.UTF_8).length
                                + " te=- body={\"qty\":2} åäö", post.body());

                        var data = "streamed ".repeat(5_000).getBytes(StandardCharsets.UTF_8);
                        var streamed = client.send(HttpRequest.newBuilder(URI.create(base + "/upload"))
                                        .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(data)))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                        assertEquals(200, streamed.statusCode());
                        assertEquals("POST /upload query=- length=- te=chunked body=" + "streamed ".repeat(5_000),
                                streamed.body());

                        var head = client.send(HttpRequest.newBuilder(URI.create(base + "/hello?x=1&y=%20"))
                                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                                HttpResponse.BodyHandlers.ofByteArray());
                        assertEquals(200, head.statusCode());
                        assertEquals(0, head.body().length);
                        // The handler saw HEAD instead of GET; the same length for the same-sized text.
                        var expectedForHead = ("HEAD /hello query=x=1&y=%20 length=- te=- body=")
                                .getBytes(StandardCharsets.UTF_8).length;
                        assertEquals(Optional.of(String.valueOf(expectedForHead)),
                                head.headers().firstValue("Content-Length"));
                    }
                }),
                test("HEAD gets exactly the Content-Length of GET and no body", () -> {
                    Handler handler = request -> Response.bytes(200, "application/octet-stream", new byte[12_345]);
                    try (var server = Support.start(handler);
                         var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
                        var uri = URI.create("http://127.0.0.1:" + server.port() + "/file");
                        var get = client.send(HttpRequest.newBuilder(uri).GET().build(),
                                HttpResponse.BodyHandlers.ofByteArray());
                        var head = client.send(HttpRequest.newBuilder(uri).method("HEAD",
                                HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
                        assertEquals(12_345, get.body().length);
                        assertEquals(get.headers().firstValue("Content-Length"), head.headers().firstValue("Content-Length"));
                        assertEquals(Optional.of("12345"), head.headers().firstValue("Content-Length"));
                        assertEquals(0, head.body().length);
                        // The connection is still usable after HEAD: the client reuses it for this GET.
                        var again = client.send(HttpRequest.newBuilder(uri).GET().build(),
                                HttpResponse.BodyHandlers.ofByteArray());
                        assertEquals(12_345, again.body().length);
                    }
                }));
    }
}
