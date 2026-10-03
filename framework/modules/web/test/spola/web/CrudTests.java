package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import spola.http.HttpServer;
import spola.test.Suite;

/** Criterion 5 (and 14): the README's CRUD example through a real server and HttpClient. */
final class CrudTests {

    private CrudTests() {
    }

    static HttpResponse<String> call(java.net.http.HttpClient client, HttpServer server, String method, String target,
            String json) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + target))
                .timeout(Duration.ofSeconds(10));
        if (json == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(json)).header("Content-Type", "application/json");
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Content-Type application/json and a Content-Length that matches the UTF-8 body. */
    static void assertJson(HttpResponse<String> response) {
        assertEquals(Optional.of("application/json"), response.headers().firstValue("Content-Type"));
        assertEquals(Optional.of(String.valueOf(response.body().getBytes(StandardCharsets.UTF_8).length)),
                response.headers().firstValue("Content-Length"));
    }

    static Suite tests() {
        return suite("CRUD with JSON (README example)",
                test("POST 201 with Location, GET 200, PUT 200, DELETE 204, HEAD without a body", () -> {
                    try (var server = Support.serve(new ItemApi().router()); var client = Support.client()) {
                        var created = call(client, server, "POST", "/items", "{\"name\":\"Kaffe – mörk\",\"quantity\":3}");
                        assertEquals(201, created.statusCode(), created.body());
                        assertEquals(Optional.of("/items/1"), created.headers().firstValue("Location"));
                        assertJson(created);
                        assertEquals(new ItemApi.Item(1, "Kaffe – mörk", 3), ItemApi.ITEM.read(created.body()));

                        var shown = call(client, server, "GET", "/items/1", null);
                        assertEquals(200, shown.statusCode());
                        assertJson(shown);
                        assertEquals(new ItemApi.Item(1, "Kaffe – mörk", 3), ItemApi.ITEM.read(shown.body()));
                        assertEquals("{\"id\":1,\"name\":\"Kaffe – mörk\",\"quantity\":3}", shown.body());

                        var head = Support.send(server.port(), "HEAD", "/items/1");
                        assertEquals(200, head.status());
                        assertEquals("application/json", head.header("Content-Type"));
                        assertEquals(shown.headers().firstValue("Content-Length").orElseThrow(), head.header("Content-Length"));
                        assertEquals("", head.body());

                        var updated = call(client, server, "PUT", "/items/1", "{\"id\":99,\"name\":\"Te\",\"quantity\":5}");
                        assertEquals(200, updated.statusCode(), updated.body());
                        assertJson(updated);
                        assertEquals(new ItemApi.Item(1, "Te", 5), ItemApi.ITEM.read(updated.body()));
                        assertEquals(new ItemApi.Item(1, "Te", 5),
                                ItemApi.ITEM.read(call(client, server, "GET", "/items/1", null).body()));

                        var deleted = call(client, server, "DELETE", "/items/1", null);
                        assertEquals(204, deleted.statusCode());
                        assertEquals("", deleted.body());
                        var gone = call(client, server, "GET", "/items/1", null);
                        assertEquals(404, gone.statusCode());
                        assertEquals(Optional.of("application/problem+json"), gone.headers().firstValue("Content-Type"));
                        assertEquals(404, call(client, server, "DELETE", "/items/1", null).statusCode());
                        assertEquals(404, call(client, server, "PUT", "/items/1", "{\"name\":\"x\",\"quantity\":1}")
                                .statusCode());
                    }
                }),
                test("the README shows ItemApi.java as it is compiled and tested here", () -> {
                    var readme = Files.readString(Path.of("modules/web/README.md"));
                    var source = Files.readString(Path.of("modules/web/test/spola/web/ItemApi.java"));
                    var header = "package spola.web;\n\n";
                    assertTrue(source.startsWith(header));
                    var block = "```java\n" + source.substring(header.length()) + "```\n";
                    assertTrue(readme.contains(block), "the README's Spola CRUD example differs from ItemApi.java");
                }));
    }
}
