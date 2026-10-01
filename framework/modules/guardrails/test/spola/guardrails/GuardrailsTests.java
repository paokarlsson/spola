package spola.guardrails;

import static spola.test.Assertions.assertAnyOrder;
import static spola.test.Assertions.assertEmpty;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertInOrder;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import spola.test.Suite;
import spola.test.Tests;

/**
 * The tests of {@code spola.guardrails}, run by {@code spola.test} on every build (the entry
 * named in {@code Build.java}). Paths are relative to {@code framework/}, the working
 * directory of the build.
 *
 * <p>The self-test runs each check against its {@link Fixtures fixtures} and fails unless every
 * fixture yields exactly its expected violations (one test per fixture). It also verifies that
 * the annotation allowlist in code matches the table in {@code REGLER.md}, that every file
 * below the fixtures directory belongs to a fixture that uses it, and that the main check
 * leaves out nothing but the listed fixture directories. Further tests cover the check of
 * test sources and {@link Project}.
 */
public final class GuardrailsTests {

    /** The fixtures, relative to {@code framework/}. */
    private static final Path FIXTURES = Path.of("modules/guardrails/fixtures");

    /** The ground rules, relative to {@code framework/}. */
    private static final Path RULES = Path.of("REGLER.md");

    /**
     * A row of the allowlist table in REGLER.md, e.g. {@code | `java.lang.Override` | SOURCE |}.
     * Coupled to the table's format on purpose: if the format changes, the test fails
     * and says so, rather than silently reading fewer rows.
     */
    private static final Pattern ALLOWLIST_ROW =
            Pattern.compile("^\\|\\s*`([\\w.]+)`\\s*\\|\\s*(SOURCE|CLASS|RUNTIME)\\s*\\|", Pattern.MULTILINE);

    private GuardrailsTests() {
    }

    /**
     * Every test of the module.
     *
     * @return the root suite
     */
    public static Suite suite() {
        return Tests.suite("spola.guardrails",
                Tests.suite("self-test",
                        test("annotation allowlist matches the table in REGLER.md", GuardrailsTests::allowlistMatchesRules),
                        test("every file below fixtures/ is used by its listed fixture",
                                () -> assertEmpty(layoutProblems(FIXTURES))),
                        test("layout check finds stray and binary files", GuardrailsTests::layoutCheckFindsStrayFiles),
                        test("main check leaves out only the listed fixture directories",
                                GuardrailsTests::onlyListedFixturesAreExcluded),
                        parameterized("fixture yields exactly its expected violations", Fixtures.ALL, Fixture::name,
                                GuardrailsTests::fixtureYieldsExactlyItsViolations)),
                Tests.suite("test sources",
                        test("are analysed as part of their module and may read the test runner",
                                GuardrailsTests::testSourcesAreChecked),
                        test("project rejects tests of an unknown module", () -> assertThrows(
                                IllegalArgumentException.class,
                                "tests given for x, which is not a project module",
                                () -> new Project(Path.of("."), Path.of("out"), List.of(),
                                        Map.of("m", Path.of("m/src")), Map.of("x", Path.of("x/test")), Set.of()))),
                        test("project rejects test reads of an unknown module", () -> assertThrows(
                                IllegalArgumentException.class,
                                "test sources read r, which is not a project module",
                                () -> new Project(Path.of("."), Path.of("out"), List.of(),
                                        Map.of("m", Path.of("m/src")), Map.of(), Set.of("r"))))));
    }

    private static void fixtureYieldsExactlyItsViolations(Fixture fixture) throws Exception {
        var dir = FIXTURES.resolve(fixture.name());
        assertTrue(Files.isDirectory(dir), "fixture directory " + dir + " exists");
        assertFalse(fixture.expected().isEmpty(), "fixture declares expected violations (a fixture must break a rule)");
        withScratch("spola-guardrails-fixture-", scratch ->
                assertAnyOrder(formatted(fixture.expected()), formatted(actualViolations(dir, scratch, fixture))));
    }

    /** Violations as report lines, in report order, so that a failure lists them readably. */
    private static List<String> formatted(Collection<Violation> violations) {
        return violations.stream().sorted(Violation.ORDER).map(Violation::format).toList();
    }

