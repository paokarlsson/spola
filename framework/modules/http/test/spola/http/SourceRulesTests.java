package spola.http;

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

/** The module's own rules, checked on its main sources (tests run from framework/). */
final class SourceRulesTests {

    private SourceRulesTests() {
    }

    static final Path SOURCES = Path.of("modules/http/src");

    static List<Path> mainSources() throws Exception {
        try (var files = Files.walk(SOURCES)) {
            return files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
    }

    static Suite tests() {
        return suite("source rules",
                test("no reflection, no method handles, no com.sun.net.httpserver in the main code", () -> {
                    var sources = mainSources();
                    assertTrue(sources.size() > 10, "found " + sources.size() + " files");
                    for (var file : sources) {
                        // comments may name what the code does not use
                        var text = Files.readString(file).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
                        for (var forbidden : List.of("java.lang.reflect", "java.lang.invoke", "com.sun.net.httpserver",
                                "jdk.httpserver", "getDeclared", "setAccessible", "Class.forName")) {
                            assertFalse(text.contains(forbidden), file + " mentions " + forbidden);
                        }
                    }
                }),
                test("module-info requires no other Spola module and exports spola.http only", () -> {
                    var text = Files.readString(SOURCES.resolve("module-info.java"));
                    var requires = Pattern.compile("^\\s*requires\\s+([^;]+);", Pattern.MULTILINE).matcher(text)
                            .results().map(m -> m.group(1).strip()).toList();
                    assertEquals(List.of("static java.net.http"), requires);
                    var exports = Pattern.compile("^\\s*exports\\s+([^;]+);", Pattern.MULTILINE).matcher(text)
                            .results().map(m -> m.group(1).strip()).toList();
                    assertEquals(List.of("spola.http"), exports);
                    assertEquals(java.util.Set.of(), HttpServer.class.getModule().getDescriptor().requires().stream()
                            .map(r -> r.name()).filter(n -> n.startsWith("spola.")).collect(java.util.stream.Collectors.toSet()));
                }),
                test("the main code sets no system or security properties and uses no internal JDK packages", () -> {
                    for (var file : mainSources()) {
                        var text = Files.readString(file).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
                        for (var forbidden : List.of("System.setProperty", "System.setProperties", "Security.setProperty",
                                "System.clearProperty", "import sun.", "import jdk.internal", "import com.sun.", "sun.security.")) {
                            assertFalse(text.contains(forbidden), file + " uses " + forbidden);
                        }
                    }
                }),
                test("no main class uses java.net.http (it is for the tests)", () -> {
                    for (var file : mainSources()) {
                        if (!file.endsWith("module-info.java")) {
                            assertFalse(Files.readString(file).contains("java.net.http"), file.toString());
                        }
                    }
                }));
    }
}
