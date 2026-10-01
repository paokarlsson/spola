package spola.test;

/**
 * Runs {@link SampleEntries#exiting()} through {@link TestTool} in a JVM of its own, so the
 * tool's exit guard can be observed from outside (see {@code ToolTests}). Not an entry of any
 * module in the build.
 */
public final class ExitProbe {

    private ExitProbe() {
    }

    /**
     * Runs the exiting sample entry and exits with the tool's exit code (which it never
     * reaches if the guard works: the guard halts first).
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        int exit = new TestTool().run(System.out, System.err,
                "--module", "spola.test", "--class", SampleEntries.class.getName(), "--method", "exiting");
        System.exit(exit == 0 ? 100 : exit);
    }
}
