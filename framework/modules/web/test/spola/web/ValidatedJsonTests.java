package spola.web;

import static spola.json.codec.JsonCodecs.INT;
import static spola.json.codec.JsonCodecs.list;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertSame;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import spola.http.HttpServer;
import spola.http.Response;
import spola.json.Json;
import spola.json.JsonLimits;
import spola.json.JsonObject;
import spola.json.codec.JsonCodec;
import spola.test.Suite;
import spola.validation.Constraints;
import spola.validation.ValidationException;
import spola.validation.Validator;

/** Criteria 4, 9 (JSON) and 10: readJson with a validator, through a server and HttpClient. */
final class ValidatedJsonTests {

    private ValidatedJsonTests() {
    }

    /** POST {@code body} with {@code contentType} (none if {@code null}) through HttpClient. */
    static HttpResponse<String> post(HttpClient client, HttpServer server, String target, String body,
            String contentType) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + target))
                .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static JsonObject json(HttpResponse<String> response) {
        return (JsonObject) Json.parse(response.body());
    }

    static final String VALID = "{\"customer\":\"Ada\",\"email\":\"ada@example.com\","
            + "\"lines\":[{\"sku\":\"ABC-1234\",\"quantity\":2}]}";

    /** A validator that counts its calls and then runs the README's rules. */
    static final AtomicInteger CALLS = new AtomicInteger();

    static final Validator<OrderApi.CreateOrder> COUNTED = (order, errors) -> {
        CALLS.incrementAndGet();
        OrderApi.CREATE_ORDER_RULES.validate(order, errors);
    };

    static final JsonCodec<List<Integer>> INTS = list(INT);

    static final Validator<List<Integer>> POSITIVE = Validator.<List<Integer>>builder()
            .include((values, errors) -> {
                for (int i = 0; i < values.size(); i++) {
                    if (values.get(i) < 1) {
                        errors.atIndex(i).reject("min", "must be at least 1");
                    }
                }
            })
            .build();

    static final IllegalStateException BOOM = new IllegalStateException("validator failed on SECRET-BOOM");

    static final Validator<Object> THROWS = (value, errors) -> {
        throw BOOM;
    };

    record Login(String user, String password) {
    }

    static final JsonCodec<Login> LOGIN = JsonCodec.object(Login::new,
            JsonCodec.field("user", spola.json.codec.JsonCodecs.STRING, Login::user),
            JsonCodec.field("password", spola.json.codec.JsonCodecs.STRING, Login::password));

    static final Validator<Login> LOGIN_RULES = Validator.<Login>builder()
            .field("password", Login::password, Constraints.matches(Pattern.compile("[a-z]{12,}")))
            .build();

    static final String MARKER = "MARKER-pw-9Zq";

    /**
     * A request that must get the same response with and without a validator.
     *
     * @param name        what it shows
     * @param body        the body
     * @param contentType the Content-Type, or {@code null}
     * @param status      the expected status
     */
    record Same(String name, String body, String contentType, int status) {
        @Override
        public String toString() {
            return name;
        }
    }

    static final List<Same> SAME = List.of(
            new Same("no Content-Type: 415", VALID, null, 415),
            new Same("text/plain: 415", VALID, "text/plain", 415),
            new Same("charset=iso-8859-1: 415", VALID, "application/json; charset=iso-8859-1", 415),
            new Same("an empty body: 400", "", "application/json", 400),
            new Same("over the router's maxInputSize: 413", "[" + "1,".repeat(600) + "1]", "application/json", 413),
            new Same("not JSON: 400", "{\"customer\":", "application/json", 400),
            new Same("a mapping problem: 400", "{\"customer\":\"Ada\",\"email\":7,\"lines\":[]}", "application/json", 400),
            new Same("a missing member: 400", "{\"customer\":\"\",\"email\":\"x\"}", "application/json", 400));

    static Suite tests() {
        var server = new AtomicReference<HttpServer>();
        var client = new AtomicReference<HttpClient>();
        var log = new Support.CapturedLog();
        return suite("readJson with a validator",
                test("a valid value reaches the handler", () -> {
                    CALLS.set(0);
                    var reply = post(client.get(), server.get(), "/counted", VALID, "application/json");
                    assertEquals(200, reply.statusCode(), reply.body());
                    assertEquals("Ada|ada@example.com|1", reply.body());
                    assertEquals(1, CALLS.get());
                }),
                test("errors in several fields and a nested list: 400 with every violation in order", () -> {
                    var body = "{\"customer\":\" \",\"email\":\"nope\",\"lines\":["
                            + "{\"sku\":\"ABC-1234\",\"quantity\":0},{\"sku\":\"\",\"quantity\":5}]}";
                    var reply = post(client.get(), server.get(), "/counted", body, "application/json");
                    assertEquals(400, reply.statusCode());
                    assertEquals("application/problem+json", reply.headers().firstValue("Content-Type").orElseThrow());
                    assertEquals("{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                            + "\"detail\":\"The request has 5 validation errors.\",\"instance\":\"/counted\",\"errors\":["
                            + "{\"path\":\"$.customer\",\"message\":\"must not be blank\",\"code\":\"notBlank\"},"
                            + "{\"path\":\"$.email\",\"message\":\"must be a well-formed email address\",\"code\":\"email\"},"
                            + "{\"path\":\"$.lines[0].quantity\",\"message\":\"must be between 1 and 99\",\"code\":\"range\"},"
                            + "{\"path\":\"$.lines[1].sku\",\"message\":\"must not be blank\",\"code\":\"notBlank\"},"
                            + "{\"path\":\"$.lines[1].sku\",\"message\":\"must match the required format\","
                            + "\"code\":\"pattern\"}]}", reply.body());
                }),
                parameterized("the same response as without a validator, and the validator is not called", SAME, c -> {
                    CALLS.set(0);
                    var plain = post(client.get(), server.get(), "/plain", c.body(), c.contentType());
                    var counted = post(client.get(), server.get(), "/counted", c.body(), c.contentType());
                    assertEquals(c.status(), plain.statusCode(), plain.body());
                    assertEquals(plain.statusCode(), counted.statusCode());
                    assertEquals(plain.body().replace("\"instance\":\"/plain\"", "\"instance\":\"/counted\""),
                            counted.body());
                    assertEquals(plain.headers().allValues("Accept"), counted.headers().allValues("Accept"));
                    assertEquals(plain.headers().allValues("Content-Type"), counted.headers().allValues("Content-Type"));
                    assertEquals(0, CALLS.get(), "the validator was called");
                }),
                test("a mapping problem is answered with the mapping errors, without codes", () -> {
                    CALLS.set(0);
                    var reply = post(client.get(), server.get(), "/counted",
                            "{\"customer\":\" \",\"email\":7,\"lines\":[]}", "application/json");
                    assertEquals("{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                            + "\"detail\":\"Request body has 1 problem.\",\"instance\":\"/counted\",\"errors\":["
                            + "{\"path\":\"$.email\",\"message\":\"expected string but found number\"}]}", reply.body());
                    assertEquals(0, CALLS.get());
                }),
                test("250 invalid elements: 100 errors and the count", () -> {
                    var body = new StringBuilder("[");
                    for (int i = 0; i < 250; i++) {
                        body.append(i == 0 ? "" : ",").append(0);
                    }
                    var reply = post(client.get(), server.get(), "/ints", body.append(']').toString(), "application/json");
                    assertEquals(400, reply.statusCode());
                    var problem = json(reply);
                    assertEquals("The request has 250 validation errors. The first 100 are listed.", problem.string("detail"));
                    var errors = problem.array("errors").values();
                    assertEquals(100, errors.size());
                    for (int i = 0; i < 100; i++) {
                        assertEquals("{\"path\":\"$[" + i + "]\",\"message\":\"must be at least 1\",\"code\":\"min\"}",
                                Json.write(errors.get(i)));
                    }
                }),
                test("an exception from the validator reaches a mapper as the same instance", () -> {
                    var mapped = new AtomicReference<Throwable>();
                    var router = Router.builder()
                            .post("/throws", r -> Response.text(200, r.readJson(OrderApi.CREATE_ORDER, THROWS).toString()))
                            .onException(IllegalStateException.class, (e, r) -> {
                                mapped.set(e);
                                return Response.text(409, "mapped");
                            })
                            .build();
                    try (var own = Support.serve(router)) {
                        var reply = post(client.get(), own, "/throws", VALID, "application/json");
                        assertEquals(409, reply.statusCode());
                        assertEquals("mapped", reply.body());
                        assertSame(BOOM, mapped.get());
                    }
                }),
                test("an exception from the validator without a mapper: 500 without its message", () -> {
                    var reply = post(client.get(), server.get(), "/throws", VALID, "application/json");
                    assertEquals(500, reply.statusCode());
                    assertFalse(reply.body().contains("SECRET-BOOM"), reply.body());
                    assertFalse(reply.body().contains("validator failed"), reply.body());
                }),
                test("readJson(codec, limits, validator) uses the given limits", () -> {
                    CALLS.set(0);
                    var small = post(client.get(), server.get(), "/limited", VALID, "application/json");
                    assertEquals(413, small.statusCode());
                    assertEquals("Request body is larger than 50 bytes.", json(small).string("detail"));
                    var deep = post(client.get(), server.get(), "/limited", "[[[[1]]]]", "application/json");
                    assertEquals(400, deep.statusCode());
                    assertTrue(json(deep).string("detail").contains("maxDepth (3)"), deep.body());
                    var ok = post(client.get(), server.get(), "/limited-large", "[" + "1,".repeat(600) + "1]",
                            "application/json");
                    assertEquals(200, ok.statusCode(), "over the router's 1 KiB but within the given limit");
                    assertEquals("601", ok.body());
                    assertEquals(0, CALLS.get());
                }),
                test("a rejected password is not echoed in the body, the headers or the log", () -> {
                    var reply = post(client.get(), server.get(), "/login",
                            "{\"user\":\"ada\",\"password\":\"" + MARKER + "\"}", "application/json");
                    assertEquals(400, reply.statusCode());
                    assertEquals("{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                            + "\"detail\":\"The request has 1 validation error.\",\"instance\":\"/login\",\"errors\":["
                            + "{\"path\":\"$.password\",\"message\":\"must match the required format\","
                            + "\"code\":\"pattern\"}]}", reply.body());
                    assertFalse(reply.body().contains(MARKER));
                    assertFalse(reply.headers().map().toString().contains(MARKER), reply.headers().toString());
                    for (var record : log.records()) {
                        assertFalse(record.contains(MARKER), record);
                    }
                }),
                serviceException())
                .beforeAll(() -> {
                    client.set(Support.client());
                    server.set(Support.serve(Router.builder()
                            .jsonLimits(JsonLimits.DEFAULT.withMaxInputSize(1_024))
                            .post("/plain", r -> summary(r.readJson(OrderApi.CREATE_ORDER)))
                            .post("/counted", r -> summary(r.readJson(OrderApi.CREATE_ORDER, COUNTED)))
                            .post("/ints", r -> Response.text(200, r.readJson(INTS, POSITIVE).toString()))
                            .post("/throws", r -> Response.text(200, r.readJson(OrderApi.CREATE_ORDER, THROWS).toString()))
                            .post("/limited", r -> summary(r.readJson(OrderApi.CREATE_ORDER,
                                    JsonLimits.DEFAULT.withMaxInputSize(50).withMaxDepth(3), COUNTED)))
                            .post("/limited-large", r -> Response.text(200, String.valueOf(r.readJson(INTS,
                                    JsonLimits.DEFAULT.withMaxInputSize(4_096), POSITIVE).size())))
                            .post("/login", r -> Response.text(200, r.readJson(LOGIN, LOGIN_RULES).user()))
                            .log(log)
                            .build()));
                })
                .afterAll(() -> {
                    server.get().close();
                    client.get().close();
                });
    }

    static Response summary(OrderApi.CreateOrder order) {
        return Response.text(200, order.customer() + "|" + order.email() + "|" + order.lines().size());
    }

    /** README's mapper for a ValidationException from the service's own code. */
    static Suite serviceException() {
        var bad = new OrderApi.CreateOrder(" ", "ada@example.com", List.of(new OrderApi.OrderLine("ABC-1234", 1)));
        var two = new OrderApi.CreateOrder(" ", "nope", List.of(new OrderApi.OrderLine("ABC-1234", 1)));
        return suite("a ValidationException from service code",
                test("without a mapper: 500, as any other exception", () -> {
                    var router = Router.builder()
                            .post("/svc", r -> Response.text(200, OrderApi.CREATE_ORDER_RULES.require(bad).toString()))
                            .log(new Support.CapturedLog())
                            .build();
                    try (var server = Support.serve(router); var client = Support.client()) {
                        var reply = post(client, server, "/svc", "", null);
                        assertEquals(500, reply.statusCode());
                        assertFalse(reply.body().contains("errors"), reply.body());
                    }
                }),
                test("with the README's mapper: exactly WebException.invalid(e)'s response", () -> {
                    var thrown = new ArrayList<ValidationException>();
                    var router = Router.builder()
                            .post("/svc/{n}", r -> {
                                var order = r.pathParam("n").equals("1") ? bad : two;
                                try {
                                    return Response.text(200, OrderApi.CREATE_ORDER_RULES.require(order).toString());
                                } catch (ValidationException e) {
                                    synchronized (thrown) {
                                        thrown.add(e);
                                    }
                                    throw e;
                                }
                            })
                            .onException(ValidationException.class, (e, r) -> WebException.invalid(e).toResponse(r))
                            .build();
                    try (var server = Support.serve(router); var client = Support.client()) {
                        var one = post(client, server, "/svc/1", "", null);
                        assertEquals(400, one.statusCode());
                        assertEquals("application/problem+json", one.headers().firstValue("Content-Type").orElseThrow());
                        assertEquals(Json.write(WebException.invalid(thrown.get(0)).problem("/svc/1").toJson()), one.body());
                        assertEquals("{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                                + "\"detail\":\"The request has 1 validation error.\",\"instance\":\"/svc/1\",\"errors\":["
                                + "{\"path\":\"$.customer\",\"message\":\"must not be blank\",\"code\":\"notBlank\"}]}",
                                one.body());
                        var other = post(client, server, "/svc/2", "", null);
                        assertEquals(Json.write(WebException.invalid(thrown.get(1)).problem("/svc/2").toJson()),
                                other.body());
                        assertEquals("The request has 2 validation errors.", json(other).string("detail"));
                        var fromResult = WebException.invalid((spola.validation.Validated.Invalid<?>)
                                OrderApi.CREATE_ORDER_RULES.check(two));
                        assertEquals(fromResult.problem("/svc/2"), WebException.invalid(thrown.get(1)).problem("/svc/2"));
                    }
                }));
    }
}
