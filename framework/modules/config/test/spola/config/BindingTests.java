package spola.config;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import spola.test.Suite;

/** Binding to records, the built-in conversions and collected errors. */
final class BindingTests {

    private BindingTests() {
    }

    enum Mode { FAST, SAFE }

    record Color(int red, int green, int blue) {
        static Color parse(String text) {
            if (!text.matches("#[0-9a-fA-F]{6}")) {
                throw new IllegalArgumentException("not #rrggbb");
            }
            var rgb = Integer.parseInt(text.substring(1), 16);
            return new Color(rgb >> 16, (rgb >> 8) & 0xff, rgb & 0xff);
        }
    }

    record Pool(int size, Duration timeout) {
    }

    record Server(String name, int port, long maxBytes, boolean secure, double ratio, Duration timeout, URI home,
                  Path data, Mode mode, List<String> hosts, List<Integer> ports, Optional<String> banner,
                  Optional<Integer> workers, Color color, Pool pool) {
    }

    record Db(String url, String user, int poolSize) {
        Db {
            Objects.requireNonNull(url, "url");
        }
    }

    static Config config(Map<String, String> values) {
        return Config.builder().add(Source.map("test", values)).build();
    }

    static Suite tests() {
        return suite("binding",
                test("a record with a nested record, built-in types and a custom converter", () -> {
                    var config = Config.builder()
                            .add(Source.map("defaults", Map.ofEntries(
                                    Map.entry("server.name", "shop"),
                                    Map.entry("server.port", " 8080 "),
                                    Map.entry("server.max.bytes", "10000000000"),
                                    Map.entry("server.secure", "TRUE"),
                                    Map.entry("server.ratio", "0.75"),
                                    Map.entry("server.timeout", "30s"),
                                    Map.entry("server.home", "https://example.org/shop"),
                                    Map.entry("server.data", "/var/shop"),
                                    Map.entry("server.mode", "SAFE"),
                                    Map.entry("server.hosts", "a.example, b.example ,c.example"),
                                    Map.entry("server.ports", "80,443"),
                                    Map.entry("server.workers", "4"),
                                    Map.entry("server.color", "#ff8000"),
                                    Map.entry("server.pool.size", "12"),
                                    Map.entry("server.pool.timeout", "PT1M"))))
                            .build();
                    var server = config.bind("server", c -> new Server(
                            c.string("name"),
                            c.intValue("port"),
                            c.longValue("max.bytes"),
                            c.booleanValue("secure"),
                            c.doubleValue("ratio"),
                            c.duration("timeout"),
                            c.uri("home"),
                            c.path("data"),
                            c.enumValue("mode", Mode.class),
                            c.list("hosts"),
                            c.list("ports", Converter.INT),
                            c.optional("banner"),
                            c.optional("workers", Converter.INT),
                            c.value("color", "Color", Color::parse),
                            c.section("pool", p -> new Pool(p.intValue("size"), p.duration("timeout")))));
                    assertEquals(new Server("shop", 8080, 10_000_000_000L, true, 0.75, Duration.ofSeconds(30),
                            URI.create("https://example.org/shop"), Path.of("/var/shop"), Mode.SAFE,
                            List.of("a.example", "b.example", "c.example"), List.of(80, 443), Optional.empty(),
                            Optional.of(4), new Color(255, 128, 0), new Pool(12, Duration.ofMinutes(1))), server);
                }),
                test("defaults are used for missing keys only", () -> {
                    var config = config(Map.of("port", "9000"));
                    var result = config.bind("", c -> List.of(
                            c.intValue("port", 80), c.intValue("other", 80),
                            c.string("name", "x"), c.duration("t", Duration.ofSeconds(1)),
                            c.enumValue("mode", Mode.class, Mode.FAST),
                            c.list("l", Converter.STRING, List.of("d")),
                            c.booleanValue("b", true), c.longValue("n", 7L), c.doubleValue("d", 1.5)));
                    assertEquals(List.of(9000, 80, "x", Duration.ofSeconds(1), Mode.FAST, List.of("d"), true, 7L, 1.5),
                            result);
                }),
                test("durations: units and ISO-8601", () -> {
                    var config = config(Map.of("a", "500ms", "b", "2h", "c", "1d", "d", "-5m", "e", "P1DT2H",
                            "f", "10us", "g", "7ns"));
                    var values = config.bind("", c -> List.of(c.duration("a"), c.duration("b"), c.duration("c"),
                            c.duration("d"), c.duration("e"), c.duration("f"), c.duration("g")));
                    assertEquals(List.of(Duration.ofMillis(500), Duration.ofHours(2), Duration.ofDays(1),
                            Duration.ofMinutes(-5), Duration.ofHours(26), Duration.ofNanos(10_000),
                            Duration.ofNanos(7)), values);
                }),
                test("conversions are strict", () -> {
                    var config = config(Map.of("bool", "yes", "dur", "30", "mode", "safe", "list", "a,,b", "int", "1.5",
                            "empty", ""));
                    var e = assertThrows(ConfigException.class, () -> config.bind("", c -> List.of(
                            c.booleanValue("bool"), c.duration("dur"), c.enumValue("mode", Mode.class),
                            c.list("list"), c.intValue("int"))));
                    assertEquals(5, e.problems().size());
                    assertTrue(e.getMessage().contains("expected Mode, got 'safe'"), e.getMessage());
                    assertTrue(e.getMessage().contains("expected list of String, got 'a,,b' (element 1 is empty)"),
                            e.getMessage());
                    assertEquals(List.of(), config.bind("", c -> c.list("empty")));
                }),
                test("two missing keys and one invalid value give one exception with all three", () -> TempDir.with(dir -> {
                    var file = TempDir.write(dir, "app.properties", "db.pool.size=many\n");
                    var config = Config.builder().add(Source.file(file)).build();
                    var e = assertThrows(ConfigException.class, () -> config.bind("db",
                            c -> new Db(c.string("url"), c.string("user"), c.intValue("pool.size"))));
                    assertEquals(3, e.problems().size());
                    assertEquals(new ConfigProblem.MissingKey("db.url", "String"), e.problems().get(0));
                    assertEquals(new ConfigProblem.MissingKey("db.user", "String"), e.problems().get(1));
                    var invalid = (ConfigProblem.InvalidValue) e.problems().get(2);
                    assertEquals("db.pool.size", invalid.key());
                    assertEquals("int", invalid.expectedType());
                    assertEquals("file " + file.toAbsolutePath().normalize(), invalid.source());
                    assertEquals("many", invalid.value());
                    var message = e.getMessage();
                    assertTrue(message.startsWith("the configuration binding of 'db' has 3 problems:"), message);
                    assertTrue(message.contains("missing key 'db.url' (expected String)"), message);
                    assertTrue(message.contains("missing key 'db.user' (expected String)"), message);
                    assertTrue(message.contains("invalid value for 'db.pool.size' from file "
                            + file.toAbsolutePath().normalize() + ": expected int, got 'many'"), message);
                    assertEquals(0, e.getSuppressed().length, "the record's NullPointerException is dropped");
                    assertEquals(null, e.getCause());
                })),
                test("problems in a nested section are collected in the same report", () -> {
                    var config = config(Map.of("port", "x"));
                    var e = assertThrows(ConfigException.class, () -> config.bind("", c -> List.of(
                            c.intValue("port"),
                            c.section("pool", p -> new Pool(p.intValue("size"), p.duration("timeout"))))));
                    assertEquals(List.of("port", "pool.size", "pool.timeout"),
                            e.problems().stream().map(p -> switch (p) {
                                case ConfigProblem.MissingKey m -> m.key();
                                case ConfigProblem.InvalidValue i -> i.key();
                                default -> "?";
                            }).toList());
                }),
                test("a present but invalid optional is still a problem", () -> {
                    var config = config(Map.of("workers", "four"));
                    var e = assertThrows(ConfigException.class, () -> config.bind("", c -> c.optional("workers",
                            Converter.INT)));
                    assertTrue(e.getMessage().contains("expected int, got 'four'"), e.getMessage());
                }),
                test("the binding function's exception propagates when nothing was wrong with the values", () -> {
                    var config = config(Map.of("a", "1"));
                    var e = assertThrows(IllegalStateException.class, () -> config.bind("", c -> {
                        throw new IllegalStateException("own");
                    }));
                    assertEquals("own", e.getMessage());
                }),
                test("a section cannot be used after its binding has finished", () -> {
                    var config = config(Map.of("a", "1"));
                    var saved = new AtomicReference<Section>();
                    config.bind("", c -> {
                        saved.set(c);
                        return c.has("a");
                    });
                    assertThrows(IllegalStateException.class, () -> saved.get().string("a"));
                }),
                test("has and fullKey follow the prefix", () -> {
                    var config = config(Map.of("db.url", "x"));
                    config.bind("db", c -> {
                        assertTrue(c.has("url"));
                        assertFalse(c.has("user"));
                        assertEquals("db.url", c.fullKey("url"));
                        assertEquals("db", c.prefix());
                        return null;
                    });
                }),
                test("the configuration is immutable and safe to read from many threads", () -> {
                    var config = config(Map.of("a", "1", "b", "${a}2"));
                    assertThrows(UnsupportedOperationException.class, () -> config.keys().clear());
                    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                        var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
                        for (var i = 0; i < 1000; i++) {
                            futures.add(executor.submit(() -> config.bind("", c -> c.intValue("b"))));
                        }
                        for (var future : futures) {
                            assertEquals(12, future.get());
                        }
                    }
                }));
    }
}
