package spola.guardrails;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.spi.ToolProvider;

/**
 * Command-line front end of guardrails, exposed as the {@link ToolProvider} named
 * {@value #NAME} and as a {@code main} method.
 *
 * <p>Command:
 * <pre>
 * check --root DIR --output DIR [--fixtures DIR] [--module NAME=SRCDIR]...
 *       [--tests NAME=TESTDIR]... [--test-reads MODULE]...
 * </pre>
 * {@code --tests} adds the test sources of a module (see {@link Project#tests()}) and
 * {@code --test-reads} the modules they read besides the module's own {@code requires}
 * ({@link Project#testReads()}). Relative paths are resolved against the current directory.
 * Exit codes: {@code 0} clean, {@code 1} violations, {@code 2} usage or analysis error.
 *
 * <p>The self-test of guardrails against its fixtures is part of the module's tests, run by
 * {@code spola.test}.
 */
public final class GuardrailsTool implements ToolProvider {

    /** The tool name used to look the provider up. */
    public static final String NAME = "spola-guardrails";

    /** Creates the tool; called by {@link java.util.ServiceLoader}. */
    public GuardrailsTool() {
    }

    /**
     * Runs the tool from the command line and exits with its exit code.
     *
     * @param args the command and its options
     */
    public static void main(String[] args) {
        System.exit(new GuardrailsTool().run(System.out, System.err, args));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int run(PrintWriter out, PrintWriter err, String... args) {
        // ToolProvider requires this overload; delegate via PrintStreams that write to the writers.
        var outStream = new PrintStream(new WriterOutputStream(out), true, StandardCharsets.UTF_8);
        var errStream = new PrintStream(new WriterOutputStream(err), true, StandardCharsets.UTF_8);
        try {
            return run(outStream, errStream, args);
        } finally {
            outStream.flush();
            errStream.flush();
        }
    }

    @Override
    public int run(PrintStream out, PrintStream err, String... args) {
        try {
            if (args.length == 0) {
                throw new IllegalArgumentException("missing command (check)");
            }
            var options = Options.parse(List.of(args).subList(1, args.length));
            return switch (args[0]) {
                case "check" -> check(options, out, err);
                default -> throw new IllegalArgumentException("unknown command: " + args[0]);
            };
        } catch (IllegalArgumentException e) {
            err.println("guardrails: " + e.getMessage());
            err.println("usage: check --root DIR --output DIR [--fixtures DIR] [--module NAME=SRCDIR]...");
            err.println("             [--tests NAME=TESTDIR]... [--test-reads MODULE]...");
            return 2;
        } catch (IllegalStateException | UncheckedIOException e) {
            err.println("guardrails: " + e.getMessage());
            return 2;
        }
    }

    private static int check(Options options, PrintStream out, PrintStream err) {
        var modules = namedDirs(options, "--module");
        var tests = namedDirs(options, "--tests");
        // Only the directories of the listed fixtures are left out, nothing else below --fixtures.
        var excluded = options.all("--fixtures").stream()
                .flatMap(dir -> Fixtures.directories(Path.of(dir)).stream())
                .toList();
        var project = new Project(options.path("--root"), options.path("--output"), excluded, modules, tests,
                Set.copyOf(options.all("--test-reads")));
        var violations = Guardrails.check(project, out);
        if (violations.isEmpty()) {
            out.println("guardrails: no violations");
            return 0;
        }
        violations.forEach(v -> err.println(v.format()));
        err.println("guardrails: " + violations.size() + " violation(s) of framework/REGLER.md");
        return 1;
    }

    /** The {@code NAME=DIR} values of {@code option}; a name may appear once. */
    private static Map<String, Path> namedDirs(Options options, String option) {
        var dirs = new LinkedHashMap<String, Path>();
        for (var value : options.all(option)) {
            int eq = value.indexOf('=');
            if (eq <= 0 || eq == value.length() - 1) {
                throw new IllegalArgumentException(option + " must be NAME=DIR: " + value);
            }
            if (dirs.put(value.substring(0, eq), Path.of(value.substring(eq + 1))) != null) {
                throw new IllegalArgumentException(option + " given twice for " + value.substring(0, eq));
            }
        }
        return dirs;
    }

    /** Parsed {@code --name value} pairs; options may repeat. */
    private record Options(Map<String, List<String>> values) {

        static Options parse(List<String> args) {
            var values = new LinkedHashMap<String, List<String>>();
            for (int i = 0; i < args.size(); i += 2) {
                var name = args.get(i);
                if (!name.startsWith("--") || i + 1 >= args.size()) {
                    throw new IllegalArgumentException("expected --option value, got: " + name);
                }
                values.computeIfAbsent(name, k -> new ArrayList<>()).add(args.get(i + 1));
            }
            return new Options(values);
        }

        List<String> all(String name) {
            return values.getOrDefault(name, List.of());
        }

        Path path(String name) {
            var all = all(name);
            if (all.size() != 1) {
                throw new IllegalArgumentException(name + " must be given exactly once");
            }
            return Path.of(all.getFirst());
        }
    }

    /**
     * Bridges a {@link PrintWriter} to the {@link PrintStream} based implementation.
     *
     * <p>Bytes are buffered and only decoded as UTF-8 on {@link #flush()}, never byte by
     * byte, so a multi-byte character is never split. The {@code PrintStream}s above are
     * created with auto-flush, which flushes after every {@code println}, i.e. after whole
     * lines; {@code run} flushes once more at the end. Nothing is lost as long as all
     * output goes through those streams, which it does.
     */
    private static final class WriterOutputStream extends OutputStream {
        private final PrintWriter writer;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        WriterOutputStream(PrintWriter writer) {
            this.writer = writer;
        }

        @Override
        public void write(int b) {
            buffer.write(b);
        }

        @Override
        public void flush() {
            writer.write(buffer.toString(StandardCharsets.UTF_8));
            writer.flush();
            buffer.reset();
        }
    }
}
