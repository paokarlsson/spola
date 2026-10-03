package spola.security.web;

/** Short, escaped text for exception messages: no raw control characters, never long. */
final class Text {

    /** The most characters of a quoted value in a message. */
    static final int MAX_QUOTED = 60;

    private Text() {
    }

    /**
     * {@code text} in double quotes with control characters (C0, DEL, C1), line and paragraph
     * separators, bidirectional controls, lone surrogates, {@code "} and {@code \} escaped, cut
     * to {@link #MAX_QUOTED} characters with {@code ...} after a cut.
     */
    static String quote(String text) {
        var out = new StringBuilder("\"");
        int end = Math.min(text.length(), MAX_QUOTED);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        for (int i = 0; i < end; i++) {
            char c = text.charAt(i);
            boolean pair = Character.isHighSurrogate(c) && i + 1 < end && Character.isLowSurrogate(text.charAt(i + 1));
            if (pair) {
                out.append(c).append(text.charAt(++i));
            } else if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (Character.isISOControl(c) || Character.isSurrogate(c) || breaksOrReorders(c)) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append(end < text.length() ? "\"..." : "\"").toString();
    }

    private static boolean breaksOrReorders(char c) {
        return c == ' ' || c == ' ' || c == '؜' || c == '‎' || c == '‏'
                || (c >= '‪' && c <= '‮') || (c >= '⁦' && c <= '⁩') || c == '﻿';
    }
}
