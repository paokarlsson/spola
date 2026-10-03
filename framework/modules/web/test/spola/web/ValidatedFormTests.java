package spola.web;

import static spola.json.codec.JsonCodec.field;
import static spola.json.codec.JsonCodecs.INT;
import static spola.json.codec.JsonCodecs.list;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import spola.http.HttpServer;
import spola.http.Response;
import spola.json.codec.JsonCodec;
import spola.test.Suite;
import spola.validation.Constraints;
import spola.validation.Validator;

/** Criteria 7, 9 (forms) and 11: readForm and bindQuery with a binder and a validator. */
final class ValidatedFormTests {

    private ValidatedFormTests() {
    }

    static final String FORM = "application/x-www-form-urlencoded";

    static HttpResponse<String> send(HttpClient client, HttpServer server, String method, String target, String body,
            String contentType) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + target))
                .timeout(Duration.ofSeconds(20))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(HttpClient client, HttpServer server, String target, String body) throws Exception {
        return send(client, server, "POST", target, body, FORM);
    }

    static final AtomicInteger CALLS = new AtomicInteger();

    static final Validator<SignupApp.Signup> COUNTED = (signup, errors) -> {
        CALLS.incrementAndGet();
        SignupApp.SIGNUP_RULES.validate(signup, errors);
    };

    static final String VALID = "email=ada%40example.com&password=correct+horse+battery&age=36"
            + "&interests=math&interests=poetry&terms=on&_csrf=t0k3n&submit=Sign+up";

    record Search(String text, int page, List<Integer> years) {
    }

    static final FormBinder<Search> SEARCH = f -> new Search(
            f.required("q", Param.STRING),
            f.optional("page", Param.INT).orElse(1),
            f.all("year", Param.INT));

    static final Validator<Search> SEARCH_RULES = Validator.<Search>builder()
            .field("text", Search::text, Constraints.notBlank())
            .field("page", Search::page, Constraints.range(1, 100))
            .each("years", Search::years, Constraints.range(1900, 2100))
            .build();

    static final Param<String> PASSWORD = Param.of("password", text -> {
        if (text.length() < 12) {
            throw new IllegalArgumentException("too short: " + text);
        }
        return text;
    });

    record Login(String user, String password) {
    }

    static final FormBinder<Login> LOGIN = f -> new Login(f.required("user", Param.STRING),
            f.required("password", PASSWORD));

    static final String MARKER = "MRK-f0rm-7x";

    static Suite tests() {
        var server = new AtomicReference<HttpServer>();
        var client = new AtomicReference<HttpClient>();
        var log = new Support.CapturedLog();
        return suite("readForm and bindQuery with a binder and a validator",
                test("a valid form: 201, and unknown fields are ignored", () -> {
                    CALLS.set(0);
                    var reply = post(client.get(), server.get(), "/signup", VALID);
                    assertEquals(201, reply.statusCode(), reply.body());
                    assertEquals("ada@example.com|correct horse battery|Optional[36]|[math, poetry]|true", reply.body());
                    assertEquals(1, CALLS.get());
                }),
                test("binding errors: 400, and the validator is not called", () -> {
                    CALLS.set(0);
                    var reply = post(client.get(), server.get(), "/signup",
                            "password=correct+horse+battery&age=old&terms=yes&terms=on");
                    assertEquals(400, reply.statusCode());
                    assertEquals("application/problem+json", reply.headers().firstValue("Content-Type").orElseThrow());
                    assertEquals("{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                            + "\"detail\":\"Request body has 3 problems.\",\"instance\":\"/signup\",\"errors\":["
                            + "{\"path\":\"$.email\",\"message\":\"is required\",\"code\":\"required\"},"
                            + "{\"path\":\"$.age\",\"message\":\"expected int\",\"code\":\"type\"},"
                            + "{\"path\":\"$.terms\",\"message\":\"must have a single value\",\"code\":\"single\"}]}",
                            reply.body());
                    assertEquals(0, CALLS.get());
                }),
                test("validation errors after a successful binding: 400 with codes", () -> {
                    CALLS.set(0);
                    var reply = post(client.get(), server.get(), "/signup", "email=nope&password=short&age=");
                    assertEquals(400, reply.statusCode());
                    assertEquals("{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                            + "\"detail\":\"The request has 3 validation errors.\",\"instance\":\"/signup\",\"errors\":["
                            + "{\"path\":\"$.email\",\"message\":\"must be a well-formed email address\",\"code\":\"email\"},"
                            + "{\"path\":\"$.password\",\"message\":\"length must be between 12 and 128\",\"code\":\"length\"},"
                            + "{\"path\":\"$.terms\",\"message\":\"must be accepted\",\"code\":\"accepted\"}]}",
                            reply.body());
                    assertEquals(1, CALLS.get());
                }),
                test("415 and 413 as for readForm(), and the validator is not called", () -> {
                    CALLS.set(0);
                    for (var type : List.of("text/plain", "application/json", FORM + "; charset=iso-8859-1")) {
                        var plain = send(client.get(), server.get(), "POST", "/plain", VALID, type);
                        var bound = send(client.get(), server.get(), "POST", "/signup", VALID, type);
                        assertEquals(415, bound.statusCode(), type);
                        assertEquals(plain.body().replace("\"instance\":\"/plain\"", "\"instance\":\"/signup\""),
                                bound.body());
                        assertEquals(List.of(FORM), bound.headers().allValues("Accept"));
                    }
                    var none = send(client.get(), server.get(), "POST", "/signup", VALID, null);
                    assertEquals(415, none.statusCode());
                    var large = VALID + "&pad=" + "x".repeat(2_000);
                    var plain = post(client.get(), server.get(), "/plain", large);
                    var bound = post(client.get(), server.get(), "/signup", large);
                    assertEquals(413, bound.statusCode());
                    assertEquals("Request body is larger than 1024 bytes.", ValidatedJsonTests.json(bound).string("detail"));
                    assertEquals(plain.body().replace("\"instance\":\"/plain\"", "\"instance\":\"/signup\""),
                            bound.body());
                    var invalid = post(client.get(), server.get(), "/signup", "email=%G1");
                    assertEquals(400, invalid.statusCode());
                    assertEquals("The form body is not valid percent-encoded UTF-8.",
                            ValidatedJsonTests.json(invalid).string("detail"));
                    assertEquals(0, CALLS.get());
                }),
                test("the body is read once: readForm(binder) after readForm() and the other way round, 500", () -> {
                    assertEquals(500, post(client.get(), server.get(), "/after-form", VALID).statusCode());
                    assertEquals(500, post(client.get(), server.get(), "/form-after", VALID).statusCode());
                    assertEquals(500, post(client.get(), server.get(), "/bound-twice", VALID).statusCode());
                    var records = String.join("\n", log.records());
                    assertTrue(records.contains("already read by readForm()"), records);
                }),
                test("readForm(binder) without a validator binds only", () -> {
                    var reply = post(client.get(), server.get(), "/bind-only", "email=nope&password=x");
                    assertEquals(201, reply.statusCode(), reply.body());
                    assertEquals("nope|x|Optional.empty|[]|false", reply.body());
                }),
                test("bindQuery on a GET: binding errors, validation errors, a value, and the body untouched", () -> {
                    var errors = send(client.get(), server.get(), "GET", "/search?page=x&year=2000&year=y", null, null);
                    assertEquals(400, errors.statusCode());
                    assertEquals("{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                            + "\"detail\":\"Query has 3 problems.\",\"instance\":\"/search\",\"errors\":["
                            + "{\"path\":\"$.q\",\"message\":\"is required\",\"code\":\"required\"},"
                            + "{\"path\":\"$.page\",\"message\":\"expected int\",\"code\":\"type\"},"
                            + "{\"path\":\"$.year[1]\",\"message\":\"expected int\",\"code\":\"type\"}]}", errors.body());
                    var one = send(client.get(), server.get(), "GET", "/search?q=x&page=1&page=2", null, null);
                    assertEquals("Query has 1 problem.", ValidatedJsonTests.json(one).string("detail"));
                    var invalid = send(client.get(), server.get(), "GET", "/search?q=+&page=0&year=1066", null, null);
                    assertEquals(400, invalid.statusCode());
                    assertEquals("{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                            + "\"detail\":\"The request has 3 validation errors.\",\"instance\":\"/search\",\"errors\":["
                            + "{\"path\":\"$.text\",\"message\":\"must not be blank\",\"code\":\"notBlank\"},"
                            + "{\"path\":\"$.page\",\"message\":\"must be between 1 and 100\",\"code\":\"range\"},"
                            + "{\"path\":\"$.years[0]\",\"message\":\"must be between 1900 and 2100\",\"code\":\"range\"}]}",
                            invalid.body());
                    var ok = send(client.get(), server.get(), "GET", "/search?q=spola&year=2024&year=2025",
                            "q=fromBody&page=7", FORM);
                    assertEquals(200, ok.statusCode(), ok.body());
                    assertEquals("Search[text=spola, page=1, years=[2024, 2025]] body=q=fromBody&page=7", ok.body());
                    var failed = send(client.get(), server.get(), "GET", "/search?page=x", "q=fromBody", FORM);
                    assertEquals(400, failed.statusCode());
                    assertEquals(1, SEARCH_BODY_UNREAD.get(), "the handler read the body after the failure");
                    var plain = send(client.get(), server.get(), "GET", "/search-plain?q=a&page=x", null, null);
                    assertEquals("Query has 1 problem.", ValidatedJsonTests.json(plain).string("detail"));
                    var bad = Support.send(server.get().port(), "GET", "/search-plain?q=%C3%28");
                    assertEquals(400, bad.status(), "an invalid query is the query's 400, before binding");
                    assertEquals("The query is not valid percent-encoded UTF-8.", bad.json().string("detail"));
                }),
                test("a password that does not convert is not echoed in the body, the headers or the log", () -> {
                    var reply = post(client.get(), server.get(), "/login", "user=ada&password=" + MARKER);
                    assertEquals(400, reply.statusCode());
                    assertEquals("{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                            + "\"detail\":\"Request body has 1 problem.\",\"instance\":\"/login\",\"errors\":["
                            + "{\"path\":\"$.password\",\"message\":\"expected password\",\"code\":\"type\"}]}",
                            reply.body());
                    assertFalse(reply.body().contains(MARKER));
                    assertFalse(reply.headers().map().toString().contains(MARKER), reply.headers().toString());
                    var flag = post(client.get(), server.get(), "/signup", VALID.replace("terms=on", "terms=" + MARKER));
                    assertEquals(400, flag.statusCode());
                    assertFalse(flag.body().contains(MARKER), flag.body());
                    var number = post(client.get(), server.get(), "/signup", VALID.replace("age=36", "age=" + MARKER));
                    assertFalse(number.body().contains(MARKER), number.body());
                    for (var record : log.records()) {
                        assertFalse(record.contains(MARKER), record);
                    }
                }),
                concurrency())
                .beforeAll(() -> {
                    client.set(Support.client());
                    server.set(Support.serve(Router.builder()
                            .maxFormSize(1_024)
                            .post("/signup", r -> show(r.readForm(SignupApp.SIGNUP, COUNTED)))
                            .post("/plain", r -> Response.text(200, r.readForm().toString()))
                            .post("/bind-only", r -> show(r.readForm(SignupApp.SIGNUP)))
                            .post("/after-form", r -> {
                                r.readForm();
                                return show(r.readForm(SignupApp.SIGNUP, COUNTED));
                            })
                            .post("/form-after", r -> {
                                r.readForm(SignupApp.SIGNUP);
                                return Response.text(200, r.readForm().toString());
                            })
                            .post("/bound-twice", r -> {
                                r.readForm(SignupApp.SIGNUP, COUNTED);
                                return show(r.withAttribute(ScaleTests.CLIENT, "x").readForm(SignupApp.SIGNUP));
                            })
                            .get("/search", r -> {
                                try {
                                    var search = r.bindQuery(SEARCH, SEARCH_RULES);
                                    return Response.text(200, search + " body=" + new String(r.bytes(),
                                            java.nio.charset.StandardCharsets.US_ASCII));
                                } catch (WebException e) {
                                    if (r.bytes().length > 0) {
                                        SEARCH_BODY_UNREAD.incrementAndGet();
                                    }
                                    throw e;
                                }
                            })
                            .get("/search-plain", r -> Response.text(200, r.bindQuery(SEARCH).toString()))
                            .post("/login", r -> Response.text(200, r.readForm(LOGIN).user()))
                            .log(log)
                            .build()));
                })
                .afterAll(() -> {
                    server.get().close();
                    client.get().close();
                });
    }

    static final AtomicInteger SEARCH_BODY_UNREAD = new AtomicInteger();

    static Response show(SignupApp.Signup signup) {
        return Response.text(201, signup.email() + "|" + signup.password() + "|" + signup.age() + "|"
                + signup.interests() + "|" + signup.terms());
    }

    record Numbers(List<Integer> n) {
    }

    static final FormBinder<Numbers> NUMBERS = f -> new Numbers(f.all("n", Param.INT));

    static final JsonCodec<Numbers> NUMBERS_JSON = JsonCodec.object(Numbers::new, field("n", list(INT), Numbers::n));

    static final Validator<Numbers> NUMBERS_RULES = Validator.<Numbers>builder()
            .each("n", Numbers::n, Constraints.range(0, 1_000))
            .build();

    /** What client {@code i} sends and what it must get back exactly. */
    record Exchange(String target, String contentType, String body, int status, String expected) {
    }

    static Exchange exchange(int i) {
        var values = new ArrayList<String>();
        for (int k = 0; k < 5; k++) {
            values.add(String.valueOf(i + k));
        }
        int bad = i % 5;
        int sum = 5 * i + 10;
        return switch (i % 4) {
            case 0 -> new Exchange("/numbers", FORM, form(values), 200, "sum=" + sum);
            case 1 -> {
                values.set(bad, "x" + i);
                yield new Exchange("/numbers", FORM, form(values), 400, "{\"type\":\"about:blank\",\"title\":"
                        + "\"Bad Request\",\"status\":400,\"detail\":\"Request body has 1 problem.\",\"instance\":"
                        + "\"/numbers\",\"errors\":[{\"path\":\"$.n[" + bad + "]\",\"message\":\"expected int\","
                        + "\"code\":\"type\"}]}");
            }
            case 2 -> {
                values.set(bad, String.valueOf(5_000 + i));
                yield new Exchange("/numbers", FORM, form(values), 400, "{\"type\":\"about:blank\",\"title\":"
                        + "\"Bad Request\",\"status\":400,\"detail\":\"The request has 1 validation error.\",\"instance\":"
                        + "\"/numbers\",\"errors\":[{\"path\":\"$.n[" + bad + "]\",\"message\":\"must be between 0 and 1000\","
                        + "\"code\":\"range\"}]}");
            }
            default -> {
                if (i % 8 == 3) {
                    yield new Exchange("/numbers-json", "application/json", "{\"n\":[" + String.join(",", values) + "]}",
                            200, "sum=" + sum);
                }
                values.set(bad, String.valueOf(-1 - i));
                yield new Exchange("/numbers-json", "application/json", "{\"n\":[" + String.join(",", values) + "]}",
                        400, "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,\"detail\":"
                        + "\"The request has 1 validation error.\",\"instance\":\"/numbers-json\",\"errors\":[{\"path\":"
                        + "\"$.n[" + bad + "]\",\"message\":\"must be between 0 and 1000\",\"code\":\"range\"}]}");
            }
        };
    }

    static String form(List<String> values) {
        var out = new StringBuilder();
        for (var value : values) {
            out.append(out.isEmpty() ? "" : "&").append("n=").append(value);
        }
        return out.toString();
    }

    static Response sum(Numbers numbers) throws InterruptedException {
        Thread.sleep(5); // overlap the requests
        return Response.text(200, "sum=" + numbers.n().stream().mapToInt(Integer::intValue).sum());
    }

    static Suite concurrency() {
        return suite("200 concurrent clients",
                test("a shared static final binder, codec and validator: every client gets exactly its own answer", () -> {
                    var router = Router.builder()
                            .post("/numbers", r -> sum(r.readForm(NUMBERS, NUMBERS_RULES)))
                            .post("/numbers-json", r -> sum(r.readJson(NUMBERS_JSON, NUMBERS_RULES)))
                            .build();
                    try (var server = Support.serve(router); var client = Support.client();
                            var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        var start = new CountDownLatch(1);
                        var futures = new ArrayList<Future<HttpResponse<String>>>();
                        for (int i = 0; i < 200; i++) {
                            var exchange = exchange(i);
                            futures.add(executor.submit(() -> {
                                start.await();
                                return send(client, server, "POST", exchange.target(), exchange.body(),
                                        exchange.contentType());
                            }));
                        }
                        start.countDown();
                        int valid = 0;
                        for (int i = 0; i < 200; i++) {
                            var exchange = exchange(i);
                            var response = futures.get(i).get();
                            assertEquals(exchange.status(), response.statusCode(), "client " + i + ": " + response.body());
                            assertEquals(exchange.expected(), response.body(), "client " + i);
                            if (response.statusCode() == 200) {
                                valid++;
                            }
                        }
                        assertEquals(75, valid);
                    }
                }));
    }
}
