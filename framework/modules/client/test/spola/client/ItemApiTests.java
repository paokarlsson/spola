package spola.client;

import static spola.client.ItemApp.ITEM;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.URI;
import spola.client.ItemApp.Item;
import spola.http.HttpServer;
import spola.http.HttpServerOptions;
import spola.test.Suite;

final class ItemApiTests {

    private ItemApiTests() {
    }

    static Suite tests() {
        return suite("item API through a real server",
                test("create, show, delete, then 404 and 400", () -> {
                    try (var server = HttpServer.start(HttpServerOptions.DEFAULT.withPort(0), new ItemApp().router());
                            var client = RestClient.builder()
                                    .baseUri(URI.create("http://127.0.0.1:" + server.port()))
                                    .build()) {
                        Item created = client.post("/items").json(new Item(0, "tea", 2), ITEM).retrieve(ITEM);
                        assertEquals(new Item(1, "tea", 2), created);
                        assertEquals(created, client.get("/items/1").retrieve(ITEM));

                        assertEquals(204, client.delete("/items/1").retrieveBodiless());

                        var missing = assertThrows(RestClientResponseException.class,
                                () -> client.get("/items/1").retrieve(ITEM));
                        assertEquals(404, missing.status());
                        assertTrue(missing.bodyExcerpt().contains("\"detail\":\"No item 1.\""), missing.bodyExcerpt());

                        client.post("/items").json(new Item(0, "milk", 1), ITEM).retrieve(ITEM);
                        var invalid = assertThrows(RestClientResponseException.class,
                                () -> client.put("/items/2").json(new Item(0, "milk", 0), ITEM).retrieve(ITEM));
                        assertEquals(400, invalid.status());
                        assertTrue(invalid.bodyExcerpt().contains("\"errors\":[{\"path\":\"$.quantity\""),
                                invalid.bodyExcerpt());
                    }
                }));
    }
}
