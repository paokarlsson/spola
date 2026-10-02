package spola.demo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A temporary shop directory for one test: the settings file, the order file and whatever else
 * the test writes, and a way to run the whole program through its entry. Removed by
 * {@link #close()}.
 */
final class Shop implements AutoCloseable {

    /** The audit secret most tests use; long enough to be valid. */
    static final String SECRET = "Pr1vate-Audit-Key-7781";

    final Path dir;

    private Shop(Path dir) {
        this.dir = dir;
    }

    static Shop create() {
        try {
            return new Shop(Files.createTempDirectory("spola-demo-test"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes a file in the shop directory. */
    Path write(String name, String... lines) {
        try {
            return Files.writeString(dir.resolve(name), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads a file in the shop directory. */
    String read(String name) {
        try {
            return Files.readString(dir.resolve(name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A path as written in a properties file: forward slashes, which every platform accepts. */
    String property(String name) {
        return dir.resolve(name).toString().replace('\\', '/');
    }

    /** The settings file {@code inventory.properties}, with the order file {@code orders.txt} of this shop. */
    Path settings(String... lines) {
        var all = new ArrayList<String>();
        all.add("inventory.orders=" + property("orders.txt"));
        all.addAll(List.of(lines));
        return write("inventory.properties", all.toArray(String[]::new));
    }

    /** The argument that points the program at this shop's settings file. */
    String settingsArgument() {
        return "--config.file=" + dir.resolve("inventory.properties");
    }

    /** The environment most tests use: just the audit secret. */
    static Map<String, String> environment() {
        return Map.of("INVENTORY_AUDIT_SECRET", SECRET);
    }

    /** {@link #environment()} plus more variables. */
    static Map<String, String> environment(String... namesAndValues) {
        var all = new HashMap<>(environment());
        for (var i = 0; i < namesAndValues.length; i += 2) {
            all.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return all;
    }

    /** Runs the program with this shop's settings file, the given environment and further arguments. */
    Run run(Map<String, String> environment, String... args) {
        var all = new ArrayList<String>();
        all.add(settingsArgument());
        all.addAll(List.of(args));
        return Run.of(all, environment);
    }

    @Override
    public void close() throws IOException {
        try (var paths = Files.walk(dir)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * One run of the whole program through {@link InventoryApp#run}.
     *
     * @param exit the exit code
     * @param out what was written to the summary stream
     * @param log what was written to the log stream
     */
    record Run(int exit, String out, String log) {

        static Run of(List<String> args, Map<String, String> environment) {
            return of(streams -> InventoryApp.run(args, environment, streams.out(), streams.log()));
        }

        record Streams(PrintStream out, PrintStream log) {
        }

        interface Program {
            int run(Streams streams);
        }

        static Run of(Program program) {
            var out = new ByteArrayOutputStream();
            var log = new ByteArrayOutputStream();
            int exit;
            try (var outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
                 var logStream = new PrintStream(log, true, StandardCharsets.UTF_8)) {
                exit = program.run(new Streams(outStream, logStream));
            }
            return new Run(exit, out.toString(StandardCharsets.UTF_8), log.toString(StandardCharsets.UTF_8));
        }

        /** Everything the program wrote, summary and log. */
        String all() {
            return out + log;
        }

        List<String> outLines() {
            return out.lines().toList();
        }

        @Override
        public String toString() {
            return "exit " + exit + "\n--- out ---\n" + out + "--- log ---\n" + log;
        }
    }
}
