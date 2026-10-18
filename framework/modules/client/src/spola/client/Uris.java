package spola.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The base URI and the paths: the checks (RFC 3986 syntax, ASCII only, no dot segments) and the
 * text concatenation of base and path. No message quotes a query, a fragment or userinfo.
 */
final class Uris {

    /** The problem of a path with a dot segment: a fixed text that quotes nothing of the path. */
    static final String DOT_SEGMENT = "the path has a segment that is . or .. (also as %2e or %2E, or with"
            + " ;parameters), which is not allowed: a server resolves it to another path";

    /** The problem of a base URI with a dot segment: a fixed text. */
    static final String BASE_DOT_SEGMENT = "baseUri has a path with a segment that is . or .. (also as %2e or %2E,"
            + " or with ;parameters), which is not allowed: a server resolves it to another path";

    /** The longest part of a path quoted in a message, after escaping. */
    private static final int MAX_QUOTED_PATH = 200;

    /** The longest scheme quoted in a message. */
    private static final int MAX_QUOTED_SCHEME = 20;

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
            problems.add("baseUri has the scheme " + Text.limit(Text.ascii(base.getScheme()), MAX_QUOTED_SCHEME)
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
            problems.add("baseUri has a path with " + Text.describe(path.charAt(bad)) + " at index " + bad
                    + ", which is not allowed in a URI path (RFC 3986 3.3)");
        }
        if (hasDotSegment(path)) {
            problems.add(BASE_DOT_SEGMENT);
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
     * optional query (RFC 3986 3.3, 3.4) without a dot segment, and has no fragment. Only the
     * part before {@code ?} is quoted, and the problem of a dot segment quotes nothing.
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
        var shown = "\"" + Text.limit(Text.ascii(path.substring(0, pathEnd)), MAX_QUOTED_PATH) + "\"";
        if (!path.startsWith("/")) {
            return Optional.of("the path " + shown + " must start with /");
        }
        if (fragment >= 0) {
            return Optional.of("the path " + shown + " has a fragment (#), which is never sent");
        }
        int bad = firstInvalid(path.substring(0, pathEnd), false);
        if (bad >= 0) {
            return Optional.of("the path " + shown + " has " + Text.describe(path.charAt(bad)) + " at index " + bad
                    + ", which is not allowed in a URI path (RFC 3986 3.3); percent-encode it");
        }
        if (hasDotSegment(path.substring(0, pathEnd))) {
            return Optional.of(DOT_SEGMENT);
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

    /**
     * Whether a path (without its query) has a segment that is {@code .} or {@code ..}, with
     * {@code %2e} and {@code %2E} counted as {@code .}: the dot segments that a server removes
     * or resolves against the segment before (RFC 3986 5.2.4; 2.3 and 6.2.2.2 for the
     * percent-encoded dot). A segment counts up to its first {@code ;}, since servers that read
     * path parameters (Tomcat, Jetty: {@code ;jsessionid=...}) drop them first, so that
     * {@code /..;/x} is {@code /x} to them. {@code ...}, {@code .x}, {@code a..b} and
     * {@code a;..} are no dot segments.
     */
    static boolean hasDotSegment(String path) {
        int start = 0;
        int semicolon = path.indexOf(';'); // the next ; at or after start, or -1
        while (true) {
            int slash = path.indexOf('/', start);
            int segmentEnd = slash < 0 ? path.length() : slash;
            if (semicolon >= 0 && semicolon < start) {
                semicolon = path.indexOf(';', start);
            }
            if (isDotSegment(path, start, semicolon >= 0 && semicolon < segmentEnd ? semicolon : segmentEnd)) {
                return true;
            }
            if (slash < 0) {
                return false;
            }
            start = slash + 1;
        }
    }

    /** Whether {@code path} from {@code start} to {@code end} is one or two dots, each as {@code .} or {@code %2e}. */
    private static boolean isDotSegment(String path, int start, int end) {
        int dots = 0;
        int i = start;
        while (i < end && dots < 3) {
            if (path.charAt(i) == '.') {
                i++;
            } else if (end - i >= 3 && path.regionMatches(true, i, "%2e", 0, 3)) {
                i += 3;
            } else {
                return false;
            }
            dots++;
        }
        return i == end && (dots == 1 || dots == 2);
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

    /** {@code uri} without query and fragment, as one line of ASCII: what messages show. */
    static String withoutQuery(URI uri) {
        var text = uri.getScheme() + "://" + uri.getRawAuthority() + (uri.getRawPath() == null ? "" : uri.getRawPath());
        return Text.ascii(text);
    }
}
