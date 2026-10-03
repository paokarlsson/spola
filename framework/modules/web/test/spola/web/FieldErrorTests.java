package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import spola.json.Json;
import spola.json.JsonArray;
import spola.json.JsonObject;
import spola.json.JsonValue;
import spola.json.codec.JsonMappingException;
import spola.test.Suite;
import spola.validation.Validated;
import spola.validation.ValidationException;
import spola.validation.Violation;

/** Criteria 3 and 10 (without a server): FieldError.code and WebException.invalid. */
final class FieldErrorTests {

    private FieldErrorTests() {
    }

    static final List<String> INVALID_CODES = List.of("", "not blank", "tab\tcode", "nul\u0000", "esc\u001b[2J", "del\u007f",
            "å", "nbsp ", "line\nbreak", "x".repeat(101));

    static Suite tests() {
        return suite("FieldError with a code, and WebException.invalid",
                test("new FieldError(path, message) has an empty code", () -> {
                    var error = new Problem.FieldError("$.a", "m");
                    assertEquals(Optional.empty(), error.code());
                    assertEquals(new Problem.FieldError("$.a", "m", Optional.empty()), error);
                }),
                test("toJson leaves out a missing code and writes a present one last", () -> {
                    assertEquals("{\"path\":\"$.a\",\"message\":\"m\"}", Json.write(new Problem.FieldError("$.a", "m").toJson()));
                    assertEquals("{\"path\":\"$.a\",\"message\":\"m\",\"code\":\"notBlank\"}",
                            Json.write(new Problem.FieldError("$.a", "m", Optional.of("notBlank")).toJson()));
                    var problem = Problem.of(400).withErrors(List.of(new Problem.FieldError("$.a", "m"),
                            new Problem.FieldError("$.b", "n", Optional.of("type"))));
                    assertEquals("{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,\"errors\":["
                            + "{\"path\":\"$.a\",\"message\":\"m\"},{\"path\":\"$.b\",\"message\":\"n\",\"code\":\"type\"}]}",
                            Json.write(problem.toJson()));
                }),
                parameterized("an invalid code: IllegalArgumentException", INVALID_CODES,
                        code -> code.length() > 20 ? code.length() + " characters" : code.replace("\n", "\\n"),
                        code -> assertThrows(IllegalArgumentException.class,
                                () -> new Problem.FieldError("$", "m", Optional.of(code)))),
                test("valid codes: 1 to 100 characters of printable ASCII without spaces", () -> {
                    for (var code : List.of("a", "x".repeat(100), "!~", "notBlank", "a.b-c_d:e/f")) {
                        assertEquals(Optional.of(code), new Problem.FieldError("$", "m", Optional.of(code)).code());
                    }
                    assertThrows(NullPointerException.class, () -> new Problem.FieldError("$", "m", null));
                    assertThrows(NullPointerException.class, () -> new Problem.FieldError(null, "m"));
                }),
                test("a mapping error from readJson is exactly the JSON from before (no code)", () -> {
                    var body = "{\"customer\":5,\"email\":\"a@b\",\"lines\":[{\"sku\":true,\"quantity\":\"x\"}]}";
                    var router = Router.builder()
                            .post("/orders", r -> spola.http.Response.text(200, r.readJson(OrderApi.CREATE_ORDER).toString()))
                            .post("/validated", r -> spola.http.Response.text(200,
                                    r.readJson(OrderApi.CREATE_ORDER, OrderApi.CREATE_ORDER_RULES).toString()))
                            .build();
                    try (var server = Support.serve(router)) {
                        var reply = JsonBodyTests.post(server.port(), "/orders", body, "Content-Type: application/json");
                        assertEquals(400, reply.status());
                        assertEquals("{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                                + "\"detail\":\"Request body has 3 problems.\",\"instance\":\"/orders\",\"errors\":["
                                + "{\"path\":\"$.customer\",\"message\":\"expected string but found number\"},"
                                + "{\"path\":\"$.lines[0].sku\",\"message\":\"expected string but found boolean\"},"
                                + "{\"path\":\"$.lines[0].quantity\",\"message\":\"expected int but found string\"}]}",
                                reply.body());
                        // the format of 08b, built from the codec's problems as JsonBody built it then
                        var errors = new ArrayList<JsonValue>();
                        try {
                            OrderApi.CREATE_ORDER.decode(Json.parse(body));
                        } catch (JsonMappingException e) {
                            for (var p : e.problems()) {
                                errors.add(JsonObject.builder().put("path", p.path()).put("message", p.message()).build());
                            }
                        }
                        var before = JsonObject.builder().put("type", "about:blank").put("title", "Bad Request")
                                .put("status", 400).put("detail", "Request body has 3 problems.")
                                .put("instance", "/orders").put("errors", JsonArray.copyOf(errors)).build();
                        assertEquals(Json.write(before), reply.body());
                        var validated = JsonBodyTests.post(server.port(), "/validated", body, "Content-Type: application/json");
                        assertEquals(reply.body().replace("/orders", "/validated"), validated.body());
                    }
                }),
                invalid());
    }

