package spola.guardrails;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs all guardrails checks on a {@link Project}.
 *
 * <p>The checks are:
 * <ol>
 *   <li>source: every {@code .java} file is parsed and attributed; disallowed annotations,
 *       annotation declarations, foreign imports and reflective annotation reads are reported.
 *       Besides {@code .java} sources only documents ({@code .md}) may exist; any other file
 *       is reported, since the source launcher or jshell could run it as Java. A document
 *       that javac parses in some charset at some release is reported, never analysed
 *       (see {@link LauncherCheck}),</li>
 *   <li>bytecode: every class file (by name or content) is read; annotations outside the allowlist
 *       (including parameter and type annotations) and annotation types are reported,</li>
 *   <li>dependencies: every {@code module-info.class} is read; {@code requires} of modules that
 *       are neither JDK nor project modules are reported,</li>
 *   <li>files: jar archives outside the output directory (recognised by name and by
 *       content), third-party build files and symbolic links are reported.</li>
 * </ol>
 */
public final class Guardrails {

    private Guardrails() {
    }

    /**
     * Checks the whole project.
     *
     * @param project what to check
     * @param log     receives one summary line per check
     * @return all violations, sorted by {@link Violation#ORDER}; empty when the project is clean
     * @throws IllegalStateException if the sources cannot be analysed (javac reports errors),
     *                               since the source check would then be incomplete, or if a
     *                               {@code module-info.java} lies outside every listed module
     */
    public static List<Violation> check(Project project, PrintStream log) {
        var root = project.root();
        var violations = new ArrayList<Violation>();

        // 1. Source.
        violations.addAll(analyzeSources(project, log));

        // 2. Bytecode: class files are recognised by name and by content, so a renamed class
        //    file is checked too.
        var classFiles = FileTree.files(root, project.excluded(), f -> FileTree.hasExtension(f, ".class")
                || FileTree.startsWith(f, FileCheck.CLASS_MAGIC));
        violations.addAll(BytecodeCheck.run(root, classFiles));
        log.println("guardrails: bytecode     " + classFiles.size() + " .class files");

        // 3. Dependencies.
        var moduleInfos = FileTree.files(root, project.excluded(),
                f -> f.getFileName().toString().equals("module-info.class"));
        violations.addAll(DependencyCheck.run(root, moduleInfos, project.modules().keySet()));
        log.println("guardrails: dependencies " + moduleInfos.size() + " module-info.class files");

        // 4. Files: nothing is excluded, a jar, a pom.xml or a symbolic link is never acceptable.
        //    Links are reported because every walk above skips them: a linked .java or .jar
        //    would otherwise escape the source, bytecode and file checks.
        var allFiles = FileTree.filesAndLinks(root);
        violations.addAll(FileCheck.run(root, allFiles, project.output()));
        log.println("guardrails: files        " + allFiles.size() + " files");

        violations.sort(Violation.ORDER);
        return List.copyOf(violations);
    }

    /**
     * Source check of every {@code .java} file outside the excluded directories and the
     * output directory. Modules are analysed together so cross-module references resolve;
     * other {@code .java} files (Build.java) are analysed one directory at a time in the
     * unnamed module, since they are single-file launcher programs and two of them in
     * different directories may share a class name.
     */
    private static List<Violation> analyzeSources(Project project, PrintStream log) {
        var root = project.root();
        var notChecked = Stream.concat(project.excluded().stream(), Stream.of(project.output())).toList();
        var moduleSources = new ArrayList<Path>();
        var options = new ArrayList<String>();
        project.modules().forEach((name, dir) -> {
            moduleSources.addAll(FileTree.files(dir, project.excluded(), f -> FileTree.hasExtension(f, ".java")));
            options.addAll(List.of("--module-source-path", name + "=" + dir));
        });
        var looseSources = FileTree.files(root, notChecked, f -> FileTree.hasExtension(f, ".java")
                && project.modules().values().stream().noneMatch(dir -> FileTree.isUnder(f, dir)));
        var unlisted = looseSources.stream()
                .filter(f -> f.getFileName().toString().equals("module-info.java"))
                .map(f -> FileTree.relative(root, f))
                .toList();
        if (!unlisted.isEmpty()) {
            throw new IllegalStateException("module-info.java outside every listed module (add the module to "
                    + "MODULES in Build.java): " + String.join(", ", unlisted));
        }
        var outcomes = new ArrayList<SourceCheck.Outcome>();
        outcomes.add(analyzeModules(root, moduleSources, options, project));
        looseSources.stream()
                .collect(Collectors.groupingBy(Path::getParent, TreeMap::new, Collectors.toList()))
                .values()
                .forEach(group -> outcomes.add(SourceCheck.analyze(root, group, List.of(), project.modules().keySet())));
        var violations = new ArrayList<Violation>();
        var errors = new ArrayList<String>();
        for (var outcome : outcomes) {
            violations.addAll(outcome.violations());
            errors.addAll(outcome.errors());
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("source check cannot analyse the sources:\n  "
                    + String.join("\n  ", errors));
        }
        log.println("guardrails: source       " + (moduleSources.size() + looseSources.size()) + " .java files");
        violations.addAll(checkOtherFiles(root, notChecked, log));
        return violations;
    }

    /**
     * Check of every file without the {@code .java} extension outside {@code notChecked}
     * ({@link FileTree#isLauncherCandidate}), by {@link LauncherCheck}: only documents may
     * exist, and a document that javac parses as Java is reported.
     */
    static List<Violation> checkOtherFiles(Path root, List<Path> notChecked, PrintStream log) {
        var candidates = FileTree.files(root, notChecked, FileTree::isLauncherCandidate);
        var result = LauncherCheck.run(root, candidates);
        log.println("guardrails: source       " + candidates.size() + " other files: " + result.notAllowed()
                + " not allowed, " + (candidates.size() - result.notAllowed()) + " documents ("
                + result.javaDocuments() + " parse as Java)");
        return result.violations();
    }

    /** javac insists on {@code -d} with {@code --module-source-path}; analysis writes nothing there. */
    private static SourceCheck.Outcome analyzeModules(Path root, List<Path> sources, List<String> options,
                                                      Project project) {
        if (sources.isEmpty()) {
            return new SourceCheck.Outcome(List.of(), List.of());
        }
        Path scratch;
        try {
            scratch = Files.createTempDirectory("spola-guardrails-analysis-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            var withOutput = new ArrayList<>(options);
            withOutput.addAll(List.of("-d", scratch.toString()));
            return SourceCheck.analyze(root, sources, withOutput, project.modules().keySet());
        } finally {
            FileTree.deleteRecursively(scratch);
        }
    }
}
