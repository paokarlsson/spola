package spola.test;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * Runs an exiting sample entry through {@link TestTool} in a JVM of its own, so the tool's
 * exit guard can be observed from outside (see {@code ToolTests}). Not an entry of any module
 * in the build.
 */
public final class ExitProbe {

    private ExitProbe() {
    }

    /**
     * Runs a sample entry and exits with the tool's exit code (which it never reaches if the
     * guard works: the guard halts first).
     *
     * @param args the {@link SampleEntries} method to run ({@code exiting} or
     *             {@code exitingEntry}), optionally followed by {@code writer} to use the
     *             {@link PrintWriter} overload of {@link TestTool#run(PrintWriter, PrintWriter, String...)}
     */
    public static void main(String[] args) {
        String[] toolArgs = {"--module", "spola.test", "--class", SampleEntries.class.getName(), "--method", args[0]};
        int exit;
        if (args.length > 1 && args[1].equals("writer")) {
            exit = new TestTool().run(new PrintWriter(System.out, false, StandardCharsets.UTF_8),
                    new PrintWriter(System.err, false, StandardCharsets.UTF_8), toolArgs);
        } else {
            exit = new TestTool().run(System.out, System.err, toolArgs);
        }
        System.exit(exit == 0 ? 100 : exit);
    }
}
