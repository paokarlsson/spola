package spola.json.codec;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import spola.test.Suite;

/**
 * The rules on the sources (run from {@code framework/}, as the build does): no reflection in
 * the codec package, and no dependency from the tree model on it.
 */
final class SourceRulesTests {

    private SourceRulesTests() {
    }

    static final Path MAIN = Path.of("modules/json/src");
    static final Path CODEC = MAIN.resolve("spola/json/codec");
    static final Path TREE = MAIN.resolve("spola/json");

    /** Text that must not appear in the main code of {@code spola.json.codec}. */
    static final List<String> FORBIDDEN = List.of("java.lang.reflect", "java.lang.invoke", "isInstance", ".cast(",
            "getRecordComponents", "getEnumConstants", "getPermittedSubclasses");

    static List<Path> javaFiles(Path dir, boolean recursive) throws IOException {
        try (Stream<Path> files = recursive ? Files.walk(dir) : Files.list(dir)) {
            return files.filter(f -> f.toString().endsWith(".java") && Files.isRegularFile(f)).sorted().toList();
        }
    }

    static Suite tests() {
        return suite("source rules",
                test("the main code of spola.json.codec uses no reflection, invoke, class tokens or casts by class", () -> {
                    var files = javaFiles(CODEC, true);
                    assertTrue(files.size() >= 10, "found " + files);
                    var found = new ArrayList<String>();
                    for (var file : files) {
                        var text = Files.readString(file);
                        for (var word : FORBIDDEN) {
                            if (text.contains(word)) {
                                found.add(file + ": " + word);
                            }
                        }
                    }
                    assertEquals(List.of(), found);
                }),
                test("no class of the package spola.json refers to spola.json.codec", () -> {
                    var files = javaFiles(TREE, false);
                    assertTrue(files.size() >= 10, "found " + files);
                    var found = new ArrayList<String>();
                    for (var file : files) {
                        if (Files.readString(file).contains("spola.json.codec")) {
                            found.add(file.toString());
                        }
                    }
                    assertEquals(List.of(), found);
                }),
                test("module-info exports spola.json.codec and requires nothing", () -> {
                    var moduleInfo = Files.readString(MAIN.resolve("module-info.java"));
                    assertTrue(moduleInfo.contains("exports spola.json.codec;"), moduleInfo);
                    assertTrue(!moduleInfo.contains("requires"), moduleInfo);
                    assertTrue(JsonCodec.class.getModule().getDescriptor().exports().stream()
                            .anyMatch(e -> e.source().equals("spola.json.codec") && !e.isQualified()));
                }));
    }
}
