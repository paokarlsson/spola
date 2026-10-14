package spola.client;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The rules for request headers: a name is a token (RFC 9110 5.6.2), a value is visible ASCII,
 * spaces and tabs without a space or tab first or last, and the names that the client or
 * {@code json(...)} sets are refused. Every problem is described without the value.
 */
final class HeaderRules {

    /**
     * Names refused in any case: the client sets the framing and the connection
     * ({@code java.net.http} does), and {@code json(...)} sets {@code Content-Type}.
     */
    static final List<String> FORBIDDEN = List.of("Connection", "Content-Length", "Content-Type", "Expect", "Host",
            "Keep-Alive", "Proxy-Connection", "TE", "Trailer", "Transfer-Encoding", "Upgrade");

    /** The longest part of an invalid name quoted in a message. */
    private static final int MAX_QUOTED_NAME = 40;

    private HeaderRules() {
    }

    /** One header as given: a valid name and value (checked before it is made). */
    record Header(String name, String value) {
    }

    /**
     * What is wrong with a header, or empty when it may be sent. The problem names the header
     * when its name is a token; it never quotes the value.
     */
    static Optional<String> problem(String name, String value) {
        var nameProblem = nameProblem(name);
        if (nameProblem.isPresent()) {
            return nameProblem;
        }
        for (var forbidden : FORBIDDEN) {
            if (forbidden.equalsIgnoreCase(name)) {
                return Optional.of("header " + name + " is not allowed: "
                        + (forbidden.equals("Content-Type") ? "json(...) sets it" : "the client sets it"));
            }
        }
        return valueProblem(value).map(problem -> "the value of header " + name + " " + problem);
    }

    /** What is wrong with a name, or empty for a token. Quotes only the part before the first bad character. */
    private static Optional<String> nameProblem(String name) {
        if (name.isEmpty()) {
            return Optional.of("a header name is empty");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!isTchar(c)) {
                var before = name.substring(0, Math.min(i, MAX_QUOTED_NAME)) + (i > MAX_QUOTED_NAME ? "..." : "");
                return Optional.of("a header name is not a token (RFC 9110 5.6.2): "
                        + (i == 0 ? "it starts with " : "\"" + before + "\" is followed by ") + describe(c));
            }
        }
        return Optional.empty();
    }

    /** What is wrong with a value, or empty. Says where, never what the value is. */
    private static Optional<String> valueProblem(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 0x21 && c <= 0x7E) || c == ' ' || c == '\t';
            if (!allowed) {
                return Optional.of("has " + (c < 0x20 || c == 0x7F ? describe(c) : "a character outside ASCII")
                        + " at index " + i + " (allowed: visible ASCII, space and tab)");
            }
        }
        if (!value.isEmpty() && isBlank(value.charAt(0))) {
            return Optional.of("starts with a space or tab");
        }
        if (!value.isEmpty() && isBlank(value.charAt(value.length() - 1))) {
            return Optional.of("ends with a space or tab");
        }
        return Optional.empty();
    }

    /** A character for a message: {@code ':'} for printable ASCII, {@code U+000D (CR)} otherwise. */
    private static String describe(char c) {
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

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t';
    }

    /** tchar (RFC 9110 5.6.2). */
    static boolean isTchar(char c) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
            return true;
        }
        return "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }

    /** Whether two names are the same header (names are case-insensitive, RFC 9110 5.1). */
    static boolean sameName(String a, String b) {
        return a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
    }
}
