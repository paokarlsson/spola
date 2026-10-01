package spola.config;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import spola.test.Suite;

/** Active profiles and profile sources. */
final class ProfileTests {

    private ProfileTests() {
    }

    static void writeProfileFiles(Path dir) throws Exception {
        TempDir.write(dir, "app.properties", "only.base=base\nbase.and.dev=base\nall=base\n");
        TempDir.write(dir, "app-dev.properties", "base.and.dev=dev\nall=dev\ndev.and.local=dev\n");
        TempDir.write(dir, "app-local.properties", "all=local\ndev.and.local=local\n");
    }

    static Suite tests() {
        return suite("profiles",
                test("two active profiles: later profile wins over earlier, both over the base file",
                        () -> TempDir.with(dir -> {
                            writeProfileFiles(dir);
                            var asked = new ArrayList<String>();
                            var config = Config.builder()
                                    .activeProfiles("dev", "local")
                                    .add(Source.file(dir.resolve("app.properties")))
                                    .addForEachProfile(profile -> {
                                        asked.add(profile);
                                        return Source.optionalFile(dir.resolve("app-" + profile + ".properties"));
                                    })
                                    .build();
                            assertEquals(List.of("dev", "local"), asked);
                            assertEquals(List.of("dev", "local"), config.profiles());
                            assertEquals(Optional.of("base"), config.get("only.base"));
                            assertEquals(Optional.of("dev"), config.get("base.and.dev"));
                            assertEquals(Optional.of("local"), config.get("dev.and.local"));
                            assertEquals(Optional.of("local"), config.get("all"));
                            assertTrue(config.sourceOf("all").orElseThrow().endsWith("app-local.properties"));
                            assertTrue(config.sourceOf("base.and.dev").orElseThrow().endsWith("app-dev.properties"));
                        })),
                test("profile order decides: local then dev makes dev win", () -> TempDir.with(dir -> {
                    writeProfileFiles(dir);
                    var config = Config.builder()
                            .activeProfiles("local", "dev")
                            .add(Source.file(dir.resolve("app.properties")))
                            .addForEachProfile(p -> Source.optionalFile(dir.resolve("app-" + p + ".properties")))
                            .build();
                    assertEquals(Optional.of("dev"), config.get("all"));
                })),
                test("profile sources sit where they are added: later sources still win", () -> TempDir.with(dir -> {
                    writeProfileFiles(dir);
                    var config = Config.builder()
                            .activeProfiles("dev")
                            .add(Source.file(dir.resolve("app.properties")))
                            .addForEachProfile(p -> Source.optionalFile(dir.resolve("app-" + p + ".properties")))
                            .add(Source.arguments("--all=argument"))
                            .build();
                    assertEquals(Optional.of("argument"), config.get("all"));
                    assertEquals(Optional.of("dev"), config.get("base.and.dev"));
                })),
                test("active profiles from a key given as an argument", () -> TempDir.with(dir -> {
                    writeProfileFiles(dir);
                    var config = Config.builder()
                            .activeProfilesFrom("app.profiles")
                            .add(Source.map("defaults", Map.of("app.profiles", "")))
                            .add(Source.file(dir.resolve("app.properties")))
                            .addForEachProfile(p -> Source.optionalFile(dir.resolve("app-" + p + ".properties")))
                            .add(Source.arguments("--app.profiles= dev , local ,"))
                            .build();
                    assertEquals(List.of("dev", "local"), config.profiles());
                    assertEquals(Optional.of("local"), config.get("all"));
                })),
                test("no profile key, no profiles: profile function is not called", () -> {
                    var config = Config.builder()
                            .activeProfilesFrom("app.profiles")
                            .addForEachProfile(p -> {
                                throw new AssertionError("called for " + p);
                            })
                            .build();
                    assertEquals(List.of(), config.profiles());
                }),
                test("a profile source that sets the profile key is an error", () -> {
                    var e = assertThrows(ConfigException.class, () -> Config.builder()
                            .activeProfilesFrom("app.profiles")
                            .add(Source.arguments("--app.profiles=dev"))
                            .addForEachProfile(p -> Source.map(p, Map.of("app.profiles", "prod")))
                            .build());
                    assertTrue(e.getMessage().contains("profile sources cannot activate profiles"), e.getMessage());
                }),
                test("invalid or repeated profile names are refused", () -> {
                    assertThrows(IllegalArgumentException.class, () -> Config.builder().activeProfiles("../etc"));
                    assertThrows(IllegalArgumentException.class, () -> Config.builder().activeProfiles("dev", "dev"));
                    assertThrows(IllegalStateException.class,
                            () -> Config.builder().activeProfiles("dev").activeProfilesFrom("p"));
                    var e = assertThrows(ConfigException.class, () -> Config.builder()
                            .activeProfilesFrom("p")
                            .add(Source.arguments("--p=dev,a/b,dev"))
                            .build());
                    assertEquals(2, e.problems().size());
                    assertTrue(e.getMessage().contains("invalid profile name"), e.getMessage());
                    assertTrue(e.getMessage().contains("profile listed twice"), e.getMessage());
                }),
                test("the dump lists the active profiles", () -> {
                    var config = Config.builder().activeProfiles("dev", "local").build();
                    assertTrue(config.describe().startsWith("active profiles: dev, local"), config.describe());
                    assertTrue(Config.builder().build().describe().startsWith("active profiles: (none)"));
                }));
    }
}
