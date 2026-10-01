package spola.guardrails;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
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

/**
 * Guardrails self-test: runs each check against its {@link Fixtures fixtures} and fails
 * unless every fixture yields exactly its expected violations. Also verifies that the
 * annotation allowlist in code matches the table in {@code REGLER.md}, that every file
 * below the fixtures directory belongs to a fixture that uses it, and that the main check
 * leaves out nothing but the listed fixture directories.
 */
final class SelfTest {

    /**
     * A row of the allowlist table in REGLER.md, e.g. {@code | `java.lang.Override` | SOURCE |}.
     * Coupled to the table's format on purpose: if the format changes, the self-test fails
     * and says so, rather than silently reading fewer rows.
     */
    private static final Pattern ALLOWLIST_ROW =
            Pattern.compile("^\\|\\s*`([\\w.]+)`\\s*\\|\\s*(SOURCE|CLASS|RUNTIME)\\s*\\|", Pattern.MULTILINE);

    private SelfTest() {
    }

    /**
     * Runs the self-test.
     *
     * @return {@code true} if every fixture behaved as expected
     */
    static boolean run(Path fixturesDir, Path rules, PrintStream out, PrintStream err) {
        var failures = new ArrayList<String>();
        checkAllowlistMatchesRules(rules, out, failures);
        layoutProblems(fixturesDir).forEach(problem -> failures.add("fixture layout: " + problem));
        checkLayoutCheck(out, failures);
        checkOnlyListedFixturesAreExcluded(out, failures);
        for (var fixture : Fixtures.ALL) {
            var problems = runFixture(fixturesDir.resolve(fixture.name()), fixture);
            if (problems.isEmpty()) {
                out.println("guardrails: fixture ok   " + fixture.name() + " (" + fixture.expected().size()
                        + " expected violations)");
            } else {
                failures.add("fixture " + fixture.name() + ":\n    " + String.join("\n    ", problems));
            }
        }
        failures.forEach(failure -> err.println("guardrails self-test FAILED: " + failure));
        return failures.isEmpty();
    }

    /** Violations reported by the check, compared with the expected ones; empty means pass. */
    static List<String> runFixture(Path dir, Fixture fixture) {
        if (!Files.isDirectory(dir)) {
            return List.of("missing fixture directory " + dir);
        }
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("spola-guardrails-fixture-");
            var actual = new ArrayList<>(actualViolations(dir, scratch, fixture));
            var expected = new ArrayList<>(fixture.expected());
            var problems = new ArrayList<String>();
            if (expected.isEmpty()) {
                problems.add("declares no expected violations; a fixture must break a rule");
            }
            var missing = new ArrayList<>(expected);
            actual.forEach(missing::remove);
            var unexpected = new ArrayList<>(actual);
            expected.forEach(unexpected::remove);
            missing.stream().sorted(Violation.ORDER).forEach(v -> problems.add("expected but not reported: " + v.format()));
            unexpected.stream().sorted(Violation.ORDER).forEach(v -> problems.add("reported but not expected: " + v.format()));
            return problems;
        } catch (IOException | RuntimeException e) {
            return List.of("could not run: " + e);
        } finally {
            if (scratch != null) {
                FileTree.deleteRecursively(scratch);
            }
        }
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
                result.addAll(FileCheck.run(scratch, FileTree.filesAndLinks(scratch), scratch.resolve("out")));
            }
            case LAUNCHER -> {
                // The same selection and analysis as the main check, with nothing left out.
                result.addAll(Guardrails.analyzeLauncherSources(dir, List.of(),
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

    private static void checkAllowlistMatchesRules(Path rules, PrintStream out, List<String> failures) {
        String text;
        try {
            text = Files.readString(rules, StandardCharsets.UTF_8);
        } catch (IOException e) {
            failures.add("cannot read " + rules + ": " + e);
            return;
        }
        var documented = ALLOWLIST_ROW.matcher(text).results().map(m -> m.group(1))
                .collect(Collectors.toCollection(TreeSet::new));
        if (!documented.equals(new TreeSet<>(Allowlist.ANNOTATIONS))) {
            failures.add("annotation allowlist in code " + new TreeSet<>(Allowlist.ANNOTATIONS)
                    + " does not match " + rules.getFileName() + " " + documented
                    + " (table rows are read with the pattern | `name` | SOURCE|CLASS|RUNTIME |;"
                    + " if the table format changed, update ALLOWLIST_ROW in SelfTest.java)");
        } else {
            out.println("guardrails: allowlist ok (" + documented.size() + " annotations match "
                    + rules.getFileName() + ")");
        }
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
                    } else if (!isText(file)) {
                        // A class file or archive named .java would only produce javac errors.
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
    private static void checkLayoutCheck(PrintStream out, List<String> failures) {
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
        withScratch(failures, "layout check", scratch -> {
            TreeSpec.materialize(tree, scratch);
            var actual = layoutProblems(scratch);
            if (!new TreeSet<>(actual).equals(new TreeSet<>(expected)) || actual.size() != expected.size()) {
                failures.add("layout check: expected " + expected + " but got " + actual);
            } else {
                out.println("guardrails: layout check ok (" + expected.size() + " stray or binary files found)");
            }
        });
    }

    /**
     * The main check leaves out exactly the listed fixture directories: an annotation
     * declaration directly below the fixtures directory is reported, one inside a listed
     * fixture directory is not (the self-test covers it).
     */
    private static void checkOnlyListedFixturesAreExcluded(PrintStream out, List<String> failures) {
        withScratch(failures, "fixture exclusion", scratch -> {
            var fixtures = scratch.resolve("fixtures");
            Files.createDirectories(fixtures.resolve("source-annotation"));
            Files.createDirectories(fixtures.resolve("unlisted"));
            Files.writeString(fixtures.resolve("Stray.java"), "@interface Stray {\n}\n");
            Files.writeString(fixtures.resolve("unlisted/Other.java"), "@interface Other {\n}\n");
            Files.writeString(fixtures.resolve("source-annotation/Listed.java"), "@interface Listed {\n}\n");
            var project = new Project(scratch, scratch.resolve("out"), Fixtures.directories(fixtures), Map.of());
            var actual = Guardrails.check(project, new PrintStream(OutputStream.nullOutputStream()));
            var expected = List.of(
                    new Violation("fixtures/Stray.java", 1, Rule.ANNOTATION_DECLARATION, "@interface Stray", ""),
                    new Violation("fixtures/unlisted/Other.java", 1, Rule.ANNOTATION_DECLARATION,
                            "@interface Other", ""));
            if (!actual.equals(expected)) {
                failures.add("fixture exclusion: expected " + expected.stream().map(Violation::format).toList()
                        + " but got " + actual.stream().map(Violation::format).toList());
            } else {
                out.println("guardrails: exclusion ok (only listed fixture directories are left out)");
            }
        });
    }

    private interface ScratchAction {
        void run(Path scratch) throws IOException;
    }

    private static void withScratch(List<String> failures, String what, ScratchAction action) {
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("spola-guardrails-selftest-");
            action.run(scratch);
        } catch (IOException | RuntimeException e) {
            failures.add(what + ": could not run: " + e);
        } finally {
            if (scratch != null) {
                FileTree.deleteRecursively(scratch);
            }
        }
    }
}
