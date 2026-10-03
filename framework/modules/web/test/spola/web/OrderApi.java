package spola.web;

import static spola.json.codec.JsonCodec.field;
import static spola.json.codec.JsonCodecs.INT;
import static spola.json.codec.JsonCodecs.STRING;
import static spola.json.codec.JsonCodecs.list;
import static spola.validation.Constraints.email;
import static spola.validation.Constraints.matches;
import static spola.validation.Constraints.maxLength;
import static spola.validation.Constraints.notBlank;
import static spola.validation.Constraints.range;
import static spola.validation.Constraints.size;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import spola.http.HttpServer;
import spola.http.HttpServerOptions;
import spola.http.Response;
import spola.json.codec.JsonCodec;
import spola.validation.Validator;
import spola.web.Responses;
import spola.web.Router;
import spola.web.WebRequest;

final class OrderApi {

    record OrderLine(String sku, int quantity) {
    }

    record CreateOrder(String customer, String email, List<OrderLine> lines) {
    }

    static final JsonCodec<OrderLine> ORDER_LINE = JsonCodec.object(OrderLine::new,
            field("sku", STRING, OrderLine::sku),
            field("quantity", INT, OrderLine::quantity));

    static final JsonCodec<CreateOrder> CREATE_ORDER = JsonCodec.object(CreateOrder::new,
            field("customer", STRING, CreateOrder::customer),
            field("email", STRING, CreateOrder::email),
            field("lines", list(ORDER_LINE), CreateOrder::lines));

    static final Validator<OrderLine> ORDER_LINE_RULES = Validator.<OrderLine>builder()
            .field("sku", OrderLine::sku, notBlank(), matches(Pattern.compile("[A-Z]{3}-[0-9]{4}")))
            .field("quantity", OrderLine::quantity, range(1, 99))
            .build();

    static final Validator<CreateOrder> CREATE_ORDER_RULES = Validator.<CreateOrder>builder()
            .field("customer", CreateOrder::customer, notBlank(), maxLength(100))
            .field("email", CreateOrder::email, notBlank(), email())
            .field("lines", CreateOrder::lines, size(1, 50))
            .eachNested("lines", CreateOrder::lines, ORDER_LINE_RULES)
            .build();

    private final Map<Long, CreateOrder> orders = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    Response create(WebRequest request) throws IOException {
        CreateOrder order = request.readJson(CREATE_ORDER, CREATE_ORDER_RULES); // valid, or 400
        long id = ids.incrementAndGet();
        orders.put(id, order);
        return Responses.created(request, "/orders/" + id, CREATE_ORDER, order);
    }

    Router router() {
        return Router.builder()
                .post("/orders", this::create)
                .build();
    }

    public static void main(String[] args) throws Exception {
        var server = HttpServer.start(HttpServerOptions.DEFAULT.withPort(8080), new OrderApi().router());
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    }
}
