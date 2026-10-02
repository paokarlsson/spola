package spola.json;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertInOrder;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.math.BigDecimal;
import java.util.List;
import spola.test.Suite;

/** The examples of RFC 8259 §13, parsed through every entry point and checked value by value. */
final class RfcExampleTests {

    private RfcExampleTests() {
    }

    /** RFC 8259 §13, the object example, verbatim (including its indentation). */
    static final String OBJECT = """
            {
                    "Image": {
                        "Width":  800,
                        "Height": 600,
                        "Title":  "View from 15th Floor",
                        "Thumbnail": {
                            "Url":    "http://www.example.com/image/481989943",
                            "Height": 125,
                            "Width":  100
                        },
                        "Animated" : false,
                        "IDs": [116, 943, 234, 38793]
                      }
                  }
            """;

    /** RFC 8259 §13, the array example, verbatim. */
    static final String ARRAY = """
            [
                    {
                       "precision": "zip",
                       "Latitude":  37.7668,
                       "Longitude": -122.3959,
                       "Address":   "",
                       "City":      "SAN FRANCISCO",
                       "State":     "CA",
                       "Zip":       "94107",
                       "Country":   "US"
                    },
                    {
                       "precision": "zip",
                       "Latitude":  37.371991,
                       "Longitude": -122.026020,
                       "Address":   "",
                       "City":      "SUNNYVALE",
                       "State":     "CA",
                       "Zip":       "94085",
                       "Country":   "US"
                    }
                  ]
            """;

    static Suite tests() {
        return suite("RFC 8259 section 13 examples",
                test("the object example", () -> {
                    var root = (JsonObject) Inputs.parseAll(OBJECT);
                    assertInOrder(List.of("Image"), List.copyOf(root.members().keySet()));
                    var image = root.object("Image");
                    assertInOrder(List.of("Width", "Height", "Title", "Thumbnail", "Animated", "IDs"),
                            List.copyOf(image.members().keySet()));
                    assertEquals(800, image.number("Width").intValueExact());
                    assertEquals(600, image.number("Height").intValueExact());
                    assertEquals("View from 15th Floor", image.string("Title"));
                    var thumbnail = image.object("Thumbnail");
                    assertEquals("http://www.example.com/image/481989943", thumbnail.string("Url"));
                    assertEquals(125, thumbnail.number("Height").intValueExact());
                    assertEquals(100, thumbnail.number("Width").intValueExact());
                    assertEquals(false, image.bool("Animated"));
                    var ids = image.array("IDs");
                    assertEquals(4, ids.size());
                    assertEquals(116, ids.number(0).intValueExact());
                    assertEquals(943, ids.number(1).intValueExact());
                    assertEquals(234, ids.number(2).intValueExact());
                    assertEquals(38793, ids.number(3).intValueExact());
                    var expected = JsonObject.of("Image", JsonObject.builder()
                            .put("Width", 800)
                            .put("Height", 600)
                            .put("Title", "View from 15th Floor")
                            .put("Thumbnail", JsonObject.builder()
                                    .put("Url", "http://www.example.com/image/481989943")
                                    .put("Height", 125)
                                    .put("Width", 100)
                                    .build())
                            .put("Animated", false)
                            .put("IDs", JsonArray.of(JsonNumber.of(116), JsonNumber.of(943), JsonNumber.of(234),
                                    JsonNumber.of(38793)))
                            .build());
                    assertEquals(expected, root);
                    assertEquals(Json.write(expected), Json.write(root));
                }),
                test("the array example", () -> {
                    var root = (JsonArray) Inputs.parseAll(ARRAY);
                    assertEquals(2, root.size());
                    var first = root.object(0);
                    assertInOrder(List.of("precision", "Latitude", "Longitude", "Address", "City", "State", "Zip",
                            "Country"), List.copyOf(first.members().keySet()));
                    assertEquals("zip", first.string("precision"));
                    assertEquals(new BigDecimal("37.7668"), first.number("Latitude").bigDecimalValue());
                    assertEquals(new BigDecimal("-122.3959"), first.number("Longitude").bigDecimalValue());
                    assertEquals("", first.string("Address"));
                    assertEquals("SAN FRANCISCO", first.string("City"));
                    assertEquals("CA", first.string("State"));
                    assertEquals("94107", first.string("Zip"));
                    assertEquals("US", first.string("Country"));
                    var second = root.object(1);
                    assertEquals(new BigDecimal("37.371991"), second.number("Latitude").bigDecimalValue());
                    // the text is kept exactly, trailing zero included
                    assertEquals("-122.026020", second.number("Longitude").text());
                    assertEquals("SUNNYVALE", second.string("City"));
                    assertEquals("94085", second.string("Zip"));
                    assertEquals(37.371991, second.number("Latitude").doubleValue(), 0.0);
                }),
                test("the top-level examples \"Hello world!\", 42 and true", () -> {
                    assertEquals(JsonString.of("Hello world!"), Inputs.parseAll("\"Hello world!\""));
                    assertEquals(JsonNumber.of(42), Inputs.parseAll("42"));
                    assertEquals(42, ((JsonNumber) Json.parse("42")).intValueExact());
                    assertEquals(JsonBoolean.TRUE, Inputs.parseAll("true"));
                }));
    }
}
