package spola.client;

import java.nio.charset.StandardCharsets;

/**
 * Safe text for messages and excerpts: {@link #excerpt} turns a response body into
 * {@link RestClientResponseException#bodyExcerpt()}, {@link #ascii} makes any text one line of
 * printable ASCII.
 */
final class Text {

    /** The longest excerpt, in {@code char}s of the body before escaping. */
    static final int MAX_EXCERPT = 1_000;

    /** How many bytes of a body are decoded for an excerpt: enough for more than 1 000 chars. */
    static final int EXCERPT_BYTES = 8_192;

    private Text() {
    }

    /**
     * The excerpt of a body: its first {@code length} bytes (at most {@link #EXCERPT_BYTES} of
     * them) decoded as UTF-8 with U+FFFD for invalid bytes and an incomplete sequence at the end,
     * cut after {@link #MAX_EXCERPT} chars without splitting a surrogate pair, the characters of
     * {@link #mustEscape} written as {@code \}{@code uXXXX}, and {@code ...} after a cut.
     */
    static String excerpt(byte[] body, int length) {
        int decoded = Math.min(length, EXCERPT_BYTES);
        var text = new String(body, 0, decoded, StandardCharsets.UTF_8); // replaces malformed input
        if (text.length() <= MAX_EXCERPT) {
            return escape(text);
        }
        int end = MAX_EXCERPT;
        if (Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return escape(text.substring(0, end)) + "...";
    }

    /** {@code text} with every character of {@link #mustEscape} as {@code \}{@code uXXXX}. */
    static String escape(String text) {
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean lone = Character.isSurrogate(c) && !paired(text, i);
            if (lone || mustEscape(c)) {
                if (out == null) {
                    out = new StringBuilder(text.length() + 16).append(text, 0, i);
                }
                appendEscape(out, c);
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? text : out.toString();
    }

    /** Whether the surrogate at {@code i} is half of a pair. */
    private static boolean paired(String text, int i) {
        char c = text.charAt(i);
        if (Character.isHighSurrogate(c)) {
            return i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1));
        }
        return i > 0 && Character.isHighSurrogate(text.charAt(i - 1));
    }

    /**
     * The characters an excerpt never shows raw (lone surrogates are found by {@link #escape}):
     * controls (U+0000–U+001F, U+007F–U+009F), the line and paragraph separators (U+2028,
     * U+2029) and the bidi controls (U+200E, U+200F, U+202A–U+202E, U+2066–U+2069).
     */
    static boolean mustEscape(char c) {
        return c <= 0x1F
                || (c >= 0x7F && c <= 0x9F)
                || c == 0x2028 || c == 0x2029
                || c == 0x200E || c == 0x200F
                || (c >= 0x202A && c <= 0x202E)
                || (c >= 0x2066 && c <= 0x2069);
    }

    /**
     * {@code text} as one line of printable ASCII: every character outside U+0020–U+007E as
     * {@code \}{@code uXXXX}.
     */
    static String ascii(String text) {
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                if (out == null) {
                    out = new StringBuilder(text.length() + 16).append(text, 0, i);
                }
                appendEscape(out, c);
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? text : out.toString();
    }

    /**
     * {@code text} (already ASCII) cut to at most {@code max} characters: the first
     * {@code max - 3} or fewer and {@code ...} when it is longer. The cut is never inside a
     * {@code \}{@code uXXXX} escape.
     */
    static String limit(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = max - 3;
        int backslash = text.lastIndexOf('\\', end - 1);
        if (backslash >= 0 && backslash > end - 6 && backslash + 1 < text.length() && text.charAt(backslash + 1) == 'u') {
            end = backslash;
        }
        return text.substring(0, end) + "...";
    }

    private static void appendEscape(StringBuilder out, char c) {
        out.append(String.format("\\u%04x", (int) c));
    }
}
