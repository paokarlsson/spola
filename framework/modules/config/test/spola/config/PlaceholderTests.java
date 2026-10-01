package spola.config;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.Map;
import java.util.Optional;
import spola.test.Suite;

/** Placeholders in values. */
final class PlaceholderTests {

    private PlaceholderTests() {
    }

    static Config config(Map<String, String> values) {
        return Config.builder().add(Source.map("test", values)).build();
    }

    static Suite tests() {
        return suite("placeholders",
                test("with and without default", () -> {
                    var config = config(Map.of(
                            "host", "db.local",
                            "url", "jdbc://${host}:${port:5432}/app",
                            "user", "${missing.user:admin}",
                            "empty", "${missing:}"));
                    assertEquals(Optional.of("jdbc://db.local:5432/app"), config.get("url"));
                    assertEquals(Optional.of("admin"), config.get("user"));
                    assertEquals(Optional.of(""), config.get("empty"));
                }),
                test("a value from a later source is used by a placeholder in an earlier one", () -> {
                    var config = Config.builder()
                            .add(Source.map("defaults", Map.of("url", "http://${host}/")))
                            .add(Source.arguments("--host=example.org"))
                            .build();
                    assertEquals(Optional.of("http://example.org/"), config.get("url"));
                    assertEquals(Optional.of("map defaults"), config.sourceOf("url"));
                }),
                test("chains and placeholders in defaults", () -> {
                    var config = config(Map.of(
                            "a", "${b}!",
                            "b", "${c}",
                            "c", "end",
                            "d", "${nope:${c}-${nope2:x}}"));
                    assertEquals(Optional.of("end!"), config.get("a"));
                    assertEquals(Optional.of("end-x"), config.get("d"));
                }),
                test("$${ is a literal ${", () -> {
                    var config = config(Map.of(
                            "template", "Hello $${name}!",
                            "name", "world",
                            "mixed", "$${name} is ${name}",
                            "dollars", "$$$${x}",
                            "lone", "$ and { and } and $x"));
                    assertEquals(Optional.of("Hello ${name}!"), config.get("template"));
                    assertEquals(Optional.of("${name} is world"), config.get("mixed"));
                    assertEquals(Optional.of("$$${x}"), config.get("dollars"));
                    assertEquals(Optional.of("$ and { and } and $x"), config.get("lone"));
                }),
                test("a cycle a -> b -> a is an error with the whole chain", () -> {
                    var e = assertThrows(ConfigException.class, () -> config(Map.of("a", "${b}", "b", "${a}")));
                    assertTrue(e.getMessage().contains("a -> b -> a"), e.getMessage());
                    assertEquals(1, e.problems().size());
                }),
                test("a longer cycle and a self-reference show their chains", () -> {
                    var e = assertThrows(ConfigException.class, () -> config(Map.of(
                            "a", "x${b}", "b", "${c:none}", "c", "${a}", "self", "${self}")));
                    assertTrue(e.getMessage().contains("a -> b -> c -> a"), e.getMessage());
                    assertTrue(e.getMessage().contains("self -> self"), e.getMessage());
                    assertEquals(2, e.problems().size());
                }),
                test("a placeholder without value or default is an error, all reported", () -> {
                    var e = assertThrows(ConfigException.class, () -> config(Map.of(
                            "a", "${x}", "b", "${y} and ${z}", "c", "${unclosed")));
                    assertEquals(4, e.problems().size());
                    assertTrue(e.getMessage().contains("placeholder ${x} has no value and no default"), e.getMessage());
                    assertTrue(e.getMessage().contains("placeholder ${z} has no value"), e.getMessage());
                    assertTrue(e.getMessage().contains("unclosed placeholder"), e.getMessage());
                    assertTrue(e.getMessage().contains("'a' (from map test)"), e.getMessage());
                }),
                test("an empty placeholder name is an error", () -> {
                    var e = assertThrows(ConfigException.class, () -> config(Map.of("a", "${:x}")));
                    assertTrue(e.getMessage().contains("invalid placeholder name"), e.getMessage());
                }));
    }
}
