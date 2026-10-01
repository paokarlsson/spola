package spola.guardrails;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * File check: reports every jar archive outside the build output directory, every build
 * file that belongs to a third-party build tool and every symbolic link.
 */
final class FileCheck {

    /**
     * File names (lower case) that only third-party build tools use: Maven, Gradle, Ant, Ivy,
     * Bazel, sbt and Make. Matching ignores case, so Bazel's {@code BUILD} is {@code build}.
     */
    static final Set<String> BUILD_FILES = Set.of(
            "pom.xml", "mvnw", "mvnw.cmd", "maven-wrapper.properties",
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "gradlew", "gradlew.bat", "gradle-wrapper.properties", "gradle.properties",
            "build.xml", "ivy.xml",
            "build", "build.bazel", "workspace", "workspace.bazel", "workspace.bzlmod", "module.bazel",
            ".bazelrc", ".bazelversion",
            "build.sbt",
            "makefile", "gnumakefile");

    /** Archive extensions (lower case) that package compiled Java code like a jar. */
    static final List<String> ARCHIVES = List.of(".jar", ".jmod", ".war", ".ear");

    private FileCheck() {
    }

    /**
     * Checks {@code files}, which should come from {@link FileTree#filesAndLinks(Path)} so
     * that symbolic links are among them.
     */
    static List<Violation> run(Path root, List<Path> files, Path output) {
        var out = new ArrayList<Violation>();
        for (var file : files) {
            var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            var relative = FileTree.relative(root, file);
            if (Files.isSymbolicLink(file)) {
                out.add(new Violation(relative, 0, Rule.SYMBOLIC_LINK, relative, ""));
            }
            if (ARCHIVES.stream().anyMatch(name::endsWith) && !FileTree.isUnder(file, output)) {
                out.add(new Violation(relative, 0, Rule.FOREIGN_JAR, relative, ""));
            }
            if (BUILD_FILES.contains(name)) {
                out.add(new Violation(relative, 0, Rule.FOREIGN_BUILD_FILE, relative, ""));
            }
        }
        return List.copyOf(out);
    }
}
