package spola.codegen;

import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.spi.ToolProvider;

/**
 * Command-line front end of the generator, the {@link ToolProvider} named {@value #NAME}; the
 * build runs it for {@code java Build.java codegen} and for the check in every build.
 *
 * <p>Commands:
 * <pre>
 * generate FILE RECORD   write RECORD's with-interface next to FILE
 * regenerate DIR...      generate every generated file below the directories again
 * check DIR...           report every generated file that does not match its record
 * </pre>
 * Errors are printed as one message each, without a stack trace. Exit codes: {@code 0} done
 * (or everything up to date), {@code 1} a generation error or a generated file that is out of
 * date, {@code 2} a usage error.
 */
public final class CodegenTool implements ToolProvider {

    /** The tool name used to look the provider up. */
    public static final String NAME = "spola-codegen";

    /** Creates the tool; called by {@link java.util.ServiceLoader}. */
    public CodegenTool() {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int run(PrintWriter out, PrintWriter err, String... args) {
        try {
            var arguments = Arrays.asList(args);
            var command = arguments.isEmpty() ? "" : arguments.getFirst();
            var rest = arguments.isEmpty() ? List.<String>of() : arguments.subList(1, arguments.size());
            return switch (command) {
                case "generate" -> rest.size() == 2 ? generate(Path.of(rest.get(0)), rest.get(1), out, err)
                        : usage(err, "generate takes a file and a record name");
                case "regenerate" -> rest.isEmpty() ? usage(err, "regenerate takes at least one directory")
                        : regenerate(paths(rest), out, err);
                case "check" -> rest.isEmpty() ? usage(err, "check takes at least one directory")
                        : check(paths(rest), out, err);
                default -> usage(err, command.isEmpty() ? "missing command" : "unknown command " + command);
            };
        } catch (CodegenException | UncheckedIOException e) {
            err.println(e.getMessage());
            return 1;
        } finally {
            out.flush();
            err.flush();
        }
    }

    private static List<Path> paths(List<String> arguments) {
        return arguments.stream().map(Path::of).toList();
    }

    private static int usage(PrintWriter err, String problem) {
        err.println(NAME + ": " + problem);
        err.println("usage: generate FILE RECORD | regenerate DIR... | check DIR...");
        return 2;
    }

    private static int generate(Path file, String record, PrintWriter out, PrintWriter err) {
        var written = GeneratedFiles.write(file, record);
        out.println((written.changed() ? "wrote " : "up to date: ") + written.file());
        written.note().ifPresent(note -> out.println("note: " + note));
        return 0;
    }

    private static int regenerate(List<Path> roots, PrintWriter out, PrintWriter err) {
        var result = GeneratedFiles.regenerate(roots);
        var changed = 0;
        for (var written : result.files()) {
            if (written.changed()) {
                out.println("wrote " + written.file());
                changed++;
            }
            written.note().ifPresent(note -> out.println("note: " + written.file() + ": " + note));
        }
        out.println(result.files().size() + " generated file(s), " + changed + " rewritten");
        result.problems().forEach(err::println);
        return result.problems().isEmpty() ? 0 : 1;
    }

    private static int check(List<Path> roots, PrintWriter out, PrintWriter err) {
        var problems = GeneratedFiles.check(roots);
        if (problems.isEmpty()) {
            out.println(GeneratedFiles.find(roots).size() + " generated file(s) up to date");
            return 0;
        }
        problems.forEach(err::println);
        err.println(problems.size() + " generated file(s) do not match their records; regenerate with "
                + GeneratedFiles.REGENERATE + " (in framework/)");
        return 1;
    }
}
