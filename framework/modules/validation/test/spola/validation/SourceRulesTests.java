package spola.validation;

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

    static final Path SOURCES = Path.of("modules/validation/src");

    static Suite tests() {
        return suite("source rules",
                test("module-info: only requires static java.compiler; exports spola.validation", () -> {
                    var text = Files.readString(SOURCES.resolve("module-info.java"));
                    var requires = Pattern.compile("^\\s*requires\\s+([^;]+);", Pattern.MULTILINE).matcher(text)
                            .results().map(m -> m.group(1).strip()).toList();
                    assertEquals(List.of("static java.compiler"), requires);
                    var exports = Pattern.compile("^\\s*exports\\s+([^;]+);", Pattern.MULTILINE).matcher(text)
                            .results().map(m -> m.group(1).strip()).toList();
                    assertEquals(List.of("spola.validation"), exports);
                    assertEquals(List.of(), Validator.class.getModule().getDescriptor().requires().stream()
                            .map(r -> r.name()).filter(n -> n.startsWith("spola.")).toList());
                }),
                test("no reflection, method handles or javax.tools in the main code", () -> {
                    List<Path> sources;
                    try (var files = Files.walk(SOURCES)) {
                        sources = files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
                    }
                    assertTrue(sources.size() >= 9, "found " + sources.size() + " files");
                    for (var file : sources) {
                        var text = Files.readString(file).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
                        if (file.endsWith("module-info.java")) {
                            continue;
                        }
                        for (var forbidden : List.of("java.lang.reflect", "java.lang.invoke", "javax.tools",
                                "getDeclared", "setAccessible", "Class.forName", "getMethod", "getField",
                                "getRecordComponents")) {
                            assertFalse(text.contains(forbidden), file + " mentions " + forbidden);
                        }
                    }
                }),
                test("Build.java lists the module with its test entry", () -> {
                    var build = Files.readString(Path.of("Build.java"));
                    assertTrue(build.contains("new ModuleDir(\"spola.validation\", Path.of(\"modules/validation\"),\n"
                            + "                new TestEntry(\"spola.validation.ValidationTests\", \"suite\"))"));
                }));
    }
}
