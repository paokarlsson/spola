package spola.security.web;

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

    static final Path SOURCES = Path.of("modules/security-web/src");

    static Suite tests() {
        return suite("source rules",
                test("module-info: exactly transitive spola.web, transitive spola.security, static java.net.http", () -> {
                    var text = Files.readString(SOURCES.resolve("module-info.java"));
                    var requires = Pattern.compile("^\\s*requires\\s+([^;]+);", Pattern.MULTILINE).matcher(text)
                            .results().map(m -> m.group(1).strip().replaceAll("\\s+", " ")).toList();
                    assertEquals(List.of("transitive spola.web", "transitive spola.security", "static java.net.http"), requires);
                    var exports = Pattern.compile("^\\s*exports\\s+([^;]+);", Pattern.MULTILINE).matcher(text)
                            .results().map(m -> m.group(1).strip()).toList();
                    assertEquals(List.of("spola.security.web"), exports);
                    assertEquals(List.of("java.base", "java.net.http", "spola.security", "spola.web"),
                            WebSecurity.class.getModule().getDescriptor().requires().stream().map(r -> r.name()).sorted()
                                    .toList());
                }),
                test("the main code: no reflection, no method handles, no logging, no ThreadLocal or ScopedValue", () -> {
                    List<Path> sources;
                    try (var files = Files.walk(SOURCES)) {
                        sources = files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
                    }
                    assertEquals(11, sources.size(), "files: " + sources);
                    for (var file : sources) {
                        var text = Files.readString(file).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
                        for (var forbidden : List.of("java.lang.reflect", "java.lang.invoke", "getDeclared", "setAccessible",
                                "Class.forName", "getMethod", "getField", "System.getLogger", "System.Logger", "Logger",
                                "System.out", "System.err", "printStackTrace", "ThreadLocal", "ScopedValue", "java.net.http")) {
                            if (forbidden.equals("java.net.http") && file.endsWith("module-info.java")) {
                                continue; // requires static java.net.http, for the tests
                            }
                            assertFalse(text.contains(forbidden), file + " mentions " + forbidden);
                        }
                    }
                    var basic = Files.readString(SOURCES.resolve("spola/security/web/HttpBasic.java"));
                    assertTrue(basic.contains("Base64.getDecoder().decode(token)"));
                    assertTrue(basic.contains(".onMalformedInput(CodingErrorAction.REPORT)"));
                }),
                test("Build.java lists the module with its test entry", () -> {
                    var build = Files.readString(Path.of("Build.java"));
                    assertTrue(build.contains("new ModuleDir(\"spola.security.web\", Path.of(\"modules/security-web\"),\n"
                            + "                new TestEntry(\"spola.security.web.SecurityWebTests\", \"suite\"))"));
                }));
    }
}
