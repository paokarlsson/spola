package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import spola.test.Suite;

/** Criterion 2: the module's dependencies and the rules for its main sources (tests run from framework/). */
final class SourceRulesTests {

    private SourceRulesTests() {
    }

    static final Path SOURCES = Path.of("modules/web/src");

    static List<Path> mainSources() throws Exception {
        try (var files = Files.walk(SOURCES)) {
            return files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
    }

    static Suite tests() {
        return suite("source rules",
                test("module-info: spola.http and spola.json (transitive), java.net.http static, nothing else", () -> {
                    var text = Files.readString(SOURCES.resolve("module-info.java"));
                    var requires = Pattern.compile("^\\s*requires\\s+([^;]+);", Pattern.MULTILINE).matcher(text)
                            .results().map(m -> m.group(1).strip()).toList();
                    assertEquals(List.of("transitive spola.http", "transitive spola.json", "static java.net.http"), requires);
                    var exports = Pattern.compile("^\\s*exports\\s+([^;]+);", Pattern.MULTILINE).matcher(text)
                            .results().map(m -> m.group(1).strip()).toList();
                    assertEquals(List.of("spola.web"), exports);
                    assertEquals(java.util.Set.of("spola.http", "spola.json"), Router.class.getModule().getDescriptor()
                            .requires().stream().map(r -> r.name()).filter(n -> n.startsWith("spola."))
                            .collect(Collectors.toSet()));
                }),
                test("no reflection or method handles in the main code, no java.net.http outside module-info", () -> {
                    var sources = mainSources();
                    assertTrue(sources.size() > 10, "found " + sources.size() + " files");
                    for (var file : sources) {
                        var text = Files.readString(file).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
                        for (var forbidden : List.of("java.lang.reflect", "java.lang.invoke", "getDeclared",
                                "setAccessible", "Class.forName", "getMethod", "getField")) {
                            assertFalse(text.contains(forbidden), file + " mentions " + forbidden);
                        }
                        if (!file.endsWith("module-info.java")) {
                            assertFalse(text.contains("java.net.http"), file + " uses java.net.http");
                        }
                    }
                }));
    }
}
