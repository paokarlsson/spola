package spola.security.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import spola.web.WebRequest;

/** The implementations behind {@link RequestMatcher}'s factories. */
final class Matchers {

    /** The {@link RequestMatcher#anyRequest()} instance, recognised by identity in {@code build()}. */
    static final RequestMatcher ANY = new RequestMatcher() {
        @Override
        public boolean matches(WebRequest request) {
            Objects.requireNonNull(request, "request");
            return true;
        }

        @Override
        public String toString() {
            return "anyRequest()";
        }
    };

    private Matchers() {
    }

    /** {@code under}/{@code exactly} over the decoded segments. */
    record Path(List<String> segments, boolean exact, String text) implements RequestMatcher {
        @Override
        public boolean matches(WebRequest request) {
            var actual = request.pathSegments();
            if (actual.isEmpty() && !request.path().equals("/")) {
                return false; // the router rejected the path (400) or it is OPTIONS *
            }
            if (exact ? actual.size() != segments.size() : actual.size() < segments.size()) {
                return false;
            }
            for (int i = 0; i < segments.size(); i++) {
                if (!segments.get(i).equals(actual.get(i))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            return (exact ? "exactly(" : "under(") + Text.quote(text) + ")";
        }
    }

    /** {@code method(method, inner)}; {@code GET} also matches {@code HEAD}. */
    record Method(String method, RequestMatcher inner) implements RequestMatcher {
        @Override
        public boolean matches(WebRequest request) {
            var actual = request.method();
            boolean sameMethod = actual.equals(method) || (method.equals("GET") && actual.equals("HEAD"));
            return sameMethod && inner.matches(request);
        }

        @Override
        public String toString() {
            return "method(" + method + ", " + inner + ")";
        }
    }

    /** The segments of a prefix or path after the rules in {@link RequestMatcher#under}. */
    static List<String> segments(String text, String what) {
        Objects.requireNonNull(text, what);
        if (!text.startsWith("/")) {
            throw new IllegalArgumentException(what + " " + Text.quote(text) + " must start with /");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new IllegalArgumentException(what + " " + Text.quote(text) + " holds a control character");
            }
            if ("{}*?%\\".indexOf(c) >= 0) {
                throw new IllegalArgumentException(what + " " + Text.quote(text) + " holds '" + c
                        + "'; it is matched literally, segment by segment, without patterns or percent-encoding");
            }
        }
        if (text.equals("/")) {
            return List.of();
        }
        var segments = new ArrayList<String>();
        for (var segment : text.substring(1).split("/", -1)) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException(what + " " + Text.quote(text)
                        + " has an empty segment (// or a trailing /)");
            }
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(what + " " + Text.quote(text) + " has a . or .. segment");
            }
            segments.add(segment);
        }
        return List.copyOf(segments);
    }

    /** Whether {@code text} is a {@code token} (RFC 9110 §5.6.2): one or more {@code tchar}. */
    static boolean isToken(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!isTchar(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** {@code tchar}: visible ASCII except the delimiters {@code "(),/:;<=>?@[\]{}}. */
    static boolean isTchar(char c) {
        return c > 0x20 && c < 0x7F && "\"(),/:;<=>?@[\\]{}".indexOf(c) < 0;
    }
}