    static final Violation V1 = new Violation("$.customer", "notBlank", "must not be blank");
    static final Violation V2 = new Violation("$.lines[0].quantity", "range", "must be between 1 and 99");

    static Suite invalid() {
        return suite("WebException.invalid",
                test("one violation: singular detail, code in errors", () -> {
                    var e = WebException.invalid(new Validated.Invalid<>(List.of(V1), 1));
                    assertEquals(400, e.status());
                    assertEquals("Bad Request", e.title());
                    assertEquals("The request has 1 validation error.", e.detail().orElseThrow());
                    assertEquals("{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                            + "\"detail\":\"The request has 1 validation error.\",\"instance\":\"/o\",\"errors\":["
                            + "{\"path\":\"$.customer\",\"message\":\"must not be blank\",\"code\":\"notBlank\"}]}",
                            Json.write(e.problem("/o").toJson()));
                }),
                test("two violations: plural detail, in order", () -> {
                    var e = WebException.invalid(new Validated.Invalid<>(List.of(V1, V2), 2));
                    assertEquals("The request has 2 validation errors.", e.detail().orElseThrow());
                    assertEquals(List.of(new Problem.FieldError("$.customer", "must not be blank", Optional.of("notBlank")),
                            new Problem.FieldError("$.lines[0].quantity", "must be between 1 and 99", Optional.of("range"))),
                            e.errors());
                }),
                test("more than 100 counted: the first 100 are listed", () -> {
                    var hundred = new ArrayList<Violation>();
                    for (int i = 0; i < 100; i++) {
                        hundred.add(new Violation("$[" + i + "]", "min", "must be at least 1"));
                    }
                    var e = WebException.invalid(new Validated.Invalid<>(hundred, 250));
                    assertEquals("The request has 250 validation errors. The first 100 are listed.", e.detail().orElseThrow());
                    assertEquals(100, e.errors().size());
                    var exact = WebException.invalid(new Validated.Invalid<>(hundred, 100));
                    assertEquals("The request has 100 validation errors.", exact.detail().orElseThrow());
                }),
                test("invalid(Invalid) and invalid(ValidationException) give the same problem", () -> {
                    for (var invalid : List.of(new Validated.Invalid<Object>(List.of(V1), 1),
                            new Validated.Invalid<Object>(List.of(V1, V2), 2))) {
                        var fromResult = WebException.invalid(invalid);
                        var fromException = WebException.invalid(new ValidationException(invalid));
                        assertEquals(fromResult.problem("/p"), fromException.problem("/p"));
                        assertEquals(fromResult.errors(), fromException.errors());
                        assertEquals(fromResult.detail(), fromException.detail());
                        assertEquals(Json.write(fromResult.problem(null).toJson()),
                                Json.write(fromException.problem(null).toJson()));
                    }
                    assertThrows(NullPointerException.class, () -> WebException.invalid((Validated.Invalid<?>) null));
                    assertThrows(NullPointerException.class, () -> WebException.invalid((ValidationException) null));
                }),
                test("a violation code that is no FieldError code: IllegalArgumentException", () -> {
                    var odd = new Violation("$", "x".repeat(101), "m");
                    assertThrows(IllegalArgumentException.class,
                            () -> WebException.invalid(new Validated.Invalid<>(List.of(odd), 1)));
                }));
    }
}
