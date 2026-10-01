package spola.guardrails;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The small text format of the {@code tree.txt} files of {@link Fixture.Kind#FILES} fixtures:
 * a file tree the self-test creates in a scratch directory, so that no real jar, class file,
 * build file or symbolic link is ever committed.
 *
 * <p>One entry per line; blank lines and lines starting with {@code #} are ignored:
 * <ul>
 *   <li>{@code path/to/file} creates an empty file (and its parent directories),</li>
 *   <li>{@code dir/} (trailing slash) creates a directory,</li>
 *   <li>{@code path -> target} creates a symbolic link {@code path} pointing at
 *       {@code target}, written exactly as given (relative to the link's directory).
 *       The target need not exist,</li>
 *   <li>{@code path = CONTENT} creates a file with generated content, see {@link #content(String)}.</li>
 * </ul>
 */
final class TreeSpec {

    /** Length of an end-of-central-directory record without comment. */
    private static final int ZIP_END_LENGTH = 22;

    private TreeSpec() {
    }

    /** Creates the tree described by {@code lines} below {@code scratch}. */
    static void materialize(List<String> lines, Path scratch) throws IOException {
        for (var line : lines) {
            var entry = line.strip();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            var arrow = entry.indexOf(" -> ");
            var equals = entry.indexOf(" = ");
            var end = arrow >= 0 ? arrow : equals >= 0 ? equals : entry.length();
            var path = entry.substring(0, end).strip();
            var file = scratch.resolve(path).normalize();
            if (!file.startsWith(scratch) || file.equals(scratch)) {
                throw new IllegalArgumentException("tree.txt entry escapes the scratch directory: " + entry);
            }
            Files.createDirectories(file.getParent());
            if (arrow >= 0) {
                Files.createSymbolicLink(file, Path.of(entry.substring(arrow + 4).strip()));
            } else if (equals >= 0) {
                Files.write(file, content(entry.substring(equals + 3).strip()));
            } else if (path.endsWith("/")) {
                Files.createDirectories(file);
            } else {
                Files.createFile(file);
            }
        }
    }

    /**
     * Generated file content:
     * <ul>
     *   <li>{@code class}: the class-file magic number followed by a few bytes,</li>
     *   <li>{@code zip(ENTRY, ...)}: a zip archive; an entry is {@code name} (empty) or
     *       {@code name = CONTENT} (nested),</li>
     *   <li>{@code jmod(ENTRY, ...)}: the jmod header followed by a zip archive,</li>
     *   <li>{@code script+zip(ENTRY, ...)}: a launcher script followed by a zip archive,</li>
     *   <li>{@code truncated-zip(ENTRY, ...)}: a zip archive without its end record, which
     *       starts like a zip file but which {@link java.util.zip.ZipFile} cannot open.</li>
     * </ul>
     */
    static byte[] content(String spec) throws IOException {
        if (spec.equals("class")) {
            return new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 69};
        }
        var open = spec.indexOf('(');
        if (open < 0 || !spec.endsWith(")")) {
            throw new IllegalArgumentException("unknown content in tree.txt: " + spec);
        }
        var kind = spec.substring(0, open);
        var prefix = switch (kind) {
            case "zip", "truncated-zip" -> new byte[0];
            case "jmod" -> new byte[] {'J', 'M', 1, 0};
            case "script+zip" -> "#!/bin/sh\nexec java -jar \"$0\" \"$@\"\n".getBytes(StandardCharsets.UTF_8);
            default -> throw new IllegalArgumentException("unknown content in tree.txt: " + spec);
        };
        var bytes = new ByteArrayOutputStream();
        bytes.write(prefix);
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : splitTopLevel(spec.substring(open + 1, spec.length() - 1))) {
                var equals = entry.indexOf(" = ");
                zip.putNextEntry(new ZipEntry(equals < 0 ? entry : entry.substring(0, equals).strip()));
                if (equals >= 0) {
                    zip.write(content(entry.substring(equals + 3).strip()));
                }
                zip.closeEntry();
            }
        }
        var result = bytes.toByteArray();
        return kind.equals("truncated-zip") ? Arrays.copyOf(result, result.length - ZIP_END_LENGTH) : result;
    }

    /** Splits {@code a, b = zip(c, d), e} at the commas outside parentheses. */
    private static List<String> splitTopLevel(String list) {
        var parts = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i <= list.length(); i++) {
            var c = i < list.length() ? list.charAt(i) : ',';
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                var part = list.substring(start, i).strip();
                if (!part.isEmpty()) {
                    parts.add(part);
                }
                start = i + 1;
            }
        }
        return parts;
    }
}
