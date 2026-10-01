package spola.guardrails;

import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
 * annotation allowlist in code matches the table in {@code REGLER.md}.
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
        checkFixtureDirectoriesAreListed(fixturesDir, failures);
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
            var actual = new ArrayList<>(actualViolations(dir, scratch, fixture.kind()));
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

    private static List<Violation> actualViolations(Path dir, Path scratch, Fixture.Kind kind) throws IOException {
        return switch (kind) {
            case SOURCE -> SourceCheck.analyze(dir, javaFiles(dir), List.of(), Set.of()).violations();
            case BYTECODE -> {
                compile(javaFiles(dir), List.of("-d", scratch.toString()));
                yield BytecodeCheck.run(scratch, FileTree.files(scratch, List.of(), f -> FileTree.hasExtension(f, ".class")));
            }
            case DEPENDENCIES -> {
                compile(javaFiles(dir.resolve("src")),
                        List.of("--module-source-path", dir.resolve("src").toString(), "-d", scratch.toString()));
                yield DependencyCheck.run(scratch, FileTree.files(scratch, List.of(),
                        f -> f.getFileName().toString().equals("module-info.class")), Set.of());
            }
            case FILES -> {
                materialize(dir.resolve("tree.txt"), scratch);
                yield FileCheck.run(scratch, FileTree.filesAndLinks(scratch), scratch.resolve("out"));
            }
        };
    }

    private static List<Path> javaFiles(Path dir) {
        return FileTree.files(dir, List.of(), f -> FileTree.hasExtension(f, ".java"));
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

    /**
     * Creates the tree described in {@code tree} below {@code scratch}. One entry per line,
     * {@code #} starts a comment line:
     * <ul>
     *   <li>{@code path/to/file} creates an empty file (and its parent directories),</li>
     *   <li>{@code dir/} (trailing slash) creates a directory,</li>
     *   <li>{@code path -> target} creates a symbolic link {@code path} pointing at
     *       {@code target}, written exactly as given (relative to the link's directory).
     *       The target need not exist.</li>
     * </ul>
     */
    private static void materialize(Path tree, Path scratch) throws IOException {
        for (var line : Files.readAllLines(tree, StandardCharsets.UTF_8)) {
            var entry = line.strip();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            var arrow = entry.indexOf(" -> ");
            var path = arrow < 0 ? entry : entry.substring(0, arrow).strip();
            var file = scratch.resolve(path).normalize();
            if (!file.startsWith(scratch) || file.equals(scratch)) {
                throw new IllegalArgumentException("tree.txt entry escapes the scratch directory: " + entry);
            }
            Files.createDirectories(file.getParent());
            if (arrow >= 0) {
                Files.createSymbolicLink(file, Path.of(entry.substring(arrow + 4).strip()));
            } else if (path.endsWith("/")) {
                Files.createDirectories(file);
            } else {
                Files.createFile(file);
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

    private static void checkFixtureDirectoriesAreListed(Path fixturesDir, List<String> failures) {
        var listed = Fixtures.ALL.stream().map(Fixture::name).collect(Collectors.toSet());
        var present = new HashSet<String>();
        try (var children = Files.list(fixturesDir)) {
            children.filter(Files::isDirectory).forEach(d -> present.add(d.getFileName().toString()));
        } catch (IOException e) {
            failures.add("cannot list fixture directory " + fixturesDir + ": " + e);
            return;
        }
        var unlisted = new TreeSet<>(present);
        unlisted.removeAll(listed);
        if (!unlisted.isEmpty()) {
            failures.add("fixture directories not listed in Fixtures.ALL: " + unlisted);
        }
    }
}
