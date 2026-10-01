package spola.test;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/** The {@code spola-test} tool that the build runs: entry lookup and exit codes. */
final class ToolTests {

    private ToolTests() {
    }

    /** A tool run: exit code, standard output and error output. */
    private record ToolRun(int exit, String out, String err) {
    }

    private static ToolRun tool(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int exit;
        try (var outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
             var errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            exit = new TestTool().run(outStream, errStream, args);
        }
        return new ToolRun(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static ToolRun entry(String method) {
        return tool("--module", "spola.test", "--class", SampleEntries.class.getName(), "--method", method);
    }

    /** A broken entry or command line and the error it must give. */
    private record Broken(String label, List<String> args, String error) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final String SAMPLE = "spola.test.SampleEntries";

    static Suite tests() {
        return suite("tool",
                test("a green entry exits 0 and prints the report", () -> {
                    var run = entry("passing");
                    assertEquals(0, run.exit(), run.err());
                    assertTrue(run.out().contains("spola-test: running spola.test/" + SAMPLE + "::passing\n"), run.out());
                    assertTrue(run.out().contains("sample: OK - 1 tests, 1 ok"), run.out());
                }),
                test("a red entry exits 1 and the report shows name, expected and actual", () -> {
                    var run = entry("failing");
                    assertEquals(1, run.exit());
                    assertTrue(run.out().contains("1) sample > fails\n"), run.out());
                    assertTrue(run.out().contains("expected: \"left\"\n"), run.out());
                    assertTrue(run.out().contains("actual:   \"right\"\n"), run.out());
                }),
                test("the PrintWriter overload gives the same result", () -> {
                    var out = new StringWriter();
                    var err = new StringWriter();
                    int exit = new TestTool().run(new PrintWriter(out), new PrintWriter(err),
                            "--module", "spola.test", "--class", SAMPLE, "--method", "failing");
                    assertEquals(1, exit);
                    assertTrue(out.toString().contains("sample: FAILED"), out.toString());
                }),
                test("a test that calls System.exit(0) makes the run red instead of ending it green", () -> {
                    var classes = Path.of(ExitProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                    var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                    var process = new ProcessBuilder(java, "--module-path", classes.toString(),
                            "--module", "spola.test/" + ExitProbe.class.getName())
                            .redirectErrorStream(true)
                            .start();
                    process.getOutputStream().close();
                    var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    int exit = process.waitFor();
                    assertEquals(TestTool.EXIT_DURING_RUN, exit, output);
                    assertTrue(output.contains("spola-test: the JVM was asked to exit (System.exit?) while the tests of"
                            + " spola.test/" + SAMPLE + "::exiting were running; the run is red"), output);
                    assertTrue(!output.contains("sample: OK"), output);
                }),
                test("the tool is named spola-test", () -> assertEquals("spola-test", new TestTool().name())),
                parameterized("a broken entry or command line exits 2 with a reason", List.of(
                        new Broken("wrong return type", List.of("--module", "spola.test", "--class", SAMPLE,
                                "--method", "notASuite"), "the entry must be public static spola.test.Suite notASuite()"),
                        new Broken("instance method", List.of("--module", "spola.test", "--class", SAMPLE,
                                "--method", "instance"), "the entry must be public static spola.test.Suite instance()"),
                        new Broken("missing method", List.of("--module", "spola.test", "--class", SAMPLE,
                                "--method", "absent"), "no public method absent() in " + SAMPLE),
                        new Broken("throwing entry", List.of("--module", "spola.test", "--class", SAMPLE,
                                "--method", "throwing"), "the entry threw while building the suite"),
                        new Broken("null suite", List.of("--module", "spola.test", "--class", SAMPLE,
                                "--method", "nothing"), "the entry returned null"),
                        new Broken("missing class", List.of("--module", "spola.test", "--class", "spola.test.Absent",
                                "--method", "suite"), "class spola.test.Absent not found in module spola.test"),
                        new Broken("missing module", List.of("--module", "no.such", "--class", SAMPLE,
                                "--method", "passing"), "module no.such is not in the layer of spola.test"),
                        new Broken("missing option", List.of("--module", "spola.test"),
                                "--module, --class and --method are required"),
                        new Broken("unknown option", List.of("--modul", "x"), "unknown option --modul"),
                        new Broken("repeated option", List.of("--module", "a", "--module", "b"), "--module given twice")),
                        broken -> {
                            var run = tool(broken.args().toArray(String[]::new));
                            assertEquals(2, run.exit(), run.out() + run.err());
                            assertTrue(run.err().contains(broken.error()), run.err());
                            assertEquals("", run.out());
                        }));
    }
}
