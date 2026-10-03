package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import spola.http.HttpServer;
import spola.http.Response;
import spola.test.Suite;

/** Criterion 9: typed parameters, strict conversion and 400 with the parameter's name. */
final class ParamTests {

    private ParamTests() {
    }

    enum Color { RED, GREEN }

    /** An own type for {@link Param#of}. */
    record Sku(String code) {
        static Sku parse(String text) {
            if (!text.matches("[A-Z]{3}-[0-9]{3}")) {
                throw new IllegalArgumentException("bad sku " + text);
            }
            return new Sku(text);
        }
    }

    static final Map<String, Param<?>> PARAMS = Map.of(
            "STRING", Param.STRING,
            "INT", Param.INT,
            "LONG", Param.LONG,
            "BOOLEAN", Param.BOOLEAN,
            "UUID", Param.UUID,
            "LOCAL_DATE", Param.LOCAL_DATE,
            "BIG_DECIMAL", Param.BIG_DECIMAL,
            "ENUM", Param.enumOf(Color.class),
            "SKU", Param.of("SKU", Sku::parse));

    /**
     * One conversion.
     *
     * @param type     the key in {@link #PARAMS}
     * @param raw      the query value as sent (percent-encoded)
     * @param expected the converted value's toString(), or null if the value is invalid
     */
    record Case(String type, String raw, String expected) {
    }

    static final List<Case> VALID = List.of(
            new Case("STRING", "any%20text%0A", "any text\n"),
            new Case("STRING", "", ""),
            new Case("INT", "42", "42"),
            new Case("INT", "-7", "-7"),
            new Case("INT", "0", "0"),
            new Case("INT", "007", "7"),
            new Case("INT", "2147483647", "2147483647"),
            new Case("INT", "-2147483648", "-2147483648"),
            new Case("LONG", "9223372036854775807", "9223372036854775807"),
            new Case("LONG", "-9223372036854775808", "-9223372036854775808"),
            new Case("BOOLEAN", "true", "true"),
            new Case("BOOLEAN", "false", "false"),
            new Case("UUID", "123e4567-e89b-12d3-a456-426614174000", "123e4567-e89b-12d3-a456-426614174000"),
            new Case("UUID", "123E4567-E89B-12D3-A456-426614174000", "123e4567-e89b-12d3-a456-426614174000"),
            new Case("LOCAL_DATE", "2024-02-29", "2024-02-29"),
            new Case("LOCAL_DATE", "1999-12-31", "1999-12-31"),
            new Case("BIG_DECIMAL", "12.50", "12.50"),
            new Case("BIG_DECIMAL", "-3", "-3"),
            new Case("BIG_DECIMAL", "0.0001", "0.0001"),
            new Case("ENUM", "RED", "RED"),
            new Case("ENUM", "GREEN", "GREEN"),
            new Case("SKU", "ABC-123", "Sku[code=ABC-123]"));

    static final List<Case> INVALID = List.of(
            new Case("INT", "1.0", null),
            new Case("INT", "%205", null),
            new Case("INT", "5%20", null),
            new Case("INT", "2147483648", null),
            new Case("INT", "-2147483649", null),
            new Case("INT", "%2B5", null),
            new Case("INT", "abc", null),
            new Case("INT", "", null),
            new Case("INT", "-", null),
            new Case("INT", "1e3", null),
            new Case("INT", "%D9%A3", null),
            new Case("INT", "0x10", null),
            new Case("LONG", "9223372036854775808", null),
            new Case("LONG", "1.0", null),
            new Case("LONG", "%2010", null),
            new Case("BOOLEAN", "TRUE", null),
            new Case("BOOLEAN", "1", null),
            new Case("BOOLEAN", "yes", null),
            new Case("BOOLEAN", "", null),
            new Case("UUID", "1-1-1-1-1", null),
            new Case("UUID", "123e4567e89b12d3a456426614174000", null),
            new Case("UUID", "123e4567-e89b-12d3-a456-42661417400g", null),
            new Case("UUID", "%20123e4567-e89b-12d3-a456-42661417400", null),
            new Case("LOCAL_DATE", "2024-02-30", null),
            new Case("LOCAL_DATE", "2023-02-29", null),
            new Case("LOCAL_DATE", "2024-2-1", null),
            new Case("LOCAL_DATE", "20240101", null),
            new Case("LOCAL_DATE", "2024-01-01T00:00", null),
            new Case("LOCAL_DATE", "%2B2024-01-01", null),
            new Case("BIG_DECIMAL", "1e5", null),
            new Case("BIG_DECIMAL", ".5", null),
            new Case("BIG_DECIMAL", "1.", null),
            new Case("BIG_DECIMAL", "%2B1", null),
            new Case("BIG_DECIMAL", "NaN", null),
            new Case("BIG_DECIMAL", "1.-5", null),
            new Case("ENUM", "red", null),
            new Case("ENUM", "Red", null),
            new Case("ENUM", "BLUE", null),
            new Case("SKU", "abc-123", null));

