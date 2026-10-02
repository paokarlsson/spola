package spola.config;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import spola.test.Suite;

/** Sources, their priority order, environment rules, files and arguments. */
final class SourceTests {

    private SourceTests() {
    }

    /** The four kinds of source in the priority test, each giving key {@code k} its own value. */
    enum Kind {
        MAP, FILE, ENVIRONMENT, ARGUMENT;

        Source source(Path dir) throws Exception {
            return switch (this) {
                case MAP -> Source.map("defaults", Map.of("k", "map"));
                case FILE -> Source.file(TempDir.write(dir, "app.properties", "k=file\n"));
                case ENVIRONMENT -> Source.environment("APP_", Map.of("APP_K", "environment"));
                case ARGUMENT -> Source.arguments("--k=argument");
            };
        }

        String value() {
            return switch (this) {
                case MAP -> "map";
                case FILE -> "file";
                case ENVIRONMENT -> "environment";
                case ARGUMENT -> "argument";
            };
        }
    }

    record Pair(Kind first, Kind second) {
        @Override
        public String toString() {
            return first + " then " + second;
        }
    }

    static List<Pair> pairs() {
        var pairs = new ArrayList<Pair>();
        for (var a : Kind.values()) {
            for (var b : Kind.values()) {
                if (a != b) {
                    pairs.add(new Pair(a, b));
                }
            }
        }
        return pairs;
    }