    /**
     * What the checks report for one fixture, run on exactly {@link Fixture#inputs(Path)}.
     * Every {@code .java} file of a fixture goes through the source check (so its exact source
     * violations are expected too), and every
     * compiled fixture goes through the bytecode check: nothing in a fixture directory escapes
     * both the main check and the self-test.
     */
    private static List<Violation> actualViolations(Path dir, Path scratch, Fixture fixture) throws IOException {
        var result = new ArrayList<Violation>();
        var inputs = fixture.inputs(dir);
        switch (fixture.kind()) {
            case SOURCE -> result.addAll(SourceCheck.analyze(dir, inputs, List.of(), Set.of()).violations());
            case BYTECODE -> {
                result.addAll(SourceCheck.analyze(dir, inputs, List.of(), Set.of()).violations());
                var classes = Files.createDirectories(scratch.resolve("classes"));
                compile(inputs, List.of("-d", classes.toString()));
                result.addAll(BytecodeCheck.run(classes, classFiles(classes)));
            }
            case DEPENDENCIES -> {
                var src = dir.resolve("src");
                var analysis = Files.createDirectories(scratch.resolve("analysis"));
                result.addAll(SourceCheck.analyze(dir, inputs,
                        List.of("--module-source-path", src.toString(), "-d", analysis.toString()), Set.of())
                        .violations());
                var classes = Files.createDirectories(scratch.resolve("classes"));
                compile(inputs, List.of("--module-source-path", src.toString(), "-d", classes.toString()));
                result.addAll(DependencyCheck.run(classes, FileTree.files(classes, List.of(),
                        f -> f.getFileName().toString().equals("module-info.class")), Set.of()));
                result.addAll(BytecodeCheck.run(classes, classFiles(classes)));
            }
            case FILES -> {
                for (var tree : inputs) {
                    TreeSpec.materialize(Files.readAllLines(tree, StandardCharsets.UTF_8), scratch);
                }
                // A tree.txt is test data that is never checked for Java (fixture directories
                // are left out of the main check), so it must not be Java in any reading.
                LauncherCheck.javaReadings(inputs).forEach((tree, reading) -> result.add(new Violation(
                        FileTree.relative(dir, tree), 0, Rule.UNCHECKABLE_SOURCE, reading.subject(), "")));
                result.addAll(FileCheck.run(scratch, FileTree.filesAndLinks(scratch), scratch.resolve("out")));
            }
            case LAUNCHER -> {
                // The same selection and analysis as the main check, with nothing left out.
                result.addAll(Guardrails.checkOtherFiles(dir, List.of(),
                        new PrintStream(OutputStream.nullOutputStream())));
            }
        }
        return result;
    }

    private static List<Path> classFiles(Path dir) {
        return FileTree.files(dir, List.of(), f -> FileTree.hasExtension(f, ".class"));
    }

