package spola.guardrails;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What guardrails checks: a project root with its modules.
 *
 * @param root     the directory to check, normally {@code framework/}
 * @param output   the build output directory, the only place jar files may live
 * @param excluded directories left out of the source, bytecode and dependency checks
 *                 (the guardrails fixtures); the file check still covers them
 * @param modules  project module name to its source directory (the directory holding
 *                 {@code module-info.java}); these modules may be required and imported
 */
public record Project(Path root, Path output, List<Path> excluded, Map<String, Path> modules) {

    /**
     * Normalises all paths to absolute paths and copies the collections.
     *
     * @throws NullPointerException if a component is {@code null}
     */
    public Project {
        root = absolute(root);
        output = absolute(output);
        excluded = excluded.stream().map(Project::absolute).toList();
        var sorted = new TreeMap<String, Path>();
        modules.forEach((name, dir) -> sorted.put(Objects.requireNonNull(name), absolute(dir)));
        modules = Collections.unmodifiableMap(sorted);
    }

    private static Path absolute(Path path) {
        return Objects.requireNonNull(path).toAbsolutePath().normalize();
    }
}
