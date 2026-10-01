package spola.guardrails;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * File check: reports every jar file outside the build output directory and every build
 * file that belongs to a third-party build tool.
 */
final class FileCheck {

    /** File names that only third-party build tools use (Maven, Gradle, Ant, Ivy, Bazel, sbt). */
    static final Set<String> BUILD_FILES = Set.of(
            "pom.xml", "mvnw", "mvnw.cmd", "maven-wrapper.properties",
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "gradlew", "gradlew.bat", "gradle-wrapper.properties", "gradle.properties",
            "build.xml", "ivy.xml",
            "build.bazel", "workspace", "workspace.bazel", "module.bazel",
            "build.sbt");

    private FileCheck() {
    }

    static List<Violation> run(Path root, List<Path> files, Path output) {
        var out = new ArrayList<Violation>();
        for (var file : files) {
            var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            var relative = FileTree.relative(root, file);
            if (name.endsWith(".jar") && !FileTree.isUnder(file, output)) {
                out.add(new Violation(relative, 0, Rule.FOREIGN_JAR, relative, ""));
            }
            if (BUILD_FILES.contains(name)) {
                out.add(new Violation(relative, 0, Rule.FOREIGN_BUILD_FILE, relative, ""));
            }
        }
        return List.copyOf(out);
    }
}
