package spola.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * A strict reader of PEM files (RFC 7468) without the preview PEM API of JDK 25: a sequence of
 * blocks {@code -----BEGIN label-----}, Base64 lines, {@code -----END label-----} with the same
 * label. Text outside the blocks is allowed (RFC 7468 §5.2, "explanatory text"), but a line that
 * starts with {@code -----} outside a block must be a BEGIN line. Inside a block only Base64 lines
 * are allowed (trailing spaces and tabs are dropped), so legacy encrypted PEM with
 * {@code Proc-Type} headers is refused. The file must be text: ASCII, lines ending in LF or CRLF.
 *
 * <p>Error messages give the file and a line number, never the content of a line: a block may be
 * a private key.
 */
final class Pem {

    /** Larger files are not PEM files this server needs (a chain is a few KiB). */
    static final int MAX_SIZE = 1 << 20;

    private static final String DASHES = "-----";
    private static final String BEGIN = "-----BEGIN ";
    private static final String END = "-----END ";

    private Pem() {
    }

    /**
     * One block.
     *
     * @param label the label, for example {@code CERTIFICATE} or {@code PRIVATE KEY}
     * @param der   the decoded bytes; the caller clears them when they are secret
     * @param line  the line of the BEGIN line, from 1
     */
    record Block(String label, byte[] der, int line) {
        /** Overwrites the decoded bytes with zeros. */
        void clear() {
            Arrays.fill(der, (byte) 0);
        }

        @Override
        public String toString() {
            return label + " at line " + line;
        }
    }

    /** The file's bytes, at most {@link #MAX_SIZE}; what goes wrong names the file and the kind. */
    static byte[] readFile(Path file, String what) {
        try {
            if (Files.size(file) > MAX_SIZE) {
                throw new TlsMaterialException(file, what + " " + file + " is larger than " + MAX_SIZE + " bytes");
            }
            return Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            throw new TlsMaterialException(file, what + " " + file + " does not exist", e);
        } catch (IOException e) {
            throw new TlsMaterialException(file, what + " " + file + " cannot be read: " + LoggedThrowable.describe(e), e);
        }
    }

    /**
     * Every block of {@code file}.
     *
     * @param what what the file is, for messages ("certificate chain", "private key")
     * @throws TlsMaterialException if the file cannot be read or is not well-formed PEM
     */
    static List<Block> read(Path file, String what) {
        var bytes = readFile(file, what);
        try {
            return parse(file, what, bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    static List<Block> parse(Path file, String what, byte[] bytes) {
        String where = what + " " + file;
        if (bytes.length == 0) {
            throw new TlsMaterialException(file, where + " is empty");
        }
        for (byte b : bytes) {
            if ((b < 0x20 || b > 0x7E) && b != '\n' && b != '\r' && b != '\t') {
                throw new TlsMaterialException(file, where + " is not a PEM file: it holds bytes that are not ASCII"
                        + " text (a DER file? convert it with openssl x509 -inform der or openssl pkcs8 -inform der)");
            }
        }
        var text = new String(bytes, StandardCharsets.US_ASCII);
        // a final line break ends the last line; it does not start an empty one
        var lines = (text.endsWith("\n") ? text.substring(0, text.length() - 1) : text).split("\n", -1);
        var blocks = new ArrayList<Block>();
        String label = null;
        int beginLine = 0;
        var base64 = new StringBuilder();
        try {
            for (int i = 0; i < lines.length; i++) {
                int number = i + 1;
                var line = lines[i];
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                if (line.indexOf('\r') >= 0) {
                    throw new TlsMaterialException(file, where + ": line " + number + " has a CR without LF");
                }
                if (label == null) {
                    if (line.startsWith(BEGIN) && line.endsWith(DASHES) && line.length() > BEGIN.length() + DASHES.length()) {
                        label = line.substring(BEGIN.length(), line.length() - DASHES.length());
                        if (!validLabel(label)) {
                            throw new TlsMaterialException(file, where + ": line " + number + " has a malformed BEGIN line");
                        }
                        beginLine = number;
                        base64.setLength(0);
                    } else if (line.startsWith(DASHES)) {
                        throw new TlsMaterialException(file, where + ": line " + number
                                + " starts with ----- but is not a BEGIN line (an END line without BEGIN?)");
                    }
                    continue;
                }
                if (line.startsWith(DASHES)) {
                    if (!line.equals(END + label + DASHES)) {
                        throw new TlsMaterialException(file, where + ": line " + number + " should be -----END " + label
                                + "----- (for the BEGIN line at line " + beginLine + ")");
                    }
                    blocks.add(new Block(label, decode(file, where, base64, beginLine), beginLine));
                    label = null;
                    continue;
                }
                var stripped = stripTrailingBlanks(line);
                if (stripped.isEmpty()) {
                    throw new TlsMaterialException(file, where + ": line " + number + " inside the block at line "
                            + beginLine + " is empty");
                }
                for (int k = 0; k < stripped.length(); k++) {
                    if (!base64Char(stripped.charAt(k))) {
                        throw new TlsMaterialException(file, where + ": line " + number + " inside the block at line "
                                + beginLine + " is not Base64" + (stripped.indexOf(':') >= 0
                                ? " (a PEM header such as Proc-Type: encrypted legacy keys are not supported;"
                                        + " convert with openssl pkcs8 -topk8 -nocrypt)" : ""));
                    }
                }
                base64.append(stripped);
            }
            if (label != null) {
                throw new TlsMaterialException(file, where + ": the BEGIN " + label + " line at line " + beginLine
                        + " has no END line");
            }
        } finally {
            clear(base64);
        }
        return blocks;
    }

    private static byte[] decode(Path file, String where, StringBuilder base64, int beginLine) {
        if (base64.isEmpty()) {
            throw new TlsMaterialException(file, where + ": the block at line " + beginLine + " is empty");
        }
        if (base64.length() % 4 != 0) { // RFC 7468 §3 (strict): padded Base64, so whole groups of four
            clear(base64);
            throw new TlsMaterialException(file, where + ": the block at line " + beginLine + " is not valid Base64");
        }
        var chars = new byte[base64.length()];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = (byte) base64.charAt(i);
        }
        try {
            return Base64.getDecoder().decode(chars);
        } catch (IllegalArgumentException e) { // the JDK's message would show a character of the block
            throw new TlsMaterialException(file, where + ": the block at line " + beginLine + " is not valid Base64");
        } finally {
            Arrays.fill(chars, (byte) 0);
            clear(base64);
        }
    }

    /** RFC 7468 §3: printable characters; a single space or '-' only between two of them. */
    private static boolean validLabel(String label) {
        char previous = ' ';
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
            boolean separator = c == ' ' || c == '-';
            boolean previousSeparator = previous == ' ' || previous == '-';
            if (separator && previousSeparator) {
                return false; // at the start, or two in a row
            }
            previous = c;
        }
        return !label.isEmpty() && previous != ' ' && previous != '-';
    }

    private static boolean base64Char(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/'
                || c == '=';
    }

    private static String stripTrailingBlanks(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) {
            end--;
        }
        return line.substring(0, end);
    }

    private static void clear(StringBuilder text) {
        for (int i = 0; i < text.length(); i++) {
            text.setCharAt(i, '\0');
        }
        text.setLength(0);
    }
}
