package spola.client;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Safe text for messages and excerpts: {@link #excerpt} turns a response body into
 * {@link RestClientResponseException#bodyExcerpt()} (and, with the request's values hidden, into
 * {@link RestClientResponseException#safeExcerpt()}, which the message shows the start of),
 * {@link #ascii} makes any text one line of printable ASCII.
 */
final class Text {

    /** The longest excerpt, in {@code char}s of the body before escaping. */
    static final int MAX_EXCERPT = 1_000;

    /**
     * How many bytes of a body are decoded for an excerpt: as many as the client reads of a body
     * outside 2xx, so that every excerpt (and the hiding of the request's values in the message)
     * looks at the same window. Any 3 001 bytes decode to more than {@link #MAX_EXCERPT} chars
     * (a char takes at most 3 bytes), so the cut after 1 000 chars never depends on where the
     * bytes ended.
     */
    static final int EXCERPT_BYTES = RestClient.MAX_ERROR_BODY;

    /** What a message shows in place of a value of the request that the server sent back. */
    static final String HIDDEN = "<hidden>";

    /** What ends a text that was cut. */
    private static final String CUT = "...";

    /** The length of a {@code \}{@code uXXXX} escape. */
    private static final int ESCAPE_LENGTH = 6;

    private Text() {
    }

    /**
     * The excerpt of a body, as {@link RestClientResponseException#bodyExcerpt()} describes it:
     *
     * <ul>
     *   <li>the first {@code length} bytes, at most {@link #EXCERPT_BYTES} of them, decoded as
     *       UTF-8 with U+FFFD for invalid bytes and for an incomplete sequence at the end;</li>
     *   <li>every whole occurrence of a value in {@code hidden} replaced by {@link #HIDDEN} (pass
     *       an empty list for the excerpt itself, the request's values for the message);</li>
     *   <li>cut after {@link #MAX_EXCERPT} chars without splitting a surrogate pair;</li>
     *   <li>the characters of {@link #mustEscape} and lone surrogates as
     *       {@code \}{@code uXXXX};</li>
     *   <li>{@code ...} after a cut.</li>
     * </ul>
     *
     * {@code more} says that the body went on past {@code length} (reading stopped at a limit).
     * The bytes are then cut too: the excerpt ends with {@code ...}, an incomplete UTF-8 sequence
     * at the end (made by the cut, not sent by the server) is left out, and an end of the text
     * that is the start of a value in {@code hidden} is hidden as well.
     */
    static String excerpt(byte[] body, int length, boolean more, List<String> hidden) {
        boolean cut = more || length > EXCERPT_BYTES;
        int end = Math.min(length, EXCERPT_BYTES);
        if (cut) {
            end = wholeCharacters(body, end);
        }
        var text = hide(new String(body, 0, end, StandardCharsets.UTF_8), hidden, cut); // replaces malformed input
        if (text.length() <= MAX_EXCERPT) {
            return escape(text) + (cut ? CUT : "");
        }
        int keep = MAX_EXCERPT;
        if (Character.isHighSurrogate(text.charAt(keep - 1)) && Character.isLowSurrogate(text.charAt(keep))) {
            keep--;
        }
        return escape(text.substring(0, keep)) + CUT;
    }

    /**
     * {@code length} without an incomplete UTF-8 sequence at the end: up to the lead byte of a
     * sequence that needs more bytes than there are. Invalid bytes are left as they are.
     */
    static int wholeCharacters(byte[] bytes, int length) {
        int lead = length - 1;
        while (lead >= 0 && length - lead <= 3 && (bytes[lead] & 0xC0) == 0x80) {
            lead--;
        }
        if (lead < 0) {
            return length;
        }
        int b = bytes[lead] & 0xFF;
        int needed = b >= 0xF0 ? 4 : b >= 0xE0 ? 3 : b >= 0xC0 ? 2 : 1;
        return b < 0xF8 && length - lead < needed ? lead : length;
    }

    /**
     * {@code text} with every whole occurrence of a value in {@code values} replaced by
     * {@link #HIDDEN}; occurrences that overlap or touch become one {@link #HIDDEN}. When
     * {@code cut}, an end of the text that is the start of a value is hidden too (the rest of the
     * value was not read). Empty values are ignored. Values are matched as they are (case and
     * all), so a server that sends back only a part of a value, or changes it, shows that part.
     */
    static String hide(String text, List<String> values, boolean cut) {
        if (values.isEmpty() || text.isEmpty()) {
            return text;
        }
        var hidden = new boolean[text.length()];
        boolean any = false;
        for (var value : values) {
            if (value.isEmpty()) {
                continue;
            }
            for (int at = text.indexOf(value); at >= 0; at = text.indexOf(value, at + 1)) {
                Arrays.fill(hidden, at, at + value.length(), true);
                any = true;
            }
            if (cut) {
                for (int at = Math.max(0, text.length() - value.length() + 1); at < text.length(); at++) {
                    if (text.regionMatches(at, value, 0, text.length() - at)) {
                        Arrays.fill(hidden, at, text.length(), true);
                        any = true;
                        break;
                    }
                }
            }
        }
        if (!any) {
            return text;
        }
        var out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            if (!hidden[i]) {
                out.append(text.charAt(i));
            } else if (i == 0 || !hidden[i - 1]) {
                out.append(HIDDEN);
            }
        }
        return out.toString();
    }

    /** {@code text} with every character of {@link #mustEscape}, and every lone surrogate, as {@code \}{@code uXXXX}. */
    static String escape(String text) {
        return escapeWhere(text, i -> mustEscape(text.charAt(i))
                || (Character.isSurrogate(text.charAt(i)) && !paired(text, i)));
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
     * U+2029) and the bidi controls (U+061C, U+200E, U+200F, U+202A–U+202E, U+2066–U+2069, the
     * characters with the Unicode property {@code Bidi_Control}).
     */
    static boolean mustEscape(char c) {
        return c <= 0x1F
                || (c >= 0x7F && c <= 0x9F)
                || c == 0x2028 || c == 0x2029
                || c == 0x061C || c == 0x200E || c == 0x200F
                || (c >= 0x202A && c <= 0x202E)
                || (c >= 0x2066 && c <= 0x2069);
    }

    /**
     * {@code text} as one line of printable ASCII: every character outside U+0020–U+007E as
     * {@code \}{@code uXXXX}.
     */
    static String ascii(String text) {
        return escapeWhere(text, i -> text.charAt(i) < 0x20 || text.charAt(i) > 0x7E);
    }

    /** {@code text} with the character at every index that {@code escapeAt} accepts as {@code \}{@code uXXXX}. */
    private static String escapeWhere(String text, IntPredicate escapeAt) {
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            if (escapeAt.test(i)) {
                if (out == null) {
                    out = new StringBuilder(text.length() + 16).append(text, 0, i);
                }
                out.append(String.format("\\u%04x", (int) text.charAt(i)));
            } else if (out != null) {
                out.append(text.charAt(i));
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
        int end = max - CUT.length();
        int backslash = text.lastIndexOf('\\', end - 1);
        if (backslash >= 0 && backslash > end - ESCAPE_LENGTH && backslash + 1 < text.length()
                && text.charAt(backslash + 1) == 'u') {
            end = backslash;
        }
        return text.substring(0, end) + CUT;
    }

    /**
     * A character for a message, never one outside ASCII: {@code ':'} for visible ASCII,
     * {@code U+000D (CR)} or {@code U+007F} for other ASCII, and
     * {@code a character outside ASCII} for the rest.
     */
    static String describe(char c) {
        if (c >= 0x21 && c <= 0x7E) {
            return c == '\'' ? "\"'\"" : "'" + c + "'";
        }
        var name = switch (c) {
            case '\r' -> " (CR)";
            case '\n' -> " (LF)";
            case '\0' -> " (NUL)";
            case '\t' -> " (tab)";
            case ' ' -> " (space)";
            default -> "";
        };
        return c < 0x80 ? String.format("U+%04X", (int) c) + name : "a character outside ASCII";
    }
}
