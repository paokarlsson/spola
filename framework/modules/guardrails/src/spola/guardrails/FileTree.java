package spola.guardrails;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * File-system helpers shared by the checks. Symbolic links are never followed: what a link
 * points to is not checked, so the file check reports every link instead (see
 * {@link #filesAndLinks(Path)}).
 */
final class FileTree {

    private FileTree() {
    }

    /** Every regular file below {@code root} matching {@code filter}, skipping {@code excluded} trees. */
    static List<Path> files(Path root, List<Path> excluded, Predicate<Path> filter) {
        return walk(root, excluded, filter, false);
    }

    /**
     * Every regular file and every symbolic link (to a file, a directory or nothing) below
     * {@code root}, with nothing excluded. Links are listed but not followed.
     */
    static List<Path> filesAndLinks(Path root) {
        return walk(root, List.of(), f -> true, true);
    }

    private static List<Path> walk(Path root, List<Path> excluded, Predicate<Path> filter, boolean links) {
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
                    // Without FOLLOW_LINKS, a link to a directory also arrives here, as a link.
                    if ((attrs.isRegularFile() || links && attrs.isSymbolicLink()) && filter.test(file)) {
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

    /** Whether the regular file {@code file} begins with the bytes {@code magic}. */
    static boolean startsWith(Path file, byte[] magic) {
        try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return Arrays.equals(in.readNBytes(magic.length), magic);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
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
