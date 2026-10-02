package spola.json;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.Optional;
import spola.test.Suite;

/** The readers on the tree: the value as the expected type, or an error with the path. */
final class AccessTests {

    private AccessTests() {
    }

    static final String ORDER = """
            {
              "id": "A-17",
              "paid": true,
              "note": null,
              "address": {"city": "Lund", "zip": 22100},
              "items": [
                {"sku": "x-1", "qty": 1},
                {"sku": "x-2", "qty": 2},
                {"sku": 3, "qty": 1.5},
                {"qty": 4}
              ],
              "weird name": {"a.b": [true]}
            }
            """;

    private static JsonObject order() {
        return (JsonObject) Json.parse(ORDER);
    }

    static Suite tests() {
        return suite("readers",
                test("readers return the value as the expected type", () -> {
                    var order = order();
                    assertEquals("A-17", order.string("id"));
                    assertEquals(true, order.bool("paid"));
                    assertEquals("Lund", order.object("address").string("city"));
                    assertEquals(22100, order.object("address").number("zip").intValueExact());
                    assertEquals(4, order.array("items").size());
                    assertEquals("x-2", order.array("items").object(1).string("sku"));
                    assertEquals(JsonString.of("A-17"), order.get("id"));
                    assertEquals(JsonNull.INSTANCE, order.get("note"));
                    assertTrue(order.contains("note"));
                    assertEquals(JsonNumber.of(1), order.array("items").object(0).get("qty"));
                    assertEquals(JsonString.of("x-1"), order.array("items").get(0) instanceof JsonObject o
                            ? o.get("sku") : null);
                }),
                test("a wrong type names the path, the expected and the actual type", () -> {
                    var e = assertThrows(JsonAccessException.class,
                            () -> order().array("items").object(2).string("sku"));
                    assertEquals("$.items[2].sku: expected string but found number", e.getMessage());
                    assertEquals("$.items[2].sku", e.path());
                    assertEquals("string", e.expected());
                    assertEquals("number", e.actual());
                    var object = assertThrows(JsonAccessException.class, () -> order().object("items"));
                    assertEquals("$.items: expected object but found array", object.getMessage());
                    var bool = assertThrows(JsonAccessException.class, () -> order().bool("id"));
                    assertEquals("$.id: expected boolean but found string", bool.getMessage());
                    var nul = assertThrows(JsonAccessException.class, () -> order().string("note"));
                    assertEquals("$.note: expected string but found null", nul.getMessage());
                    var number = assertThrows(JsonAccessException.class, () -> order().object("address").number("city"));
                    assertEquals("$.address.city: expected number but found string", number.getMessage());
                    var array = assertThrows(JsonAccessException.class, () -> order().array("items").array(0));
                    assertEquals("$.items[0]: expected array but found object", array.getMessage());
                }),
                test("a missing member and an index out of bounds name the path", () -> {
                    var e = assertThrows(JsonAccessException.class,
                            () -> order().array("items").object(3).string("sku"));
                    assertEquals("$.items[3].sku: expected string but the member is missing", e.getMessage());
                    assertEquals(JsonAccessException.MISSING, e.actual());
                    var city = assertThrows(JsonAccessException.class, () -> order().object("shipping").string("city"));
                    assertEquals("$.shipping: expected object but the member is missing", city.getMessage());
                    var bounds = assertThrows(JsonAccessException.class, () -> order().array("items").object(4));
                    assertEquals("$.items[4]: expected object but the index is out of bounds (the array has 4 elements)",
                            bounds.getMessage());
                    assertEquals(JsonAccessException.OUT_OF_BOUNDS, bounds.actual());
                    assertThrows(JsonAccessException.class, () -> order().array("items").get(-1));
                    assertThrows(JsonAccessException.class, () -> order().get("nothing"));
                }),
                test("names that are not identifiers are quoted and escaped in the path", () -> {
                    var e = assertThrows(JsonAccessException.class,
                            () -> order().object("weird name").array("a.b").string(0));
                    assertEquals("$[\"weird name\"][\"a.b\"][0]: expected string but found boolean", e.getMessage());
                    var control = assertThrows(JsonAccessException.class, () -> order().string("a\n\u202Eb"));
                    assertEquals("$[\"a\\u000a\\u202eb\"]: expected string but the member is missing", control.getMessage());
                }),
                test("optional: empty when missing or null, the value otherwise, with its path", () -> {
                    var order = order();
                    assertEquals(Optional.empty(), order.optional("note"));
                    assertEquals(Optional.empty(), order.optional("missing"));
                    assertEquals(Optional.of(JsonString.of("A-17")), order.optional("id"));
                    var address = (JsonObject) order.optional("address").orElseThrow();
                    assertEquals("$.address", address.path());
                    var e = assertThrows(JsonAccessException.class, () -> address.number("city"));
                    assertEquals("$.address.city", e.path());
                }),
                test("the path is not part of the value", () -> {
                    var order = order();
                    var reached = order.array("items").object(1);
                    var parsed = Json.parse("{\"sku\": \"x-2\", \"qty\": 2}");
                    assertEquals("$.items[1]", reached.path());
                    assertEquals("$", ((JsonObject) parsed).path());
                    assertEquals(parsed, reached);
                    assertEquals(parsed.hashCode(), reached.hashCode());
                    assertEquals(Json.write(parsed), Json.write(reached));
                    // values taken from members() are the stored ones, with path $
                    assertEquals("$", ((JsonObject) order.members().get("address")).path());
                    assertEquals("$.address", order.object("address").path());
                }),
                test("the tree cannot be changed", () -> {
                    var order = order();
                    assertThrows(UnsupportedOperationException.class, () -> order.members().put("x", JsonNull.INSTANCE));
                    assertThrows(UnsupportedOperationException.class, () -> order.members().remove("id"));
                    assertThrows(UnsupportedOperationException.class, () -> order.array("items").values().clear());
                    var builder = JsonObject.builder().put("a", 1);
                    var built = builder.build();
                    builder.put("b", 2);
                    assertEquals(1, built.size());
                    var list = new java.util.ArrayList<JsonValue>(java.util.List.of(JsonNumber.of(1)));
                    var array = JsonArray.copyOf(list);
                    list.add(JsonNumber.of(2));
                    assertEquals(1, array.size());
                }),
                test("a switch over JsonValue covers every type without default", () -> {
                    var kinds = new StringBuilder();
                    for (var value : ((JsonArray) Json.parse("[{}, [], \"s\", 1, true, null]")).values()) {
                        kinds.append(switch (value) {
                            case JsonObject o -> "object" + o.size();
                            case JsonArray a -> "array" + a.size();
                            case JsonString s -> "string:" + s.value();
                            case JsonNumber n -> "number:" + n.text();
                            case JsonBoolean b -> "boolean:" + b.value();
                            case JsonNull _ -> "null";
                        }).append(' ');
                    }
                    assertEquals("object0 array0 string:s number:1 boolean:true null ", kinds.toString());
                }));
    }
}
