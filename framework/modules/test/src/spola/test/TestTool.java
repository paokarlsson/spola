package spola.test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.spi.ToolProvider;

/**
 * Command-line front end of the runner, exposed as the {@link ToolProvider} named
 * {@value #NAME}. It runs the test entry of one module:
 *
 * <pre>
 * --module NAME --class CLASS --method METHOD
 * </pre>
 *
 * <p>{@code CLASS} must be a public class in module {@code NAME}, in a package exported to
 * {@code spola.test}, and {@code METHOD} a public static method without parameters that
 * returns a {@link Suite}. The module is looked up in the module layer of {@code spola.test}
 * itself, so the caller (the build) loads both into one layer. This is the one place the
 * runner uses reflection: the build cannot call the entry method directly because it compiles
 * the method in the same run. The entry is named explicitly; nothing is scanned.
 *
 * <p>Exit codes: {@code 0} every test passed, {@code 1} a test or suite fixture failed (or no
 * test passed), {@code 2} usage error or the entry could not be called.
 *
 * <p>Fail closed: while the tests run, a shutdown hook is registered. If anything asks the
 * JVM to exit before the run is over (a test calling {@code System.exit(0)}, say), the hook
 * prints that and halts the JVM with {@value #EXIT_DURING_RUN}, so the run can never end
 * green without its report. A test that calls {@code Runtime.halt} itself bypasses all
 * shutdown hooks and cannot be caught in process.
 */
public final class TestTool implements ToolProvider {

    /** The tool name used to look the provider up. */
    public static final String NAME = "spola-test";

    /** The status the JVM halts with when it is asked to exit while tests run. */
    public static final int EXIT_DURING_RUN = 3;

    /** Creates the tool; called by {@link java.util.ServiceLoader}. */
    public TestTool() {
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This overload collects the output and writes it to the writers when the run ends; the
     * {@link PrintStream} overload, which the build uses, prints the report as it goes.
     */
    @Override
    public int run(PrintWriter out, PrintWriter err, String... args) {
        var outBytes = new ByteArrayOutputStream();
        var errBytes = new ByteArrayOutputStream();
        try (var outStream = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
             var errStream = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
            return run(outStream, errStream, args);
        } finally {
            out.write(outBytes.toString(StandardCharsets.UTF_8));
            out.flush();
            err.write(errBytes.toString(StandardCharsets.UTF_8));
            err.flush();
        }
    }

    @Override
    public int run(PrintStream out, PrintStream err, String... args) {
        Entry entry;
        try {
            entry = Entry.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("spola-test: " + e.getMessage());
            err.println("usage: --module NAME --class CLASS --method METHOD");
            return 2;
        }
        Suite suite;
        try {
            suite = entry.suite();
        } catch (EntryException e) {
            err.println("spola-test: test entry " + entry + ": " + e.getMessage());
            if (e.getCause() != null) {
                e.getCause().printStackTrace(err);
            }
            return 2;
        }
        out.println("spola-test: running " + entry);
        var guard = new Thread(() -> {
            out.flush();
            err.println();
            err.println("spola-test: the JVM was asked to exit (System.exit?) while the tests of " + entry
                    + " were running; the run is red (halting with status " + EXIT_DURING_RUN + ")");
            err.flush();
            Runtime.getRuntime().halt(EXIT_DURING_RUN);
        }, "spola-test exit guard");
        Runtime.getRuntime().addShutdownHook(guard);
        try {
            return Runner.run(suite, out).success() ? 0 : 1;
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(guard);
            } catch (IllegalStateException shuttingDown) {
                // The JVM is already exiting; the guard is running and halts it.
            }
        }
    }

    /** A test entry that cannot be called. */
    private static final class EntryException extends Exception {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        EntryException(String message) {
            super(message);
        }

        EntryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** {@code --module NAME --class CLASS --method METHOD}. */
    private record Entry(String module, String className, String method) {

        static Entry parse(String... args) {
            String module = null;
            String className = null;
            String method = null;
            for (int i = 0; i < args.length; i += 2) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value after " + args[i]);
                }
                var value = args[i + 1];
                switch (args[i]) {
                    case "--module" -> module = once(module, "--module", value);
                    case "--class" -> className = once(className, "--class", value);
                    case "--method" -> method = once(method, "--method", value);
                    default -> throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            if (module == null || className == null || method == null) {
                throw new IllegalArgumentException("--module, --class and --method are required");
            }
            return new Entry(module, className, method);
        }

        private static String once(String previous, String option, String value) {
            if (previous != null) {
                throw new IllegalArgumentException(option + " given twice");
            }
            return value;
        }

        Suite suite() throws EntryException {
            var layer = TestTool.class.getModule().getLayer();
            if (layer == null) {
                throw new EntryException("spola.test is not loaded as a named module");
            }
            var named = layer.findModule(module)
                    .orElseThrow(() -> new EntryException("module " + module + " is not in the layer of spola.test"));
            var type = Class.forName(named, className);
            if (type == null) {
                throw new EntryException("class " + className + " not found in module " + module);
            }
            try {
                var entry = type.getMethod(method);
                if (!Modifier.isStatic(entry.getModifiers()) || entry.getReturnType() != Suite.class) {
                    throw new EntryException("the entry must be public static " + Suite.class.getName() + " "
                            + method + "()");
                }
                var suite = entry.invoke(null);
                if (suite == null) {
                    throw new EntryException("the entry returned null");
                }
                return (Suite) suite;
            } catch (NoSuchMethodException e) {
                throw new EntryException("no public method " + method + "() in " + className);
            } catch (IllegalAccessException e) {
                throw new EntryException("cannot call the entry (class not public, or package not exported to"
                        + " spola.test): " + e.getMessage());
            } catch (InvocationTargetException e) {
                throw new EntryException("the entry threw while building the suite", e.getCause());
            } catch (LinkageError e) { // includes ExceptionInInitializerError from a static initializer
                throw new EntryException("the entry class could not be initialised", e);
            }
        }

        @Override
        public String toString() {
            return module + "/" + className + "::" + method;
        }
    }
}
