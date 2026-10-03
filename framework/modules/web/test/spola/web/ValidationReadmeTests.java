package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import spola.test.Suite;

/** Criterion 12: the README's validation examples are OrderApi.java and SignupApp.java, and they work. */
final class ValidationReadmeTests {

    private ValidationReadmeTests() {
    }

    static String readme() throws Exception {
        return Files.readString(Path.of("modules/web/README.md"));
    }

    static void assertShown(String file) throws Exception {
        var source = Files.readString(Path.of("modules/web/test/spola/web/" + file));
        var header = "package spola.web;\n\n";
        assertTrue(source.startsWith(header), file);
        var block = "#### Spola\n\n```java\n" + source.substring(header.length()) + "```\n";
        assertTrue(readme().contains(block), "the README's Spola example differs from " + file);
    }

    static Suite tests() {
        return suite("README validation examples",
                test("the README shows OrderApi.java as it is compiled and tested here", () -> assertShown("OrderApi.java")),
                test("the README shows SignupApp.java as it is compiled and tested here", () -> assertShown("SignupApp.java")),
                test("each Spola example stands next to its Spring Boot counterpart", () -> {
                    var text = readme();
                    int json = text.indexOf("### JSON: `@Valid @RequestBody`");
                    int form = text.indexOf("### Formulär: `@Valid @ModelAttribute` och `BindingResult`");
                    assertTrue(json > 0 && form > json, "both sections, JSON first");
                    var jsonSection = text.substring(json, form);
                    assertTrue(jsonSection.indexOf("#### Spring Boot") < jsonSection.indexOf("#### Spola"));
                    assertTrue(jsonSection.contains("@Valid @RequestBody CreateOrder order"));
                    assertTrue(jsonSection.contains("@NotBlank @Email String email"));
                    var formSection = text.substring(form, text.indexOf("## Mönster och matchning"));
                    assertTrue(formSection.indexOf("#### Spring Boot") < formSection.indexOf("#### Spola"));
                    assertTrue(formSection.contains("@Valid @ModelAttribute SignupForm form, BindingResult result"));
                }),
                test("the comparison table and the deviation table have the new rows", () -> {
                    var text = readme();
                    for (var row : List.of(
                            "| `@Valid @RequestBody CreateOrder order` (Jakarta-annoteringar) |",
                            "| `@Valid @ModelAttribute SignupForm form` + `BindingResult` |",
                            "| `@ModelAttribute` på en `GET` (query till objekt) |",
                            "| `MethodArgumentNotValidException` → 400 |",
                            "| `FieldError.getCode()` |",
                            "| `@ExceptionHandler(ConstraintViolationException.class)` |",
                            "| Inget `BindingResult` |", "| Mappningsfel före valideringsfel |", "| Värden i felsvar |",
                            "| Tomma formulärfält |", "| Flera värden för ett fält |",
                            "| `ValidationException` från egen kod |")) {
                        assertTrue(text.contains(row), "missing row " + row);
                    }
                    assertTrue(text.contains(
                            "onException(ValidationException.class, (e, r) -> WebException.invalid(e).toResponse(r))"));
                }),
                test("the JSON example through a server: 201 for a valid order, 400 with every violation", () -> {
                    try (var server = Support.serve(new OrderApi().router()); var client = Support.client()) {
                        var created = ValidatedJsonTests.post(client, server, "/orders", ValidatedJsonTests.VALID,
                                "application/json");
                        assertEquals(201, created.statusCode(), created.body());
                        assertEquals(Optional.of("/orders/1"), created.headers().firstValue("Location"));
                        var invalid = ValidatedJsonTests.post(client, server, "/orders",
                                "{\"customer\":\"\",\"email\":\"ada@example.com\",\"lines\":[{\"sku\":\"ABC-1234\","
                                        + "\"quantity\":100}]}", "application/json");
                        assertEquals("{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                                + "\"detail\":\"The request has 2 validation errors.\",\"instance\":\"/orders\",\"errors\":["
                                + "{\"path\":\"$.customer\",\"message\":\"must not be blank\",\"code\":\"notBlank\"},"
                                + "{\"path\":\"$.lines[0].quantity\",\"message\":\"must be between 1 and 99\","
                                + "\"code\":\"range\"}]}", invalid.body());
                    }
                }),
                test("the form example through a server: 201, binding errors, validation errors", () -> {
                    try (var server = Support.serve(new SignupApp().router()); var client = Support.client()) {
                        var created = ValidatedFormTests.post(client, server, "/signup", ValidatedFormTests.VALID);
                        assertEquals(201, created.statusCode(), created.body());
                        assertEquals("Welcome, ada@example.com.", created.body());
                        var unbound = ValidatedFormTests.post(client, server, "/signup", "email=a%40b.se&age=x");
                        assertEquals(400, unbound.statusCode());
                        assertEquals("Request body has 2 problems.", ValidatedJsonTests.json(unbound).string("detail"));
                        var invalid = ValidatedFormTests.post(client, server, "/signup",
                                "email=a%40b.se&password=correct+horse+battery&terms=on&age=12");
                        assertEquals("{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                                + "\"detail\":\"The request has 1 validation error.\",\"instance\":\"/signup\",\"errors\":["
                                + "{\"path\":\"$.age\",\"message\":\"must be between 13 and 150\",\"code\":\"range\"}]}",
                                invalid.body());
                    }
                }));
    }
}
