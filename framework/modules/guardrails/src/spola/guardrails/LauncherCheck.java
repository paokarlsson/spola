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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Source check of the files that do not end in {@code .java} (outside the build output and
 * the fixture directories).
 *
 * <p>The JDK runs Java code from a file of any name: the source launcher with
 * {@code java --source N FILE} or as {@code ./FILE} with a {@code #!} first line (JEP 330),
 * decoding it with the default charset (which {@code -Dfile.encoding} sets), and jshell with
 * {@code jshell FILE}. Deciding from a file's content that it is not Java has no clear end, so
 * this check is fail-closed:
 * <ol>
 *   <li>Only documents ({@link Allowlist#DOCUMENT_EXTENSIONS}: {@code .md}) may lie next to
 *       the {@code .java} sources. Every other file is reported as
 *       {@link Rule#UNCHECKABLE_SOURCE}, whatever it contains.</li>
 *   <li>A document is read every way the source launcher could read it: decoded with every
 *       charset the JDK has (malformed input replaced, as the launcher's reader does), a
 *       {@code #!} first line blanked up to the first {@code \r} or {@code \n} (as
 *       {@code BufferedReader.readLine} ends it), and parsed at every release from
 *       {@value #OLDEST_RELEASE} to the running JDK's. A document that javac parses in no
 *       reading cannot be run, and is skipped. If its UTF-8 reading parses, it is analysed
 *       like a {@code .java} file at the newest release that parses it; if another reading
 *       that differs from the UTF-8 one parses, it is reported as
 *       {@link Rule#UNCHECKABLE_SOURCE}, since that program is not the one analysed.</li>
 * </ol>
 * jshell runs any text, snippet by snippet, so a document can still hold jshell input; that
 * is a documented gap (see {@code PROGRESS.md}).
 */
final class LauncherCheck {

    /** The oldest release javac (and so the source launcher) of JDK 25 accepts for {@code --source}. */
    static final int OLDEST_RELEASE = 8;

    /**
     * The release of the running JDK: the {@code java} that runs the build is the one whose
     * launcher would run the file, and only that release takes {@code --enable-preview}.
     */
    private static final int CURRENT_RELEASE = Runtime.version().feature();

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
     * One way to read a document: its text as the launcher would compile it.
     *
     * @param file    the document
     * @param charset the charset it was decoded with
     * @param text    the decoded text, {@code #!} line blanked
     */
    private record Reading(Path file, Charset charset, String text) {
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
        var readings = documents.stream().flatMap(LauncherCheck::readings).toList();
        var parsedAt = newestParsingRelease(readings);
        int javaDocuments = 0;
        for (var document : documents) {
            var parsing = readings.stream()
                    .filter(r -> r.file().equals(document) && parsedAt.containsKey(r))
                    .toList();
            if (parsing.isEmpty()) {
                continue;
            }
            javaDocuments++;
            var relative = FileTree.relative(root, document);
            var utf8 = parsing.getFirst().charset().equals(StandardCharsets.UTF_8) ? parsing.getFirst() : null;
            if (utf8 != null) {
                violations.addAll(analyze(root, utf8, parsedAt.get(utf8)));
            }
            parsing.stream().filter(r -> r != utf8).findFirst().ifPresent(other -> violations.add(
                    new Violation(relative, 0, Rule.UNCHECKABLE_SOURCE,
                            "document that javac parses when read as " + other.charset().name(), "")));
        }
        return new Result(List.copyOf(violations), files.size() - documents.size(), javaDocuments);
    }

    /**
     * The distinct readings of {@code file} that could hold a declaration, UTF-8 first, then
     * ISO-8859-1, then every other charset of the JDK.
     */
    private static Stream<Reading> readings(Path file) {
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
        return result.stream();
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
     * For each reading javac parses without errors at some release, the newest such release.
     * One javac task per release parses all readings still open.
     */
    private static Map<Reading, Integer> newestParsingRelease(List<Reading> readings) {
        var result = new IdentityHashMap<Reading, Integer>();
        var open = new ArrayList<>(readings);
        for (int release = CURRENT_RELEASE; release >= OLDEST_RELEASE && !open.isEmpty(); release--) {
            var parsed = parsing(open, release);
            for (var reading : parsed) {
                result.put(reading, release);
            }
            open.removeAll(parsed);
        }
        return result;
    }

    /** The {@code readings} that javac parses without errors at {@code release}. */
    private static List<Reading> parsing(List<Reading> readings, int release) {
        var units = new IdentityHashMap<JavaFileObject, Reading>();
        for (int i = 0; i < readings.size(); i++) {
            units.put(new Source(readings.get(i), i), readings.get(i));
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var options = new ArrayList<>(List.of("-proc:none", "-nowarn", "-Xlint:none",
                // Every unit must report its own errors; the default stops reporting after 100.
                "-Xmaxerrs", String.valueOf(Integer.MAX_VALUE)));
        options.addAll(sourceOptions(release));
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
            if (reading == null) {
                // An error not tied to one unit says nothing about which ones parse.
                throw new IllegalStateException("javac cannot parse documents at release " + release + ": "
                        + diagnostic.getMessage(Locale.ROOT));
            }
            failed.add(reading);
        }
        return readings.stream().filter(r -> !failed.contains(r)).toList();
    }

    /** The source check of {@code reading} at {@code release}, as the launcher would compile it. */
    private static List<Violation> analyze(Path root, Reading reading, int release) {
        var source = new Source(reading, -1);
        var analysis = SourceCheck.runAnalysis(root, fileManager -> List.of(source), sourceOptions(release), Set.of());
        if (analysis.errors().isEmpty()) {
            return analysis.violations();
        }
        var violations = new ArrayList<>(analysis.violations());
        violations.add(new Violation(FileTree.relative(root, reading.file()),
                (int) analysis.errors().getFirst().getLineNumber(), Rule.UNCHECKABLE_SOURCE,
                "Java source javac cannot attribute", ""));
        return List.copyOf(violations);
    }

    /** The options the source launcher gives javac for {@code java --source N}. */
    private static List<String> sourceOptions(int release) {
        return release == CURRENT_RELEASE
                ? List.of("--source", String.valueOf(release), "--enable-preview")
                : List.of("--source", String.valueOf(release));
    }

    /**
     * A reading as a compilation unit under its file's URI (so reports name the file, and the
     * implicit class of a compact source file is named after it). Its name need not match a
     * public class, as in the source launcher. Readings parsed together get a distinct query
     * so that javac never mixes them up.
     */
    private static final class Source extends SimpleJavaFileObject {
        private final String text;

        Source(Reading reading, int index) {
            super(uri(reading.file(), index), Kind.SOURCE);
            this.text = reading.text();
        }

        private static URI uri(Path file, int index) {
            var uri = file.toAbsolutePath().normalize().toUri();
            return index < 0 ? uri : URI.create(uri + "?reading=" + index);
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
