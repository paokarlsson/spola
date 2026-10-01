package spola.guardrails;

import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Check of the files that do not end in {@code .java} (outside the build output and the
 * fixture directories).
 *
 * <p>The JDK runs Java code from a file of any name: the source launcher with
 * {@code java --source N FILE} or as {@code ./FILE} with a {@code #!} first line (JEP 330),
 * decoding it with the default charset (which {@code -Dfile.encoding} sets), and jshell with
 * {@code jshell FILE}. What such a file means depends on charset and release, so the check
 * never analyses one; it is fail-closed:
 * <ol>
 *   <li>Only documents ({@link Allowlist#DOCUMENT_EXTENSIONS}: {@code .md}) may lie next to
 *       the {@code .java} sources. Every other file is reported as
 *       {@link Rule#UNCHECKABLE_SOURCE}, whatever it contains.</li>
 *   <li>A document must not be Java. It is read every way the source launcher could read it:
 *       decoded with every charset the JDK has (malformed input replaced, as the launcher's
 *       reader does), a {@code #!} first line blanked up to the first {@code \r} or {@code \n}
 *       (as {@code BufferedReader.readLine} ends it), and parsed at every release javac
 *       accepts for {@code --source}, from the running JDK's down. A document that javac
 *       parses in any reading is reported as {@link Rule#UNCHECKABLE_SOURCE}; a document no
 *       reading parses cannot be run by the launcher, and passes.</li>
 * </ol>
 * jshell runs any text, snippet by snippet, so a document can still hold jshell input; that
 * is a documented gap (see {@code PROGRESS.md}).
 */
final class LauncherCheck {

    /**
     * The release of the running JDK: the {@code java} that runs the build is the one whose
     * launcher would run the file, and only that release takes {@code --enable-preview}.
     */
    private static final int CURRENT_RELEASE = Runtime.version().feature();

    /**
     * javac's diagnostic code for a {@code --source} release it no longer supports (JDK 25:
     * "Source option 7 is no longer supported. Use 8 or later."). The search for parsing
     * releases goes down from {@link #CURRENT_RELEASE} until javac reports this, so the oldest
     * release is javac's own and not a number written down here. Any other error that is not
     * tied to one document stops the check (fail-closed).
     */
    private static final String RELEASE_REMOVED = "compiler.err.option.removed.source";

    /** What the subject of a reported document starts with. */
    static final String JAVA_DOCUMENT = "document that javac parses as Java";

    /**
     * What the check found.
     *
     * @param violations    broken rules
     * @param notAllowed    files that are neither {@code .java} sources nor documents
     * @param javaDocuments documents that javac parses in some reading
     */
    record Result(List<Violation> violations, int notAllowed, int javaDocuments) {
    }

    /**
     * One way to read a file: its text as the launcher would compile it.
     *
     * @param file    the file
     * @param charset the charset it was decoded with
     * @param text    the decoded text, {@code #!} line blanked
     */
    private record Reading(Path file, Charset charset, String text) {
    }

    /**
     * A reading javac parses without errors.
     *
     * @param charset the charset of the reading
     * @param release the {@code --source} release it parses at
     */
    record JavaReading(Charset charset, int release) {
        /** The subject of the violation: {@link #JAVA_DOCUMENT} and this reading. */
        String subject() {
            return JAVA_DOCUMENT + " (read as " + charset.name() + ", --source " + release + ")";
        }
    }

    private LauncherCheck() {
    }

    /**
     * Checks {@code files}, none of which ends in {@code .java}.
     *
     * @param root paths in the report are relative to this directory
     */
    static Result run(Path root, List<Path> files) {
        var violations = new ArrayList<Violation>();
        var documents = new ArrayList<Path>();
        for (var file : files) {
            if (Allowlist.isDocument(file)) {
                documents.add(file);
            } else {
                violations.add(new Violation(FileTree.relative(root, file), 0, Rule.UNCHECKABLE_SOURCE,
                        "neither a .java source nor a document (" + String.join(", ", Allowlist.DOCUMENT_EXTENSIONS)
                                + ")", ""));
            }
        }
        var java = javaReadings(documents);
        java.forEach((document, reading) -> violations.add(new Violation(FileTree.relative(root, document), 0,
                Rule.UNCHECKABLE_SOURCE, reading.subject(), "")));
        return new Result(List.copyOf(violations), files.size() - documents.size(), java.size());
    }

    /**
     * For each of {@code files} that javac parses in some reading, the first such reading
     * found: newest release first, then UTF-8, ISO-8859-1 and every other charset of the JDK.
     * Files no reading parses are absent.
     */
    static Map<Path, JavaReading> javaReadings(List<Path> files) {
        var open = new ArrayList<Reading>();
        for (var file : files) {
            open.addAll(readings(file));
        }
        var result = new LinkedHashMap<Path, JavaReading>();
        for (int release = CURRENT_RELEASE; !open.isEmpty(); release--) {
            var parsed = parsing(open, release);
            if (parsed.isEmpty()) {
                break; // javac accepts no older release
            }
            for (var reading : parsed.get()) {
                result.putIfAbsent(reading.file(), new JavaReading(reading.charset(), release));
            }
            // One parsing reading settles the file; its other readings need not be parsed.
            open.removeIf(reading -> result.containsKey(reading.file()));
        }
        return result;
    }

    /**
     * The distinct readings of {@code file} that could hold a declaration, UTF-8 first, then
     * ISO-8859-1, then every other charset of the JDK.
     */
    private static List<Reading> readings(Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        var charsets = new LinkedHashSet<Charset>();
        charsets.add(StandardCharsets.UTF_8);
        charsets.add(StandardCharsets.ISO_8859_1);
        charsets.addAll(Charset.availableCharsets().values());
        var seen = new HashSet<String>();
        var result = new ArrayList<Reading>();
        for (var charset : charsets) {
            var text = launcherText(bytes, charset);
            if (mayDeclare(text) && seen.add(text)) {
                result.add(new Reading(file, charset, text));
            }
        }
        return result;
    }

    /**
     * The text the source launcher compiles: {@code bytes} decoded with {@code charset}
     * (malformed input replaced), and a first line starting with the bytes {@code #!}
     * replaced by an empty line, so that line numbers stay as in the file. The line ends at
     * {@code \r}, {@code \n} or {@code \r\n}, as {@code BufferedReader.readLine} ends it.
     */
    static String launcherText(byte[] bytes, Charset charset) {
        if (bytes.length < 2 || bytes[0] != '#' || bytes[1] != '!') {
            return new String(bytes, charset);
        }
        // The launcher consumes the bytes #! and decodes the rest, from which it drops one line.
        var text = new String(bytes, 2, bytes.length - 2, charset);
        int end = 0;
        while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') {
            end++;
        }
        if (end == text.length()) {
            return "\n";
        }
        int next = end + 1;
        if (text.charAt(end) == '\r' && next < text.length() && text.charAt(next) == '\n') {
            next++;
        }
        return "\n" + text.substring(next);
    }

    /**
     * Whether {@code text} could hold any declaration or import. Every non-empty compilation
     * unit has a semicolon (package, import, field or empty declaration) or an opening brace
     * (class, interface, module or method body), possibly written as a unicode escape, which
     * starts with a backslash; a text with none of them is at most comments and whitespace.
     */
    private static boolean mayDeclare(String text) {
        return text.indexOf(';') >= 0 || text.indexOf('{') >= 0 || text.indexOf('\\') >= 0;
    }

    /**
     * The {@code readings} that javac parses without errors at {@code release}, in one task;
     * empty if javac rejects the release itself ({@link #RELEASE_REMOVED}).
     *
     * @throws IllegalStateException on any other error not tied to one reading, since it says
     *                               nothing about which readings parse
     */
    private static Optional<List<Reading>> parsing(List<Reading> readings, int release) {
        var units = new IdentityHashMap<JavaFileObject, Reading>();
        for (int i = 0; i < readings.size(); i++) {
            units.put(new Source(readings.get(i), i), readings.get(i));
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var options = new ArrayList<>(List.of("-proc:none", "-nowarn", "-Xlint:none",
                // Every unit must report its own errors; the default stops reporting after 100.
                "-Xmaxerrs", String.valueOf(Integer.MAX_VALUE), "--source", String.valueOf(release)));
        if (release == CURRENT_RELEASE) {
            options.add("--enable-preview");
        }
        try (var fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(new StringWriter(), fileManager, diagnostics, options, null,
                    units.keySet());
            task.parse();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var failed = new HashSet<Reading>();
        for (var diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() != Diagnostic.Kind.ERROR) {
                continue;
            }
            var reading = diagnostic.getSource() == null ? null : units.get(diagnostic.getSource());
            if (reading != null) {
                failed.add(reading);
            } else if (RELEASE_REMOVED.equals(diagnostic.getCode())) {
                return Optional.empty();
            } else {
                throw new IllegalStateException("javac cannot parse documents at release " + release + ": "
                        + diagnostic.getMessage(Locale.ROOT));
            }
        }
        return Optional.of(readings.stream().filter(r -> !failed.contains(r)).toList());
    }

    /**
     * A reading as a compilation unit under its file's URI. Its name need not match a public
     * class, as in the source launcher. Each unit gets a distinct query so that javac never
     * mixes up two readings of one file.
     */
    private static final class Source extends SimpleJavaFileObject {
        private final String text;

        Source(Reading reading, int index) {
            super(URI.create(reading.file().toAbsolutePath().normalize().toUri() + "?reading=" + index), Kind.SOURCE);
            this.text = reading.text();
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return text;
        }

        @Override
        public boolean isNameCompatible(String simpleName, Kind kind) {
            return true;
        }
    }
}