    static Suite tests() {
        var server = new AtomicReference<HttpServer>();
        return suite("typed parameters",
                parameterized("valid values convert", VALID, c -> c.type() + " " + c.raw(), c -> {
                    var reply = Support.send(server.get().port(), "GET", "/convert?type=" + c.type() + "&amount=" + c.raw());
                    assertEquals(200, reply.status(), reply.toString());
                    assertEquals(c.expected(), reply.body());
                }),
                parameterized("invalid values give 400 naming the parameter", INVALID, c -> c.type() + " " + c.raw(), c -> {
                    var reply = Support.send(server.get().port(), "GET", "/convert?type=" + c.type() + "&amount=" + c.raw());
                    assertEquals(400, reply.status(), reply.toString());
                    assertEquals("application/problem+json", reply.header("Content-Type"));
                    var detail = reply.json().string("detail");
                    assertTrue(detail.startsWith("Invalid query parameter \"amount\": expected "), detail);
                    assertEquals("Bad Request", reply.json().string("title"));
                }),
                test("STRING has no invalid value: every decoded text is a string", () -> {
                    var reply = Support.send(server.get().port(), "GET", "/convert?type=STRING&amount=%E2%82%AC");
                    assertEquals("€", reply.body());
                }),
                test("the detail quotes at most 40 characters of the value, escaped", () -> {
                    var value = "%0A%1B" + "x".repeat(60);
                    var reply = Support.send(server.get().port(), "GET", "/convert?type=INT&amount=" + value);
                    var detail = reply.json().string("detail");
                    assertEquals("Invalid query parameter \"amount\": expected int, got \"\\x0a\\x1b" + "x".repeat(38)
                            + "...\".", detail);
                    for (char c : detail.toCharArray()) {
                        assertFalse(c < 0x20 || c == 0x7F, "control character in the detail");
                    }
                }),
                test("requiredQuery without the parameter: 400", () -> {
                    var reply = Support.send(server.get().port(), "GET", "/convert?type=INT");
                    assertEquals(400, reply.status());
                    assertEquals("Missing query parameter \"amount\".", reply.json().string("detail"));
                }),
                test("query with a Param: absent is empty, present converts", () -> {
                    assertEquals("Optional.empty", Support.send(server.get().port(), "GET", "/optional").body());
                    assertEquals("Optional[5]", Support.send(server.get().port(), "GET", "/optional?limit=5").body());
                    var bad = Support.send(server.get().port(), "GET", "/optional?limit=five");
                    assertEquals(400, bad.status());
                    assertTrue(bad.json().string("detail").contains("\"limit\""));
                }),
                test("typed path parameters: valid, invalid (400 naming it)", () -> {
                    assertEquals("order 42", Support.send(server.get().port(), "GET", "/orders/42").body());
                    var bad = Support.send(server.get().port(), "GET", "/orders/4x2");
                    assertEquals(400, bad.status());
                    assertEquals("Invalid path parameter \"id\": expected long, got \"4x2\".", bad.json().string("detail"));
                }),
                test("pathParam with a name that is not in the pattern: 500", () -> {
                    var reply = Support.send(server.get().port(), "GET", "/typo/1");
                    assertEquals(500, reply.status());
                    assertEquals("application/problem+json", reply.header("Content-Type"));
                    assertFalse(reply.body().contains("idd"), "the exception's message stays in the log");
                }))
                .beforeAll(() -> server.set(Support.serve(Router.builder()
                        .get("/convert", r -> {
                            var param = PARAMS.get(r.requiredQuery("type", Param.STRING));
                            return Response.text(200, String.valueOf(r.requiredQuery("amount", param)));
                        })
                        .get("/optional", r -> Response.text(200, String.valueOf(r.query("limit", Param.INT))))
                        .get("/orders/{id}", r -> Response.text(200, "order " + r.pathParam("id", Param.LONG)))
                        .get("/typo/{id}", r -> Response.text(200, r.pathParam("idd")))
                        .log(new Support.CapturedLog())
                        .build())))
                .afterAll(() -> server.get().close());
    }
}