    static Suite tests() {
        return suite("sources",
                test("the same key in four sources: the argument, added last, wins", () -> TempDir.with(dir -> {
                    var config = Config.builder()
                            .add(Kind.MAP.source(dir))
                            .add(Kind.FILE.source(dir))
                            .add(Kind.ENVIRONMENT.source(dir))
                            .add(Kind.ARGUMENT.source(dir))
                            .build();
                    assertEquals(Optional.of("argument"), config.get("k"));
                    assertEquals(Optional.of("arguments"), config.sourceOf("k"));
                })),
                parameterized("every pair of sources: the one added later wins", pairs(), pair -> TempDir.with(dir -> {
                    var config = Config.builder()
                            .add(pair.first().source(dir))
                            .add(pair.second().source(dir))
                            .build();
                    assertEquals(Optional.of(pair.second().value()), config.get("k"));
                })),
                test("keys only in an earlier source are kept", () -> {
                    var config = Config.builder()
                            .add(Source.map("defaults", Map.of("a", "1", "b", "2")))
                            .add(Source.map("override", Map.of("b", "3")))
                            .build();
                    assertEquals(Optional.of("1"), config.get("a"));
                    assertEquals(Optional.of("map defaults"), config.sourceOf("a"));
                    assertEquals(Optional.of("3"), config.get("b"));
                    assertEquals(Optional.of("map override"), config.sourceOf("b"));
                    assertEquals(Set.of("a", "b"), config.keys());
                }),
                test("environment prefix: removed, rest lower-cased, _ becomes .; others not read", () -> {
                    var env = Map.of(
                            "APP_DB_POOL_SIZE", "20",
                            "APP_NAME", "shop",
                            "DB_POOL_SIZE", "99",
                            "PATH", "/usr/bin",
                            "app_lower", "x",
                            "APP_", "only the prefix");
                    var config = Config.builder().add(Source.environment("APP_", env)).build();
                    assertEquals(Optional.of("20"), config.get("db.pool.size"));
                    assertEquals(Optional.of("shop"), config.get("name"));
                    assertEquals(Set.of("db.pool.size", "name"), config.keys());
                    assertEquals(Optional.of("environment (prefix APP_)"), config.sourceOf("db.pool.size"));
                }),
                test("environment prefix: two variables giving the same key is an error", () -> {
                    var env = Map.of("APP_DB_URL", "a", "APP_db_url", "b");
                    var e = assertThrows(ConfigException.class,
                            () -> Config.builder().add(Source.environment("APP_", env)).build());
                    assertTrue(e.getMessage().contains("APP_DB_URL and APP_db_url both give key 'db.url'"),
                            e.getMessage());
                }),
                test("environment prefix must not be blank", () -> {
                    assertThrows(IllegalArgumentException.class, () -> Source.environment("", Map.of()));
                    assertThrows(IllegalArgumentException.class, () -> Source.environment(" "));
                }),
                test("environment table: only the listed variables, under the given keys", () -> {
                    var env = Map.of("DATABASE_URL", "jdbc:x", "HOME", "/root", "APP_DB_URL", "ignored");
                    var config = Config.builder()
                            .add(Source.environmentTable(Map.of("DATABASE_URL", "db.url", "UNSET", "un.set"), env))
                            .build();
                    assertEquals(Optional.of("jdbc:x"), config.get("db.url"));
                    assertEquals(Set.of("db.url"), config.keys());
                    assertEquals(Optional.of("environment (table)"), config.sourceOf("db.url"));
                    assertThrows(IllegalArgumentException.class,
                            () -> Source.environmentTable(Map.of("A", "x", "B", "x"), env));
                }),
                test("the environment map is read at build time, not at creation", () -> {
                    var env = new java.util.HashMap<String, String>();
                    var builder = Config.builder().add(Source.environment("APP_", env));
                    env.put("APP_X", "1");
                    assertEquals(Optional.of("1"), builder.build().get("x"));
                }),
                test("a missing optional file is ignored", () -> TempDir.with(dir -> {
                    var config = Config.builder()
                            .add(Source.map("defaults", Map.of("k", "default")))
                            .add(Source.optionalFile(dir.resolve("absent.properties")))
                            .build();
                    assertEquals(Optional.of("default"), config.get("k"));
                })),
                test("a missing required file is an error with the path", () -> TempDir.with(dir -> {
                    var path = dir.resolve("required.properties");
                    var e = assertThrows(ConfigException.class,
                            () -> Config.builder().add(Source.file(path)).build());
                    assertTrue(e.getMessage().contains(path.toAbsolutePath().toString()), e.getMessage());
                    assertEquals(1, e.problems().size());
                    assertTrue(e.problems().getFirst() instanceof ConfigProblem.SourceError);
                })),
                test("every missing file is reported, not only the first", () -> TempDir.with(dir -> {
                    var e = assertThrows(ConfigException.class, () -> Config.builder()
                            .add(Source.file(dir.resolve("one.properties")))
                            .add(Source.file(dir.resolve("two.properties")))
                            .build());
                    assertEquals(2, e.problems().size());
                    assertTrue(e.getMessage().contains("one.properties"), e.getMessage());
                    assertTrue(e.getMessage().contains("two.properties"), e.getMessage());
                })),
                test("an optional file that exists but cannot be read is still an error", () -> TempDir.with(dir -> {
                    var directory = Files.createDirectory(dir.resolve("app.properties"));
                    var e = assertThrows(ConfigException.class,
                            () -> Config.builder().add(Source.optionalFile(directory)).build());
                    assertTrue(e.getMessage().contains(directory.toString()), e.getMessage());
                })),
                test("files are read as UTF-8 with Properties syntax", () -> TempDir.with(dir -> {
                    var file = TempDir.write(dir, "app.properties",
                            "# comment\ngreeting = h\u00e4lsningar\nescaped=\\u00e5\nlong=a\\\n  b\n");
                    var config = Config.builder().add(Source.file(file)).build();
                    assertEquals(Optional.of("h\u00e4lsningar"), config.get("greeting"));
                    assertEquals(Optional.of("\u00e5"), config.get("escaped"));
                    assertEquals(Optional.of("ab"), config.get("long"));
                    assertEquals(Optional.of("file " + file.toAbsolutePath().normalize()), config.sourceOf("greeting"));
                })),
                test("a byte order mark at the start of a file is skipped, not part of the first key", () -> TempDir.with(dir -> {
                    var file = Files.write(dir.resolve("bom.properties"),
                            "\uFEFFfirst=1\nsecond=2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    var config = Config.builder().add(Source.file(file)).build();
                    assertEquals(Optional.of("1"), config.get("first"));
                    assertEquals(Optional.of("2"), config.get("second"));
                    assertEquals(java.util.Set.of("first", "second"), java.util.Set.copyOf(config.keys()));
                    var optional = Config.builder().add(Source.optionalFile(file)).build();
                    assertEquals(Optional.of("1"), optional.get("first"));
                })),
                test("only a leading byte order mark is skipped; an empty file and a file without one are unchanged", () -> TempDir.with(dir -> {
                    var empty = Files.write(dir.resolve("empty.properties"), new byte[0]);
                    assertEquals(0, Config.builder().add(Source.file(empty)).build().keys().size());
                    var plain = Files.write(dir.resolve("plain.properties"), "k=v\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    assertEquals(Optional.of("v"), Config.builder().add(Source.file(plain)).build().get("k"));
                    var inner = Files.write(dir.resolve("inner.properties"),
                            "a=1\n\uFEFFb=2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    var config = Config.builder().add(Source.file(inner)).build();
                    assertEquals(Optional.of("2"), config.get("\uFEFFb"));
                    assertEquals(Optional.empty(), config.get("b"));
                })),
                test("a file that is not UTF-8 is an error", () -> TempDir.with(dir -> {
                    var file = Files.write(dir.resolve("latin1.properties"), new byte[] {'k', '=', (byte) 0xE4});
                    var e = assertThrows(ConfigException.class, () -> Config.builder().add(Source.file(file)).build());
                    assertTrue(e.getMessage().contains("not valid UTF-8"), e.getMessage());
                })),
                test("arguments: --key=value, positional arguments ignored, -- ends options", () -> {
                    var config = Config.builder()
                            .add(Source.arguments("serve", "--port=8080", "--url=a=b", "--empty=", "--port=9090",
                                    "--", "--after=x"))
                            .build();
                    assertEquals(Optional.of("9090"), config.get("port"));
                    assertEquals(Optional.of("a=b"), config.get("url"));
                    assertEquals(Optional.of(""), config.get("empty"));
                    assertFalse(config.get("after").isPresent());
                    assertEquals(Set.of("port", "url", "empty"), config.keys());
                }),
                test("arguments: --key without a value and an empty key are errors", () -> {
                    var e = assertThrows(ConfigException.class,
                            () -> Config.builder().add(Source.arguments("--verbose", "--=x")).build());
                    assertEquals(2, e.problems().size());
                    assertTrue(e.getMessage().contains("'--verbose' has no '=value'"), e.getMessage());
                }),
                test("system properties are read at build time", () -> {
                    var name = "spola.config.test.property";
                    System.setProperty(name, "set");
                    try {
                        var config = Config.builder().add(Source.systemProperties()).build();
                        assertEquals(Optional.of("set"), config.get(name));
                        assertEquals(Optional.of("system properties"), config.sourceOf(name));
                    } finally {
                        System.clearProperty(name);
                    }
                }),
                test("map sources are copied and reject blank keys", () -> {
                    var values = new java.util.HashMap<>(Map.of("a", "1"));
                    var source = Source.map("m", values);
                    values.put("a", "2");
                    assertEquals(Optional.of("1"), Config.builder().add(source).build().get("a"));
                    assertThrows(IllegalArgumentException.class, () -> Source.map("m", Map.of(" ", "x")));
                }));
    }
}
