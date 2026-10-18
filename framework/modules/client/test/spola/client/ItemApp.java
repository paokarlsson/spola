package spola.client;

import static spola.json.codec.JsonCodec.field;
import static spola.json.codec.JsonCodecs.INT;
import static spola.json.codec.JsonCodecs.LONG;
import static spola.json.codec.JsonCodecs.STRING;
import static spola.validation.Constraints.notBlank;
import static spola.validation.Constraints.range;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import spola.http.Response;
import spola.json.codec.JsonCodec;
import spola.validation.Validator;
import spola.web.Param;
import spola.web.Responses;
import spola.web.Router;
import spola.web.WebException;
import spola.web.WebRequest;

// the app's router, as in its main code
final class ItemApp {

    record Item(long id, String name, int quantity) {
    }

    // the id is the server's: a client may leave it out
    static final JsonCodec<Item> ITEM = JsonCodec.object(Item::new,
            field("id", LONG, Item::id).orElse(0L),
            field("name", STRING, Item::name),
            field("quantity", INT, Item::quantity));

    static final Validator<Item> ITEM_RULES = Validator.<Item>builder()
            .field("name", Item::name, notBlank())
            .field("quantity", Item::quantity, range(1, 99))
            .build();

    private final Map<Long, Item> items = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    Response show(WebRequest request) {
        return Responses.json(request, 200, ITEM, find(request.pathParam("id", Param.LONG)));
    }

    Response create(WebRequest request) throws IOException {
        var input = request.readJson(ITEM);
        var item = new Item(ids.incrementAndGet(), input.name(), input.quantity());
        items.put(item.id(), item);
        return Responses.created(request, "/items/" + item.id(), ITEM, item);
    }

    Response update(WebRequest request) throws IOException {
        long id = request.pathParam("id", Param.LONG);
        find(id);
        var input = request.readJson(ITEM, ITEM_RULES); // valid, or 400 with errors
        var item = new Item(id, input.name(), input.quantity());
        items.put(id, item);
        return Responses.json(request, 200, ITEM, item);
    }

    Response remove(WebRequest request) {
        long id = request.pathParam("id", Param.LONG);
        if (items.remove(id) == null) {
            throw new WebException(404, "No item " + id + ".");
        }
        return Responses.noContent();
    }

    private Item find(long id) {
        var item = items.get(id);
        if (item == null) {
            throw new WebException(404, "No item " + id + ".");
        }
        return item;
    }

    Router router() {
        return Router.builder()
                .get("/items/{id}", this::show)
                .post("/items", this::create)
                .put("/items/{id}", this::update)
                .delete("/items/{id}", this::remove)
                .build();
    }
}
