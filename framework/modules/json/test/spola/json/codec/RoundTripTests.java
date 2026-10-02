package spola.json.codec;

import static spola.json.codec.JsonCodecs.DOUBLE;
import static spola.json.codec.JsonCodecs.list;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import spola.json.Json;
import spola.json.JsonObject;
import spola.test.Suite;

/** decode(encode(v)) and read(write(v)) give v back, for a graph that uses every codec and combinator. */
final class RoundTripTests {

    private RoundTripTests() {
    }

    static Suite tests() {
        return suite("round trip",
                test("a graph with every built-in codec and every combinator, and recursion via lazy", () -> {
                    var value = Model.everything();
                    var codec = Model.EVERYTHING;
                    assertEquals(value, codec.decode(codec.encode(value)));
                    assertEquals(value, codec.read(codec.write(value)));
                    assertEquals(value, codec.read(codec.writePretty(value)));
                    assertEquals(value, codec.read(codec.write(value).getBytes(StandardCharsets.UTF_8)));
                }),
                test("empty and absent variants round trip too", () -> {
                    var full = Model.everything();
                    var sparse = new Model.Everything(full.scalars(), full.times(), JsonObject.of(), List.of(),
                            java.util.Set.of(), java.util.Map.of(), Optional.empty(), Optional.empty(), "nick", 3,
                            new Model.Node("alone", List.of()), new Model.Circle(0.5));
                    var codec = Model.EVERYTHING;
                    assertEquals(sparse, codec.decode(codec.encode(sparse)));
                    assertEquals(sparse, codec.read(codec.write(sparse)));
                    var json = (JsonObject) codec.encode(sparse);
                    assertTrue(!json.contains("note"), "an empty optionalField is left out");
                    assertEquals("null", Json.write(json.get("maybe")), "optional(...) writes null");
                }),
                test("the encoded graph has the expected JSON", () -> {
                    var json = (JsonObject) Model.EVERYTHING.encode(Model.everything());
                    assertEquals(List.of("scalars", "times", "extra", "emails", "statuses", "colors", "note", "maybe",
                            "nickname", "retries", "tree", "shape"), List.copyOf(json.members().keySet()));
                    assertEquals("{\"text\":\"héj \\\"quoted\\\" \\n\\u2028 😀\",\"flag\":true,"
                            + "\"count\":-2147483648,\"big\":9223372036854775807,\"ratio\":-0.1,\"price\":1.10,"
                            + "\"huge\":-123456789012345678901234567890,\"id\":\"123e4567-e89b-12d3-a456-426614174000\"}",
                            Json.write(json.get("scalars")));
                    assertEquals("{\"at\":\"2026-10-02T09:18:00.123456789Z\",\"day\":\"2024-02-29\","
                            + "\"time\":\"23:59:59.000000001\",\"local\":\"2026-10-02T11:18\","
                            + "\"offset\":\"2026-10-02T11:18:30+05:30\",\"duration\":\"PT-59M-59.999S\"}",
                            Json.write(json.get("times")));
                    assertEquals("[\"a@b.se\",\"c@d.se\"]", Json.write(json.get("emails")));
                    assertEquals("[\"ON_HOLD\",\"ACTIVE\"]", Json.write(json.get("statuses")));
                    assertEquals("{\"sky\":\"dark-blue\",\"the \\\"rose\\\"\":\"red\"}", Json.write(json.get("colors")));
                    assertEquals("null", Json.write(json.get("nickname")));
                    assertEquals("{\"type\":\"group\",\"label\":\"g\",\"shapes\":[{\"type\":\"circle\",\"radius\":1.5},"
                            + "{\"type\":\"square\",\"side\":2.0},{\"type\":\"group\",\"label\":\"inner\",\"shapes\":[]}]}",
                            Json.write(json.get("shape")));
                }),
                test("a recursive type via lazy round trips at depth 200", () -> {
                    var node = new Model.Node("leaf", List.of());
                    for (int i = 0; i < 200; i++) {
                        node = new Model.Node("n" + i, List.of(node));
                    }
                    assertEquals(node, Model.NODE.read(Model.NODE.write(node)));
                    assertEquals(node, Model.NODE.decode(Model.NODE.encode(node)));
                }),
                test("a lazy codec in a list of doubles is resolved once and shared", () -> {
                    var codec = list(JsonCodecs.lazy(() -> DOUBLE));
                    assertEquals(List.of(1.0, 2.5), codec.read("[1, 2.5]"));
                    assertEquals("[1.0,2.5]", codec.write(List.of(1.0, 2.5)));
                }));
    }
}
