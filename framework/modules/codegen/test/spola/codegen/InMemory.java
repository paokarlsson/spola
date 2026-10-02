package spola.codegen;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import spola.test.AssertionFailure;

/**
 * Compiles source code in memory with {@code javax.tools} and runs it. The options are the
 * build's: every lint warning is an error, and doclint checks Javadoc on public and protected
 * API.
 */
final class InMemory {

    /** The build's javac options (Build.java), with every lint category on. */
    static final List<String> OPTIONS = List.of("--release", "25", "-proc:none", "-Xlint:all",
            "-Xdoclint:all/protected", "-Werror");

    private InMemory() {
    }

    /**
     * Compiles the sources (file name, e.g. {@code p/Person.java}, to text) and returns a class
     * loader for the classes. Fails the test with javac's diagnostics if anything does not
     * compile or gives a warning.
     */
    static ClassLoader compile(Map<String, String> sources) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var classes = new HashMap<String, ByteArrayOutputStream>();
        var standard = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        JavaFileManager files = new ForwardingJavaFileManager<>(standard) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                                                       FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
                    @Override
                    public OutputStream openOutputStream() {
                        var bytes = new ByteArrayOutputStream();
                        classes.put(className, bytes);
                        return bytes;
                    }
                };
            }
        };
        var units = new ArrayList<JavaFileObject>();
        sources.forEach((name, text) -> units.add(new SimpleJavaFileObject(URI.create("string:///" + name),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        }));
        var ok = compiler.getTask(null, files, diagnostics, OPTIONS, null, units).call();
        if (!ok || !diagnostics.getDiagnostics().isEmpty()) {
            var report = new StringBuilder("javac " + OPTIONS + " reported:");
            diagnostics.getDiagnostics().forEach(d -> report.append('\n').append(d.getKind()).append(' ')
                    .append(d.getSource() == null ? "" : d.getSource().getName()).append(':').append(d.getLineNumber())
                    .append(": ").append(d.getMessage(Locale.ROOT)));
            sources.forEach((name, text) -> report.append("\n--- ").append(name).append('\n').append(text));
            throw new AssertionFailure(report.toString());
        }
        var bytes = new HashMap<String, byte[]>();
        classes.forEach((name, out) -> bytes.put(name, out.toByteArray()));
        return new ClassLoader("codegen-test", ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                var code = bytes.get(name);
                if (code == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, code, 0, code.length);
            }
        };
    }

    /**
     * Compiles the sources and runs the class {@code className}, a {@code Supplier<List<String>>}
     * written with {@link #check}, and returns its lines. Uses reflection to create the
     * instance: the class exists only at run time (test code only; see ARCHITECTURE.md).
     */
    static List<String> run(Map<String, String> sources, String className) {
        var loader = compile(sources);
        Supplier<?> check;
        try {
            check = (Supplier<?>) Class.forName(className, true, loader).getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionFailure("cannot create " + className + ": " + e);
        }
        var lines = new ArrayList<String>();
        for (var line : (List<?>) check.get()) {
            lines.add(String.valueOf(line));
        }
        return lines;
    }

    /**
     * The source of {@code <pkg>.Check}, a public {@code Supplier<List<String>>} whose
     * {@code get()} runs {@code body} (statements that add lines to {@code out}). Inside the
     * body, {@code attempt(() -> ...)} gives the value's {@code toString()}, or
     * {@code threw <Exception>: <message>}.
     */
    static String check(String pkg, String body) {
        return """
                package %s;

                /** Runs the checks of one test; each line is something it saw. */
                public final class Check implements java.util.function.Supplier<java.util.List<String>> {

                    /** Creates the check. */
                    public Check() {
                    }

                    /** @return what the checks saw, one line each */
                    @Override
                    public java.util.List<String> get() {
                        var out = new java.util.ArrayList<String>();
                %s
                        return out;
                    }

                    static String attempt(java.util.function.Supplier<?> action) {
                        try {
                            return String.valueOf(action.get());
                        } catch (RuntimeException e) {
                            return "threw " + e.getClass().getSimpleName() + ": " + e.getMessage();
                        }
                    }
                }
                """.formatted(pkg, body.indent(8).stripTrailing());
    }
}
