package spola.demo;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import spola.test.Suite;

/**
 * What the module's structure promises, checked on the sources (tests run with
 * {@code framework/} as the working directory): only the entry and the wiring know the
 * container and the configuration, and nothing depends on {@code spola.demo}.
 */
final class BoundaryTests {

    private BoundaryTests() {
    }

    static final Path MODULE = Path.of("modules/demo");

    /** The only files allowed to name the container, the binder or the configuration. */
    static final Set<String> WIRING = Set.of("InventoryApp.java", "InventoryWiring.java");

    /**
     * The names of the container, the binder and the configuration (also as part of a longer name,
     * like the configuration's exception), and imports from the packages of {@code spola.di} and
     * {@code spola.config}. Written with character classes so that this file does not match itself.
     */
    static final Pattern FORBIDDEN = Pattern.compile("Contain[e]r|Bind[e]r|Conf[i]g|import\\s+(?:static\\s+)?spola\\.(?:d[i]|conf[i]g)\\.");

    static Suite tests() {
        return suite("module boundaries",
                test("only the entry and its wiring name the container, the binder or the configuration", () -> {
                    var offenders = new ArrayList<String>();
                    var checked = 0;
                    try (Stream<Path> files = Stream.concat(Files.walk(MODULE.resolve("src")), Files.walk(MODULE.resolve("test")))) {
                        for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                            var name = file.getFileName().toString();
                            if (WIRING.contains(name) || name.equals("module-info.java")) {
                                continue;
                            }
                            checked++;
                            var lines = Files.readAllLines(file);
                            for (var i = 0; i < lines.size(); i++) {
                                if (FORBIDDEN.matcher(lines.get(i)).find()) {
                                    offenders.add(file + ":" + (i + 1) + ": " + lines.get(i).strip());
                                }
                            }
                        }
                    }
                    assertTrue(checked >= 20, "checked " + checked + " files");
                    assertEquals(List.of(), offenders);
                }),
                test("spola.demo requires di, config and util, and no other module requires spola.demo", () -> {
                    var own = Files.readString(MODULE.resolve("src/module-info.java"));
                    for (var required : List.of("spola.di", "spola.config", "spola.util")) {
                        assertTrue(own.contains("requires " + required + ";"), required);
                    }
                    var requiresDemo = Pattern.compile("requires\\s+(?:transitive\\s+|static\\s+)*spola\\.demo\\s*;");
                    try (var modules = Files.list(Path.of("modules"))) {
                        for (var module : modules.toList()) {
                            var info = module.resolve("src/module-info.java");
                            if (Files.isRegularFile(info) && !module.equals(MODULE)) {
                                assertTrue(!requiresDemo.matcher(Files.readString(info)).find(), info.toString());
                            }
                        }
                    }
                }));
    }
}
