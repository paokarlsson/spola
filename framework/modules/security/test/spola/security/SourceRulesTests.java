package spola.security;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import spola.test.Suite;

/** Criterion 2: the module's dependencies and the rules for its main sources (tests run from framework/). */
final class SourceRulesTests {

    private SourceRulesTests() {
    }

    static final Path SOURCES = Path.of("modules/security/src");

    static Suite tests() {
        return suite("source rules",
                test("module-info: no requires at all; exports spola.security", () -> {
                    var text = Files.readString(SOURCES.resolve("module-info.java"));
                    var requires = Pattern.compile("^\\s*requires\\s+([^;]+);", Pattern.MULTILINE).matcher(text)
                            .results().map(m -> m.group(1).strip()).toList();
                    assertEquals(List.of(), requires);
                    var exports = Pattern.compile("^\\s*exports\\s+([^;]+);", Pattern.MULTILINE).matcher(text)
                            .results().map(m -> m.group(1).strip()).toList();
                    assertEquals(List.of("spola.security"), exports);
                    // The compiled descriptor: java.base only (mandated), no Spola module.
                    assertEquals(List.of("java.base"), Pbkdf2.class.getModule().getDescriptor().requires().stream()
                            .map(r -> r.name()).sorted().toList());
                }),
                test("no reflection or method handles in the main code; only Mac, SecureRandom and MessageDigest for crypto", () -> {
                    List<Path> sources;
                    try (var files = Files.walk(SOURCES)) {
                        sources = files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
                    }
                    assertEquals(13, sources.size(), "files: " + sources);
                    for (var file : sources) {
                        var text = Files.readString(file).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
                        for (var forbidden : List.of("java.lang.reflect", "java.lang.invoke", "getDeclared", "setAccessible",
                                "Class.forName", "getMethod", "getField", "new Random", "ThreadLocalRandom",
                                "SecretKeyFactory", "Math.random", "Arrays.equals")) {
                            assertFalse(text.contains(forbidden), file + " mentions " + forbidden);
                        }
                    }
                    var hasher = Files.readString(SOURCES.resolve("spola/security/Pbkdf2PasswordHasher.java"));
                    assertTrue(hasher.contains("MessageDigest.isEqual(actual, stored.hash())"));
                    assertTrue(hasher.contains("new SecureRandom()"));
                    var authenticator = Files.readString(SOURCES.resolve("spola/security/Authenticator.java"));
                    assertTrue(authenticator.contains("System.getLogger(\"spola.security\")"));
                }),
                test("Build.java lists the module with its test entry", () -> {
                    var build = Files.readString(Path.of("Build.java"));
                    assertTrue(build.contains("new ModuleDir(\"spola.security\", Path.of(\"modules/security\"),\n"
                            + "                new TestEntry(\"spola.security.SecurityTests\", \"suite\"))"));
                }));
    }
}
