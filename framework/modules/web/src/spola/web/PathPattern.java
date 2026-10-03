package spola.web;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * A route pattern: {@code /} followed by segments separated by {@code /}. A segment is a
 * literal, a variable {@code {name}} (one whole segment) or, as the last segment only, a
 * catch-all {@code {name...}} (the rest of the path, at least one segment). {@code /} alone is
 * the root. A pattern may end with {@code /} (it then matches only paths that end with
 * {@code /}). No regular expressions, wildcards, partial segments or {@code %}.
 *
 * @param text     the pattern as written (after joining group prefixes)
 * @param segments the parsed segments; an empty literal last means a trailing slash
 */
record PathPattern(String text, List<Segment> segments) {

    /** One segment of a pattern. */
    sealed interface Segment {
        /** The segment's part of the pattern's shape: the literal, or a marker for a variable. */
        String shape();
    }

    /** Matches a decoded segment equal to {@code text}. */
    record Literal(String text) implements Segment {
        @Override
        public String shape() {
            return "=" + text;
        }
    }

    /** Matches any one non-empty segment. */
    record Variable(String name) implements Segment {
        @Override
        public String shape() {
            return "{}";
        }
    }

    /** Matches the rest of the path, at least one segment, none of them empty. */
    record CatchAll(String name) implements Segment {
        @Override
        public String shape() {
            return "{...}";
        }
    }

    /** The shape: two patterns with the same shape match exactly the same paths. */
    String shape() {
        var out = new StringBuilder();
        for (var segment : segments) {
            out.append('/').append(segment.shape());
        }
        return out.isEmpty() ? "/" : out.toString();
    }

    /**
     * Parses the raw segments of {@code pattern} (everything after the leading {@code /}),
     * adding a description of every problem to {@code problems}.
     *
     * @param pattern     the pattern text, for messages
     * @param allowTrailingSlash whether an empty last segment is allowed (not for group prefixes)
     * @return the segments, or {@code null} if there were problems
     */
    static List<Segment> parse(String pattern, boolean allowTrailingSlash, List<String> problems) {
        int before = problems.size();
        if (pattern.isEmpty() || pattern.charAt(0) != '/') {
            problems.add("does not start with /");
            return null;
        }
        if (pattern.length() == 1) {
            return List.of();
        }
        var raw = pattern.substring(1).split("/", -1);
        var segments = new ArrayList<Segment>(raw.length);
        var names = new HashSet<String>();
        for (int i = 0; i < raw.length; i++) {
            var text = raw[i];
            boolean last = i == raw.length - 1;
            if (text.isEmpty()) {
                if (!last) {
                    problems.add("has an empty segment (//)");
                } else if (!allowTrailingSlash) {
                    problems.add("ends with / (a group prefix cannot)");
                } else {
                    segments.add(new Literal(""));
                }
                continue;
            }
            var segment = segment(text, problems);
            if (segment == null) {
                continue;
            }
            String name = switch (segment) {
                case Variable v -> v.name();
                case CatchAll c -> c.name();
                case Literal l -> null;
            };
            if (name != null && !names.add(name)) {
                problems.add("has the variable name " + name + " more than once");
            }
            if (segment instanceof CatchAll && !last) {
                problems.add("has the catch-all {" + name + "...} before the last segment");
            }
            segments.add(segment);
        }
        return problems.size() == before ? List.copyOf(segments) : null;
    }

    /** One non-empty segment, or {@code null} after adding its problem. */
    private static Segment segment(String text, List<String> problems) {
        if (text.indexOf('%') >= 0) {
            problems.add("has % in a segment (write literals decoded; patterns are not percent-decoded)");
            return null;
        }
        boolean opens = text.charAt(0) == '{';
        boolean closes = text.charAt(text.length() - 1) == '}';
        if (opens && closes && text.length() >= 2 && text.indexOf('{', 1) < 0 && text.indexOf('}') == text.length() - 1) {
            var inner = text.substring(1, text.length() - 1);
            boolean catchAll = inner.endsWith("...");
            var name = catchAll ? inner.substring(0, inner.length() - 3) : inner;
            if (name.isEmpty()) {
                problems.add("has an empty variable name in " + Text.escape(text, Text.MAX_LOGGED));
                return null;
            }
            if (!isIdentifier(name)) {
                problems.add("has a variable name that is not a Java identifier: " + Text.escape(text, Text.MAX_LOGGED));
                return null;
            }
            return catchAll ? new CatchAll(name) : new Variable(name);
        }
        if (text.indexOf('{') >= 0 || text.indexOf('}') >= 0) {
            problems.add("has a partial segment " + Text.escape(text, Text.MAX_LOGGED)
                    + " (a variable must be a whole segment)");
            return null;
        }
        if (text.indexOf('*') >= 0 || text.indexOf('?') >= 0) {
            problems.add("has a wildcard in " + Text.escape(text, Text.MAX_LOGGED)
                    + " (not supported; use {name} or {name...})");
            return null;
        }
        if (text.equals(".") || text.equals("..")) {
            problems.add("has a . or .. segment (such paths are refused with 400)");
            return null;
        }
        boolean neverMatches = !Text.wellFormed(text).equals(text); // an unpaired surrogate is not UTF-8
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            neverMatches |= c < 0x20 || c == 0x7F || c == '\\'; // refused in request paths with 400
        }
        if (neverMatches) {
            problems.add("has a character that no request path can match in " + Text.escape(text, Text.MAX_LOGGED));
            return null;
        }
        return new Literal(text);
    }

    private static boolean isIdentifier(String name) {
        if (!Character.isJavaIdentifierStart(name.codePointAt(0))) {
            return false;
        }
        for (int i = Character.charCount(name.codePointAt(0)); i < name.length(); ) {
            int cp = name.codePointAt(i);
            if (!Character.isJavaIdentifierPart(cp) || Character.isIdentifierIgnorable(cp)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }
}
