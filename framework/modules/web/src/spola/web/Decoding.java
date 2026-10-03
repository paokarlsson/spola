package spola.web;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict percent-decoding (RFC 3986 §2.1) to UTF-8 (RFC 3629) of path segments and query
 * pairs, and the checks the router applies to the decoded path before routing.
 *
 * <p>UTF-8 is decoded with the JDK's decoder set to report every malformed or unmappable
 * input (no replacement characters): an incomplete sequence, an overlong encoding
 * ({@code %C0%AF}), an encoded surrogate ({@code %ED%A0%80}) and anything above U+10FFFF fail.
 * The decoded text is never decoded again ({@code %252F} is the text {@code %2F}).
 */
final class Decoding {

    private Decoding() {
    }

    /** The most query pairs a request may have (as Tomcat's {@code maxParameterCount}). */
    static final int MAX_QUERY_PAIRS = 1_000;

    /**
     * Percent-decodes {@code raw}; {@code +} becomes a space when {@code plusIsSpace}.
     *
     * @return the text, or {@code null} if {@code raw} holds a {@code %} without two hex digits,
     *         a character outside visible ASCII, or bytes that are not UTF-8
     */
    static String decode(String raw, boolean plusIsSpace) {
        boolean plain = true;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c <= 0x20 || c >= 0x7F) {
                return null; // the HTTP parser lets only visible ASCII through; never decode anything else
            }
            if (c == '%' || (c == '+' && plusIsSpace)) {
                plain = false;
            }
        }
        if (plain) {
            return raw;
        }
        var bytes = new byte[raw.length()];
        int n = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%') {
                if (i + 2 >= raw.length()) {
                    return null;
                }
                int high = Character.digit(raw.charAt(i + 1), 16);
                int low = Character.digit(raw.charAt(i + 2), 16);
                if (high < 0 || low < 0 || raw.charAt(i + 1) > 0x7F || raw.charAt(i + 2) > 0x7F) {
                    return null;
                }
                bytes[n++] = (byte) (high << 4 | low);
                i += 2;
            } else if (c == '+' && plusIsSpace) {
                bytes[n++] = ' ';
            } else {
                bytes[n++] = (byte) c;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, n))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** The outcome of checking a request path. */
    sealed interface PathCheck {
    }

    /**
     * A path that passed every check.
     *
     * @param segments the decoded segments; empty for {@code /}; the last one is empty when the
     *                 path ends with {@code /}
     * @param decoded  {@code /} followed by the segments joined with {@code /}
     */
    record ValidPath(List<String> segments, String decoded) implements PathCheck {
    }

    /**
     * A path the router refuses with 400.
     *
     * @param reason a fixed description without request data
     */
    record InvalidPath(String reason) implements PathCheck {
    }

    /**
     * Splits the raw path on {@code /}, then decodes each segment and checks it: no encoded
     * {@code /} or {@code \}, no control characters (U+0000–U+001F, U+007F), no {@code .} or
     * {@code ..} (also encoded), no empty segment except a final one (a trailing slash).
     */
    static PathCheck checkPath(String raw) {
        if (raw.isEmpty() || raw.charAt(0) != '/') {
            return new InvalidPath("the path does not start with /");
        }
        if (raw.length() == 1) {
            return new ValidPath(List.of(), "/");
        }
        var rawSegments = raw.substring(1).split("/", -1);
        var segments = new ArrayList<String>(rawSegments.length);
        for (int i = 0; i < rawSegments.length; i++) {
            var rawSegment = rawSegments[i];
            if (rawSegment.isEmpty()) {
                if (i < rawSegments.length - 1) {
                    return new InvalidPath("the path has an empty segment (//)");
                }
                segments.add("");
                continue;
            }
            var segment = decode(rawSegment, false);
            if (segment == null) {
                return new InvalidPath("the path is not valid percent-encoded UTF-8");
            }
            for (int j = 0; j < segment.length(); j++) {
                char c = segment.charAt(j);
                if (c == '/') {
                    return new InvalidPath("the path has an encoded slash (%2F)");
                }
                if (c == '\\') {
                    return new InvalidPath("the path has an encoded backslash (%5C)");
                }
                if (c < 0x20 || c == 0x7F) {
                    return new InvalidPath("the path has an encoded control character");
                }
            }
            if (segment.equals(".") || segment.equals("..")) {
                return new InvalidPath("the path has a . or .. segment");
            }
            segments.add(segment);
        }
        return new ValidPath(List.copyOf(segments), "/" + String.join("/", segments));
    }

    /**
     * Parses a raw query after the {@code application/x-www-form-urlencoded} rules (WHATWG URL
     * Standard §5.1): pairs separated by {@code &}, empty pairs skipped, the first {@code =}
     * separates name and value (no {@code =}: empty value), {@code +} is a space, then strict
     * percent-decoding. Names keep the order they first appear in; values keep their order.
     *
     * @return the parameters, unmodifiable
     * @throws WebException 400 if a pair is not valid percent-encoded UTF-8 or there are more
     *                      than {@link #MAX_QUERY_PAIRS} pairs
     */
    static Map<String, List<String>> parseQuery(String raw) {
        return parseQuery(raw, "The query");
    }

    /**
     * {@link #parseQuery(String)} for a query or a form body; {@code subject} begins the 400's
     * detail ({@code "The query"}, {@code "The form body"}), which never quotes {@code raw}.
     */
    static Map<String, List<String>> parseQuery(String raw, String subject) {
        var params = new LinkedHashMap<String, List<String>>();
        int pairs = 0;
        int start = 0;
        while (start <= raw.length()) {
            int end = raw.indexOf('&', start);
            if (end < 0) {
                end = raw.length();
            }
            if (end > start) {
                if (++pairs > MAX_QUERY_PAIRS) {
                    throw new WebException(400, "Bad Request", subject + " has more than " + MAX_QUERY_PAIRS
                            + " parameters.");
                }
                var pair = raw.substring(start, end);
                int eq = pair.indexOf('=');
                var name = decode(eq < 0 ? pair : pair.substring(0, eq), true);
                var value = eq < 0 ? "" : decode(pair.substring(eq + 1), true);
                if (name == null || value == null) {
                    throw new WebException(400, "Bad Request", subject + " is not valid percent-encoded UTF-8.");
                }
                params.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            }
            start = end + 1;
        }
        var result = new LinkedHashMap<String, List<String>>();
        params.forEach((name, values) -> result.put(name, List.copyOf(values)));
        return java.util.Collections.unmodifiableSequencedMap(result);
    }
}
