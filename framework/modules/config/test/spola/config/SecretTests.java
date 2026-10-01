package spola.config;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import spola.test.Suite;

/** Sensitive keys are masked in error messages and in the diagnostic dump. */
final class SecretTests {

    private SecretTests() {
    }

    static final String SECRET = "hunter2-s3cr3t";

    static String stackTrace(Throwable e) {
        var bytes = new ByteArrayOutputStream();
        e.printStackTrace(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    static Suite tests() {
        return suite("secrets",
                test("an invalid sensitive value is not in the error message or stack trace", () -> {
                    var config = Config.builder()
                            .add(Source.map("defaults", Map.of("db.password", SECRET, "db.port", SECRET)))
                            .sensitive("db.password")
                            .sensitive(key -> key.endsWith(".port"))
                            .build();
                    var e = assertThrows(ConfigException.class, () -> config.bind("db",
                            c -> c.intValue("password") + c.intValue("port")));
                    assertEquals(2, e.problems().size());
                    var trace = stackTrace(e);
                    assertFalse(trace.contains(SECRET), trace);
                    assertTrue(e.getMessage().contains("invalid value for 'db.password' from map defaults: expected int, got '"
                            + Config.MASK + "'"), e.getMessage());
                    assertTrue(e.getMessage().contains("'db.port'"), e.getMessage());
                }),
                test("a non-sensitive invalid value is shown, so the masking is what hides the secret", () -> {
                    var config = Config.builder().add(Source.map("m", Map.of("port", SECRET))).build();
                    var e = assertThrows(ConfigException.class, () -> config.bind("", c -> c.intValue("port")));
                    assertTrue(e.getMessage().contains(SECRET), e.getMessage());
                }),
                test("the dump masks sensitive values and shows the winning source of every key", () -> {
                    var config = Config.builder()
                            .add(Source.map("defaults", Map.of("db.url", "jdbc:h2:mem", "db.password", "default-pw",
                                    "name", "shop")))
                            .add(Source.environment("APP_", Map.of("APP_DB_PASSWORD", SECRET)))
                            .add(Source.arguments("--name=cli\nline"))
                            .sensitive("db.password")
                            .build();
                    var dump = config.describe();
                    assertFalse(dump.contains(SECRET), dump);
                    assertFalse(dump.contains("default-pw"), dump);
                    assertEquals("""
                            active profiles: (none)
                            db.password = ******  [environment (prefix APP_)]
                            db.url = jdbc:h2:mem  [map defaults]
                            name = cli\\nline  [arguments]""", dump);
                    assertEquals(dump, config.toString());
                    assertTrue(config.isSensitive("db.password"));
                }),
                test("a value built from a sensitive value through a placeholder is masked too", () -> {
                    var config = Config.builder()
                            .add(Source.map("m", Map.of("db.password", SECRET, "db.url", "jdbc:x?pw=${db.password}",
                                    "db.label", "${db.url}", "plain", "x")))
                            .sensitive("db.password")
                            .build();
                    assertTrue(config.isSensitive("db.url"));
                    assertTrue(config.isSensitive("db.label"));
                    assertFalse(config.isSensitive("plain"));
                    assertFalse(config.describe().contains(SECRET), config.describe());
                    var e = assertThrows(ConfigException.class, () -> config.bind("db", c -> c.intValue("label")));
                    assertFalse(stackTrace(e).contains(SECRET), e.getMessage());
                }),
                test("placeholder errors in a sensitive value do not show fragments of it", () -> {
                    var e = assertThrows(ConfigException.class, () -> Config.builder()
                            .add(Source.map("m", Map.of("db.password", "pa${ss}word", "other", "${ss}")))
                            .sensitive("db.password")
                            .build());
                    assertEquals(2, e.problems().size());
                    assertTrue(e.getMessage().contains("'db.password' (from map m): placeholder ${"
                            + Config.MASK + "} has no value"), e.getMessage());
                    assertTrue(e.getMessage().contains("'other' (from map m): placeholder ${ss} has no value"),
                            e.getMessage());
                }),
                test("a binding function that throws after problems: its exception (with values) is not kept",
                        () -> {
                            var config = Config.builder()
                                    .add(Source.map("m", Map.of("db.password", SECRET)))
                                    .sensitive("db.password")
                                    .build();
                            var e = assertThrows(ConfigException.class, () -> config.bind("db", c -> {
                                var user = c.string("user");
                                throw new IllegalStateException(user + " / " + c.string("password"));
                            }));
                            assertFalse(stackTrace(e).contains(SECRET), stackTrace(e));
                        }),
                test("an invalid sensitive list element is masked, including the converter's message", () -> {
                    var config = Config.builder()
                            .add(Source.map("m", Map.of("tokens", "1," + SECRET)))
                            .sensitive("tokens")
                            .build();
                    var e = assertThrows(ConfigException.class,
                            () -> config.bind("", c -> c.list("tokens", Converter.INT)));
                    assertFalse(stackTrace(e).contains(SECRET), stackTrace(e));
                }));
    }
}
