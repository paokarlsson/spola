package spola.codegen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** A temporary directory with source files for one test; deleted on close. */
final class Workspace implements AutoCloseable {

    final Path dir;

    private Workspace(Path dir) {
        this.dir = dir;
    }

    static Workspace create() {
        try {
            return new Workspace(Files.createTempDirectory("spola-codegen-test"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes {@code content} to {@code relative} (UTF-8, as given) and returns its path. */
    Path write(String relative, String content) {
        try {
            var file = dir.resolve(relative);
            Files.createDirectories(file.getParent());
            return Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String read(String relative) {
        try {
            return Files.readString(dir.resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Path path(String relative) {
        return dir.resolve(relative);
    }

    List<Path> roots() {
        return List.of(dir);
    }

    @Override
    public void close() {
        try (var paths = Files.walk(dir)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
