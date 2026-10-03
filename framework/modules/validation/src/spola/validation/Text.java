package spola.validation;

/**
 * Safe rendering of text in paths, messages and exception messages. The path rules are those
 * of {@code spola.json.codec}: printable ASCII only, everything else as {@code \}{@code uXXXX}
 * (lower-case hex), names from data cut after {@link #MAX_EXCERPT} characters.
 */
final class Text {

    /** Longest excerpt of a name from data (an {@code atKey} key) in a path, in characters. */
    static final int MAX_EXCERPT = 40;

    /** Longest message kept in a {@link Violation}, in characters of the given message. */
    static final int MAX_MESSAGE = 500;

    private Text() {
    }

    /** {@code [A-Za-z_$][A-Za-z0-9_$]*}, as in {@code spola.json.codec}. */
    static boolean isIdentifier(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$';
            if (!letter && !(i > 0 && c >= '0' && c <= '9')) {
                return false;
            }
        }
        return true;
    }

    /**
     * The first {@code count} characters of {@code text}; characters outside printable ASCII as
     * {@code \}{@code uXXXX}, and {@code "} and {@code \} escaped when {@code quoted}.
     */
    static void appendEscaped(StringBuilder out, String text, int count, boolean quoted) {
        for (int i = 0; i < count; i++) {
            appendAscii(out, text.charAt(i), quoted);
        }
    }

    private static void appendAscii(StringBuilder out, char c, boolean quoted) {
        if (c >= 0x20 && c < 0x7F) {
            if (quoted && (c == '"' || c == '\\')) {
                out.append('\\');
            }
            out.append(c);
        } else {
            appendUnicodeEscape(out, c);
        }
    }

    private static void appendUnicodeEscape(StringBuilder out, char c) {
        out.append("\\u");
        String hex = Integer.toHexString(c);
        out.append("0".repeat(4 - hex.length())).append(hex);
    }

    /**
     * {@code text} quoted as in a JSON string, cut after {@link #MAX_EXCERPT} characters with
     * the full length appended outside the quotes: {@code "aaaa..."... (10000 characters)}.
     */
    static String excerpt(String text) {
        var out = new StringBuilder(MAX_EXCERPT + 32).append('"');
        int shown = Math.min(text.length(), MAX_EXCERPT);
        appendEscaped(out, text, shown, true);
        out.append('"');
        if (shown < text.length()) {
            out.append("... (").append(text.length()).append(" characters)");
        }
        return out.toString();
    }

    /**
     * Free text in printable ASCII without quotes (every other character as
     * {@code \}{@code uXXXX}), at most {@code max} characters once escaped; when it does not
     * fit, it is cut and {@code ...} is appended. Used for
     * {@link ValidationException#getMessage()}.
     */
    static String ascii(String text, int max) {
        var out = new StringBuilder(Math.min(text.length(), max) + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int width = c >= 0x20 && c < 0x7F ? 1 : 6;
            if (out.length() + width > max) {
                return out.append("...").toString();
            }
            appendAscii(out, c, false);
        }
        return out.toString();
    }

    /**
     * A message as kept in a {@link Violation}: letters, digits and symbols of any script are
     * kept, but control characters (C0, DEL, C1), line and paragraph separators, bidirectional
     * formatting characters and unpaired surrogates are written as {@code \}{@code uXXXX}. A
     * message longer than {@link #MAX_MESSAGE} characters is cut and followed by its length.
     */
    static String message(String text) {
        int shown = Math.min(text.length(), MAX_MESSAGE);
        if (shown > 0 && Character.isHighSurrogate(text.charAt(shown - 1)) && shown < text.length()
                && Character.isLowSurrogate(text.charAt(shown))) {
            shown--; // never cut a pair in half
        }
        StringBuilder out = null;
        for (int i = 0; i < shown; i++) {
            char c = text.charAt(i);
            boolean unsafe;
            if (Character.isHighSurrogate(c)) {
                unsafe = i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1));
                if (!unsafe) {
                    if (out != null) {
                        out.append(c).append(text.charAt(i + 1));
                    }
                    i++;
                    continue;
                }
            } else {
                unsafe = Character.isLowSurrogate(c) || isUnsafe(c);
            }
            if (unsafe && out == null) {
                out = new StringBuilder(shown + 16).append(text, 0, i);
            }
            if (out != null) {
                if (unsafe) {
                    appendUnicodeEscape(out, c);
                } else {
                    out.append(c);
                }
            }
        }
        if (out == null && shown == text.length()) {
            return text;
        }
        if (out == null) {
            out = new StringBuilder(shown + 32).append(text, 0, shown);
        }
        if (shown < text.length()) {
            out.append("... (").append(text.length()).append(" characters)");
        }
        return out.toString();
    }

    private static boolean isUnsafe(char c) {
        return c < 0x20 || (c >= 0x7F && c <= 0x9F)
                || c == ' ' || c == ' '
                || c == '؜' || c == '‎' || c == '‏'
                || (c >= '‪' && c <= '‮')
                || (c >= '⁦' && c <= '⁩');
    }

    /** A code: non-empty printable ASCII without spaces. */
    static String code(String code) {
        if (code.isEmpty()) {
            throw new IllegalArgumentException("a code must not be empty");
        }
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c <= 0x20 || c >= 0x7F) {
                throw new IllegalArgumentException("a code must be printable ASCII without spaces; found "
                        + excerpt(code));
            }
        }
        return code;
    }
}
