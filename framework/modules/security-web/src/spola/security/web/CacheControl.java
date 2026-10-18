package spola.security.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import spola.http.Headers;
import spola.http.Response;

/**
 * The {@code Cache-Control} rules of the security filter; see "Caches" in {@link WebSecurity}.
 *
 * <ul>
 *   <li>{@link #withoutSharedCache} for a response that sets {@code XSRF-TOKEN}: the handler's
 *       value is kept only when it already stops a shared cache on its own (a {@code no-store} or
 *       an unqualified {@code private}, and neither {@code public} nor {@code s-maxage});
 *       everything else, a missing field included, becomes one {@code Cache-Control: no-store}.</li>
 *   <li>{@link #forIdentity} for a response to a logged-in request: a missing field, one with
 *       {@code public} or {@code s-maxage} and one that does not parse become one
 *       {@code Cache-Control: no-store}; any other value the handler set is kept.</li>
 * </ul>
 *
 * <p>The parser and {@code withoutSharedCache} are a copy of {@code spola.web}'s
 * {@code CacheControl}, which applies the same rule to the session cookie: the modules share no
 * internal code. {@code CacheControlTests} checks that the copies have the same code and runs
 * the same table of values against this one.
 */
final class CacheControl {

    private CacheControl() {
    }

    /** One directive: its name in lower case, and whether it has an argument ({@code name=value}). */
    private record Directive(String name, boolean argument) {
    }

    /**
     * {@code response} unchanged if its {@code Cache-Control} already keeps it out of shared
     * caches, otherwise with every {@code Cache-Control} field replaced by one
     * {@code Cache-Control: no-store}; see the class documentation.
     */
    static Response withoutSharedCache(Response response) {
        var directives = directives(response.headers());
        boolean keep = directives != null && !shared(directives) && directives.stream()
                .anyMatch(d -> !d.argument() && (d.name().equals("no-store") || d.name().equals("private")));
        return keep ? response : noStore(response);
    }

    /**
     * For a response to a logged-in request: unchanged if it has a {@code Cache-Control}
     * without {@code public} and {@code s-maxage}, otherwise with one
     * {@code Cache-Control: no-store} instead (also when it has none, or an empty or malformed
     * one).
     */
    static Response forIdentity(Response response) {
        var directives = directives(response.headers());
        boolean keep = directives != null && !directives.isEmpty() && !shared(directives);
        return keep ? response : noStore(response);
    }

    /** Whether a directive lets a shared cache store the response although it may be personal. */
    private static boolean shared(List<Directive> directives) {
        return directives.stream().anyMatch(d -> d.name().equals("public") || d.name().equals("s-maxage"));
    }

    /**
     * The directives of every {@code Cache-Control} field, in order (none without the field), or
     * {@code null} if a value is not a list of {@code token [ "=" ( token / quoted-string ) ]}
     * (RFC 9111 §5.2) with optional whitespace and empty elements between them.
     */
    private static List<Directive> directives(Headers headers) {
        var directives = new ArrayList<Directive>();
        for (var value : headers.all("Cache-Control")) {
            int n = value.length();
            int i = 0;
            while (true) {
                while (i < n && (isWhitespace(value.charAt(i)) || value.charAt(i) == ',')) {
                    i++;
                }
                if (i == n) {
                    break;
                }
                int start = i;
                while (i < n && isTokenChar(value.charAt(i))) {
                    i++;
                }
                if (i == start) {
                    return null;
                }
                var name = value.substring(start, i).toLowerCase(Locale.ROOT);
                boolean argument = i < n && value.charAt(i) == '=';
                if (argument) {
                    i = argumentEnd(value, i + 1);
                    if (i < 0) {
                        return null;
                    }
                }
                directives.add(new Directive(name, argument));
                while (i < n && isWhitespace(value.charAt(i))) {
                    i++;
                }
                if (i < n && value.charAt(i) != ',') {
                    return null;
                }
            }
        }
        return directives;
    }

    /** The index after the token or quoted string at {@code i}, or -1 if there is none. */
    private static int argumentEnd(String value, int i) {
        int n = value.length();
        if (i < n && value.charAt(i) == '"') {
            i++;
            while (i < n) {
                char c = value.charAt(i++);
                if (c == '"') {
                    return i;
                }
                if (c == '\\') {
                    if (i == n) {
                        return -1;
                    }
                    i++;
                }
            }
            return -1;
        }
        int start = i;
        while (i < n && isTokenChar(value.charAt(i))) {
            i++;
        }
        return i == start ? -1 : i;
    }

    /** {@code response} with its {@code Cache-Control} fields replaced by one {@code Cache-Control: no-store}. */
    private static Response noStore(Response response) {
        var fields = new ArrayList<Headers.Field>();
        for (var field : response.headers().fields()) {
            if (!field.name().equalsIgnoreCase("Cache-Control")) {
                fields.add(field);
            }
        }
        fields.add(new Headers.Field("Cache-Control", "no-store"));
        return new Response(response.status(), Headers.of(fields), response.body());
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t';
    }

    /** {@code tchar} of RFC 9110 §5.6.2. */
    private static boolean isTokenChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }
}
