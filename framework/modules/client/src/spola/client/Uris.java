package spola.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The base URI and the paths: the checks (RFC 3986 syntax, ASCII only) and the text
 * concatenation of base and path. No message quotes a query, a fragment or userinfo.
 */
final class Uris {

    /** The longest part of a path quoted in a message. */
    private static final int MAX_QUOTED_PATH = 200;

    private Uris() {
    }

    /**
     * The problems of a base URI, each as one line without the URI itself (it may hold userinfo
     * or a query with a secret); empty when it is usable.
     */
    static List<String> baseProblems(URI base) {
        var problems = new ArrayList<String>();
        if (!base.isAbsolute()) {
            problems.add("baseUri is relative: it needs the scheme http or https and a host");
            return problems;
        }
        var scheme = base.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            problems.add("baseUri has the scheme " + Text.ascii(Text.limit(base.getScheme(), 20))
                    + ": only http and https are supported");
        }
        if (base.isOpaque()) {
            problems.add("baseUri has no host (it is not of the form scheme://host)");
            return problems;
        }
        if (base.getRawUserInfo() != null) {
            problems.add("baseUri has userinfo: credentials belong in a header such as Authorization");
        }
        if (base.getHost() == null) {
            problems.add("baseUri has no host (a host name has letters, digits, - and . only)");
        }
        int port = base.getPort();
        if (base.getHost() != null && (port == 0 || port > 65_535)) {
            problems.add("baseUri has the port " + port + ": it must be between 1 and 65535");
        }
        if (base.getRawQuery() != null) {
            problems.add("baseUri has a query: give it with the path of each request");
        }
        if (base.getRawFragment() != null) {
            problems.add("baseUri has a fragment");
        }
        var path = base.getRawPath() == null ? "" : base.getRawPath();
        int bad = firstInvalid(path, false);
        if (bad >= 0) {
            problems.add("baseUri has a path with " + describe(path.charAt(bad)) + " at index " + bad
                    + ", which is not allowed in a URI path (RFC 3986 3.3)");
        }
        return problems;
    }

    /** The base as text: lower-case scheme, authority and path without a trailing {@code /}. */
    static String base(URI base) {
        var path = base.getRawPath() == null ? "" : base.getRawPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return base.getScheme().toLowerCase(Locale.ROOT) + "://" + base.getRawAuthority() + path;
    }

    /**
     * The problem of a request path, or empty: it starts with {@code /}, is a URI path with an
     * optional query (RFC 3986 3.3, 3.4) and has no fragment. Only the part before {@code ?} is
     * quoted.
     */
    static Optional<String> pathProblem(String path) {
        int query = path.indexOf('?');
        int fragment = path.indexOf('#');
        int pathEnd = path.length();
        if (query >= 0) {
            pathEnd = query;
        }
        if (fragment >= 0 && fragment < pathEnd) {
            pathEnd = fragment;
        }
        var shown = "\"" + Text.ascii(Text.limit(path.substring(0, pathEnd), MAX_QUOTED_PATH)) + "\"";
        if (!path.startsWith("/")) {
            return Optional.of("the path " + shown + " must start with /");
        }
        if (fragment >= 0) {
            return Optional.of("the path " + shown + " has a fragment (#), which is never sent");
        }
        int bad = firstInvalid(path.substring(0, pathEnd), false);
        if (bad >= 0) {
            return Optional.of("the path " + shown + " has " + describe(path.charAt(bad)) + " at index " + bad
                    + ", which is not allowed in a URI path (RFC 3986 3.3); percent-encode it");
        }
        if (query >= 0) {
            int badInQuery = firstInvalid(path.substring(query + 1), true);
            if (badInQuery >= 0) {
                return Optional.of("the query of the path " + shown + " has a character that is not allowed in a URI"
                        + " query (RFC 3986 3.4) at index " + (query + 1 + badInQuery) + "; percent-encode it");
            }
        }
        return Optional.empty();
    }

    /**
     * The index of the first character that is not allowed in a URI path ({@code query} false:
     * pchar and {@code /}) or query ({@code query} true: also {@code ?}), with {@code %} only
     * before two hex digits; -1 when there is none.
     */
    static int firstInvalid(String text, boolean query) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%') {
                if (i + 2 >= text.length() || !isHex(text.charAt(i + 1)) || !isHex(text.charAt(i + 2))) {
                    return i;
                }
                i += 2;
            } else if (!isPchar(c) && c != '/' && !(query && c == '?')) {
                return i;
            }
        }
        return -1;
    }

    /** pchar without pct-encoded: unreserved, sub-delims, {@code :} and {@code @} (RFC 3986 3.3). */
    private static boolean isPchar(char c) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
            return true;
        }
        return "-._~!$&'()*+,;=:@".indexOf(c) >= 0;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static String describe(char c) {
        if (c >= 0x21 && c <= 0x7E) {
            return c == '\'' ? "\"'\"" : "'" + c + "'";
        }
        return c < 0x80 ? String.format("U+%04X", (int) c) : "a character outside ASCII";
    }

    /** {@code uri} without query and fragment, as one line of ASCII: what messages show. */
    static String withoutQuery(URI uri) {
        var text = uri.getScheme() + "://" + uri.getRawAuthority() + (uri.getRawPath() == null ? "" : uri.getRawPath());
        return Text.ascii(text);
    }
}
