package spola.guardrails;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * File check: reports every jar archive outside the build output directory, every build
 * file that belongs to a third-party build tool and every symbolic link.
 *
 * <p>A file is a jar archive if its name says so ({@link #ARCHIVES}) or if its content does:
 * a zip file (whatever its name, also with a script or a jmod header in front of it) that
 * holds compiled Java code, a manifest or another archive. Zip files with only other content
 * (test data, say) are allowed.
 */
final class FileCheck {

    /**
     * File names (lower case) that only third-party build tools use: Maven, Gradle, Ant, Ivy,
     * Bazel, Buck, Pants, Mill, sbt, Leiningen, Clojure CLI and Make. Matching ignores case,
     * so Bazel's {@code BUILD} is {@code build}.
     */
    static final Set<String> BUILD_FILES = Set.of(
            "pom.xml", "mvnw", "mvnw.cmd", "maven-wrapper.properties",
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "gradlew", "gradlew.bat", "gradle-wrapper.properties", "gradle.properties", "libs.versions.toml",
            "build.xml", "ivy.xml",
            "build", "build.bazel", "workspace", "workspace.bazel", "workspace.bzlmod", "module.bazel",
            ".bazelrc", ".bazelversion",
            "buck", ".buckconfig", "pants.toml", "build.mill", "build.sc",
            "build.sbt",
            "project.clj", "deps.edn",
            "makefile", "gnumakefile");

    /**
     * File name endings (lower case) that only third-party build tools use: Gradle scripts
     * ({@code deps.gradle}, {@code x.gradle.kts}), sbt ({@code plugins.sbt}) and Bazel/Starlark
     * ({@code deps.bzl}), whatever the rest of the name.
     */
    static final List<String> BUILD_FILE_EXTENSIONS = List.of(".gradle", ".gradle.kts", ".sbt", ".bzl");

    /** Directory names (lower case) that only third-party build tools use; every file below them is reported. */
    static final Set<String> BUILD_DIRECTORIES = Set.of(".mvn", ".gradle", ".mill", ".bsp");

    /** Archive extensions (lower case) that package compiled Java code like a jar. */
    static final List<String> ARCHIVES = List.of(".jar", ".jmod", ".war", ".ear");

    /** First bytes of a class file. */
    static final byte[] CLASS_MAGIC = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};

    /** First bytes of a zip local file header. */
    private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};

    /** First bytes of an empty zip file (end of central directory record only). */
    private static final byte[] EMPTY_ZIP_MAGIC = {'P', 'K', 5, 6};

    /** Archives nested deeper than this are reported without looking further. */
    private static final int MAX_NESTING = 8;

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
            var link = Files.isSymbolicLink(file);
            if (link) {
                out.add(new Violation(relative, 0, Rule.SYMBOLIC_LINK, relative, ""));
            }
            if (!FileTree.isUnder(file, output)
                    && (ARCHIVES.stream().anyMatch(name::endsWith) || !link && holdsJavaCode(file))) {
                out.add(new Violation(relative, 0, Rule.FOREIGN_JAR, relative, ""));
            }
            if (BUILD_FILES.contains(name) || BUILD_FILE_EXTENSIONS.stream().anyMatch(name::endsWith)
                    || inBuildDirectory(relative)) {
                out.add(new Violation(relative, 0, Rule.FOREIGN_BUILD_FILE, relative, ""));
            }
        }
        return List.copyOf(out);
    }

    private static boolean inBuildDirectory(String relative) {
        var segments = relative.split("/");
        return Arrays.stream(segments, 0, segments.length - 1)
                .anyMatch(segment -> BUILD_DIRECTORIES.contains(segment.toLowerCase(Locale.ROOT)));
    }

    /**
     * Whether {@code file} is a zip archive that packages Java code. {@link ZipFile} finds the
     * central directory from the end of the file, so data in front of the archive (a launcher
     * script, the jmod header) does not hide it. Fail-closed: a file that starts like a zip
     * file but that {@link ZipFile} cannot open (damaged, or a compression method it does not
     * support) cannot be shown to be free of Java code, so it counts as holding some.
     */
    private static boolean holdsJavaCode(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (var zip = new ZipFile(file.toFile())) {
            for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                var entry = entries.nextElement();
                try (var content = zip.getInputStream(entry)) {
                    if (isJavaCode(entry.getName(), content, 1)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (ZipException notReadable) {
            return FileTree.startsWith(file, ZIP_MAGIC) || FileTree.startsWith(file, EMPTY_ZIP_MAGIC);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /**
     * Whether a zip entry is Java code: a class file (by name or by content), a manifest,
     * an archive by name, or a nested zip that holds Java code.
     */
    private static boolean isJavaCode(String entryName, InputStream content, int depth) throws IOException {
        var name = entryName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".class") || name.equals("meta-inf/manifest.mf")
                || ARCHIVES.stream().anyMatch(name::endsWith)) {
            return true;
        }
        var buffered = new BufferedInputStream(content);
        buffered.mark(ZIP_MAGIC.length);
        var head = buffered.readNBytes(ZIP_MAGIC.length);
        buffered.reset();
        if (Arrays.equals(head, CLASS_MAGIC)) {
            return true;
        }
        if (!Arrays.equals(head, ZIP_MAGIC)) {
            return false;
        }
        if (depth >= MAX_NESTING) {
            return true; // cannot vouch for what is not read
        }
        var nested = new ZipInputStream(buffered);
        for (var entry = nested.getNextEntry(); entry != null; entry = nested.getNextEntry()) {
            if (isJavaCode(entry.getName(), nested, depth + 1)) {
                return true;
            }
        }
        return false;
    }
}
