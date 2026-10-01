package spola.guardrails;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** File-system helpers shared by the checks. Symbolic links are not followed. */
final class FileTree {

    private FileTree() {
    }

    /** Every regular file below {@code root} matching {@code filter}, skipping {@code excluded} trees. */
    static List<Path> files(Path root, List<Path> excluded, Predicate<Path> filter) {
        var normalizedRoot = root.toAbsolutePath().normalize();
        var skip = excluded.stream().map(p -> p.toAbsolutePath().normalize()).toList();
        var result = new ArrayList<Path>();
        try {
            Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return skip.contains(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && filter.test(file)) {
                        result.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk " + normalizedRoot, e);
        }
        result.sort(Comparator.naturalOrder());
        return List.copyOf(result);
    }

    static boolean hasExtension(Path file, String extension) {
        return file.getFileName().toString().endsWith(extension);
    }

    /** {@code file} relative to {@code root}, with {@code /} as separator. */
    static String relative(Path root, Path file) {
        var relative = root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize());
        return relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
    }

    static boolean isUnder(Path file, Path dir) {
        return file.toAbsolutePath().normalize().startsWith(dir.toAbsolutePath().normalize());
    }

    static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete " + root, e);
        }
    }
}
