package spola.guardrails;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What guardrails checks: a project root with its modules and their tests.
 *
 * @param root      the directory to check, normally {@code framework/}
 * @param output    the build output directory, the only place jar files may live
 * @param excluded  directories left out of the source, bytecode and dependency checks
 *                  (the directories of the listed guardrails fixtures, see
 *                  {@code Fixtures.directories}); the file check still covers them
 * @param modules   project module name to its source directory (the directory holding
 *                  {@code module-info.java}); these modules may be required and imported
 * @param tests     project module name to its test source directory: test sources belong to the
 *                  module's packages (no {@code module-info.java}) and are analysed as part of it
 * @param testReads project modules that test sources read besides their module's
 *                  {@code requires} (the test runner); they are given to javac as
 *                  {@code --add-reads} for every module with tests
 */
public record Project(Path root, Path output, List<Path> excluded, Map<String, Path> modules,
                      Map<String, Path> tests, Set<String> testReads) {

    /**
     * Normalises all paths to absolute paths and copies the collections.
     *
     * @throws NullPointerException     if a component is {@code null}
     * @throws IllegalArgumentException if {@code tests} or {@code testReads} names a module that is
     *                                  not in {@code modules}
     */
    public Project {
        root = absolute(root);
        output = absolute(output);
        excluded = excluded.stream().map(Project::absolute).toList();
        modules = normalised(modules);
        tests = normalised(tests);
        testReads = Collections.unmodifiableSet(new TreeSet<>(testReads));
        for (var name : tests.keySet()) {
            if (!modules.containsKey(name)) {
                throw new IllegalArgumentException("tests given for " + name + ", which is not a project module");
            }
        }
        for (var name : testReads) {
            if (!modules.containsKey(name)) {
                throw new IllegalArgumentException("test sources read " + name + ", which is not a project module");
            }
        }
    }

    /**
     * A project without test sources.
     *
     * @param root     the directory to check
     * @param output   the build output directory
     * @param excluded directories left out of the source, bytecode and dependency checks
     * @param modules  project module name to its source directory
     */
    public Project(Path root, Path output, List<Path> excluded, Map<String, Path> modules) {
        this(root, output, excluded, modules, Map.of(), Set.of());
    }

    private static Map<String, Path> normalised(Map<String, Path> dirs) {
        var sorted = new TreeMap<String, Path>();
        dirs.forEach((name, dir) -> sorted.put(Objects.requireNonNull(name), absolute(dir)));
        return Collections.unmodifiableMap(sorted);
    }

    private static Path absolute(Path path) {
        return Objects.requireNonNull(path).toAbsolutePath().normalize();
    }
}
