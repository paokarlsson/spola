package spola.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Configuration files for tests, written to a temporary directory that is removed afterwards. */
final class TempDir {

    private TempDir() {
    }

    @FunctionalInterface
    interface WithDir {
        void run(Path dir) throws Exception;
    }

    static void with(WithDir body) throws Exception {
        var dir = Files.createTempDirectory("spola-config-test");
        try {
            body.run(dir);
        } finally {
            try (var paths = Files.walk(dir)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    static Path write(Path dir, String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }
}