    private static void compile(List<Path> files, List<String> options) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var allOptions = new ArrayList<>(List.of("-proc:none", "-nowarn", "-Xlint:none"));
        allOptions.addAll(options);
        try (var fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task = compiler.getTask(new StringWriter(), fileManager, diagnostics, allOptions, null,
                    fileManager.getJavaFileObjectsFromPaths(files));
            if (!task.call()) {
                throw new IllegalStateException("fixture does not compile: " + diagnostics.getDiagnostics());
            }
        }
    }

    private static void allowlistMatchesRules() throws IOException {
        var text = Files.readString(RULES, StandardCharsets.UTF_8);
        var documented = ALLOWLIST_ROW.matcher(text).results().map(m -> m.group(1))
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(new TreeSet<>(Allowlist.ANNOTATIONS), documented,
                "annotation allowlist in Allowlist.ANNOTATIONS (expected) vs the table in " + RULES
                + " (actual); rows are read with the pattern | `name` | SOURCE|CLASS|RUNTIME |, so if the"
                + " table format changed, update ALLOWLIST_ROW in GuardrailsTests.java");
    }

    /**
     * Problems with what lies below {@code fixturesDir}: a directory that is not a listed
     * fixture, a file outside every fixture directory, or a file inside a fixture directory
     * that its fixture does not use (see {@link Fixture#inputs(Path)}). The main check leaves
     * the fixture directories out, so the self-test must account for every file in them.
     */
    static List<String> layoutProblems(Path fixturesDir) {
        var problems = new ArrayList<String>();
        List<Path> children;
        try (var list = Files.list(fixturesDir)) {
            children = list.sorted().toList();
        } catch (IOException e) {
            return List.of("cannot list fixture directory " + fixturesDir + ": " + e);
        }
        for (var child : children) {
            var name = child.getFileName().toString();
            var fixture = Fixtures.ALL.stream().filter(f -> f.name().equals(name)).findFirst();
            if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                problems.add(name + ": file outside every fixture directory");
            } else if (fixture.isEmpty()) {
                problems.add(name + ": fixture directory not listed in Fixtures.ALL");
            } else {
                var inputs = fixture.get().inputs(child);
                for (var file : FileTree.files(child, List.of(), f -> true)) {
                    if (!inputs.contains(file)) {
                        problems.add(FileTree.relative(fixturesDir, file) + ": not used by fixture " + name
                                + " (" + fixture.get().kind() + ")");
                    } else if (fixture.get().kind() != Fixture.Kind.LAUNCHER && !isText(file)) {
                        // A class file or archive named .java would only produce javac errors.
                        // LAUNCHER inputs may be any bytes: that check reads them in every charset.
                        problems.add(FileTree.relative(fixturesDir, file) + ": not a UTF-8 text file");
                    }
                }
            }
        }
        return problems;
    }

    private static boolean isText(Path file) {
        try {
            var bytes = Files.readAllBytes(file);
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
            for (var b : bytes) {
                if (b == 0) {
                    return false;
                }
            }
            return true;
        } catch (CharacterCodingException e) {
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /** {@link #layoutProblems(Path)} on a scratch tree with every kind of stray file. */
    private static void layoutCheckFindsStrayFiles() throws Exception {
        var tree = List.of(
                "Stray.java",
                "notes.txt",
                "unlisted/X.java",
                "source-annotation/fx/A.java",
                "source-annotation/fx/A.class",
                "bytecode-annotation/fx/B.java",
                "bytecode-annotation/fx/Pre.class",
                "foreign-requires/src/m/module-info.java",
                "foreign-requires/App.java",
                "foreign-jar/tree.txt",
                "foreign-jar/real.jar",
                "launcher-source/tools/Tool.java",
                "annotation-declaration/fx/Binary.java = class");
        var expected = List.of(
                "Stray.java: file outside every fixture directory",
                "annotation-declaration/fx/Binary.java: not a UTF-8 text file",
                "bytecode-annotation/fx/Pre.class: not used by fixture bytecode-annotation (BYTECODE)",
                "foreign-jar/real.jar: not used by fixture foreign-jar (FILES)",
                "foreign-requires/App.java: not used by fixture foreign-requires (DEPENDENCIES)",
                "launcher-source/tools/Tool.java: not used by fixture launcher-source (LAUNCHER)",
                "notes.txt: file outside every fixture directory",
                "source-annotation/fx/A.class: not used by fixture source-annotation (SOURCE)",
                "unlisted: fixture directory not listed in Fixtures.ALL");
        withScratch("spola-guardrails-layout-", scratch -> {
            TreeSpec.materialize(tree, scratch);
            assertAnyOrder(expected, layoutProblems(scratch));
        });
    }

    /**
     * The main check leaves out exactly the listed fixture directories: an annotation
     * declaration directly below the fixtures directory is reported, one inside a listed
     * fixture directory is not (the self-test covers it).
     */
    private static void onlyListedFixturesAreExcluded() throws Exception {
        withScratch("spola-guardrails-exclusion-", scratch -> {
            var fixtures = scratch.resolve("fixtures");
            Files.createDirectories(fixtures.resolve("source-annotation"));
            Files.createDirectories(fixtures.resolve("unlisted"));
            Files.writeString(fixtures.resolve("Stray.java"), "@interface Stray {\n}\n");
            Files.writeString(fixtures.resolve("unlisted/Other.java"), "@interface Other {\n}\n");
            Files.writeString(fixtures.resolve("source-annotation/Listed.java"), "@interface Listed {\n}\n");
            var project = new Project(scratch, scratch.resolve("out"), Fixtures.directories(fixtures), Map.of());
            var actual = Guardrails.check(project, new PrintStream(OutputStream.nullOutputStream()));
            assertInOrder(formatted(List.of(
                    new Violation("fixtures/Stray.java", 1, Rule.ANNOTATION_DECLARATION, "@interface Stray", ""),
                    new Violation("fixtures/unlisted/Other.java", 1, Rule.ANNOTATION_DECLARATION,
                            "@interface Other", ""))), formatted(actual));
        });
    }

    /**
     * Test sources are checked like the module's own sources: they are analysed in the
     * module (so package-private code and the test runner, read via {@code --add-reads},
     * resolve without errors) and a rule broken there is reported.
     */
    private static void testSourcesAreChecked() throws Exception {
        withScratch("spola-guardrails-tests-", scratch -> {
            write(scratch.resolve("m/src/module-info.java"), "module m {\n}\n");
            write(scratch.resolve("m/src/m/Hidden.java"), "package m;\n\nclass Hidden {\n}\n");
            write(scratch.resolve("r/src/module-info.java"), "module r {\n    exports r;\n}\n");
            write(scratch.resolve("r/src/r/Runner.java"), "package r;\n\npublic class Runner {\n}\n");
            write(scratch.resolve("m/test/m/HiddenTest.java"), String.join("\n",
                    "package m;",
                    "",
                    "import r.Runner;",
                    "",
                    "class HiddenTest {",
                    "    Hidden hidden = new Hidden();",
                    "    Runner runner = new Runner();",
                    "    @interface Tag {",
                    "    }",
                    "}",
                    ""));
            var project = new Project(scratch, scratch.resolve("out"), List.of(),
                    Map.of("m", scratch.resolve("m/src"), "r", scratch.resolve("r/src")),
                    Map.of("m", scratch.resolve("m/test")), Set.of("r"));
            var actual = Guardrails.check(project, new PrintStream(OutputStream.nullOutputStream()));
            assertInOrder(formatted(List.of(new Violation("m/test/m/HiddenTest.java", 8, Rule.ANNOTATION_DECLARATION,
                    "@interface m.HiddenTest.Tag", ""))), formatted(actual));
        });
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private interface ScratchAction {
        void run(Path scratch) throws Exception;
    }

    /** Runs {@code action} in a fresh scratch directory, which is deleted afterwards. */
    private static void withScratch(String prefix, ScratchAction action) throws Exception {
        var scratch = Files.createTempDirectory(prefix);
        try {
            action.run(scratch);
        } finally {
            FileTree.deleteRecursively(scratch);
        }
    }
}
