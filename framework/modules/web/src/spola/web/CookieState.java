package spola.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import spola.http.Headers;

/**
 * The cookies of one request, parsed at most once, on first use, and shared by every
 * {@link WebRequest} derived from the same request ({@code withAttribute} keeps it), as
 * {@link QueryState} does for the query. A failed parse (more than {@link #MAX_COOKIES}) is
 * kept too: every later use throws the same 400.
 *
 * <p>The rules are those of RFC 6265 §4.2.1 and §5.4 as the server sees them: every
 * {@code Cookie} field is split on {@code ;} (several fields are joined as if separated by
 * {@code "; "}), each pair is trimmed of spaces and tabs, the name is the text before the
 * first {@code =} and must be a {@code token} (RFC 9110 §5.6.2), and the value is
 * {@code *cookie-octet} or the same inside {@code "}. The quotes stay part of the value and
 * nothing is percent-decoded: the value is opaque. A pair that breaks a rule is skipped, never
 * a 400, because other applications on the same site can set cookies the browser sends along
 * (Tomcat's {@code Rfc6265CookieProcessor} does the same).
 */
final class CookieState {

    /** The most cookies a request may have (valid pairs, after skipping the malformed ones). */
    static final int MAX_COOKIES = 200;

    private final Headers headers;
    private Map<String, List<String>> cookies;
    private WebException failure;
    private int parses;

    /** @param headers the request's headers; only the {@code Cookie} fields are read, on first use */
    CookieState(Headers headers) {
        this.headers = headers;
    }

    synchronized Map<String, List<String>> cookies() {
        if (cookies == null && failure == null) {
            parses++;
            try {
                cookies = parse(headers.all("Cookie"));
            } catch (WebException e) {
                failure = e;
            }
        }
        if (failure != null) {
            throw new WebException(failure.status(), failure.title(), failure.detail().orElse(null));
        }
        return cookies;
    }

    /** How many times the cookies were parsed (0 or 1), for tests. */
    synchronized int parses() {
        return parses;
    }

    /**
     * Parses the values of every {@code Cookie} field, in order.
     *
     * @return names in the order they first appear, values in order; unmodifiable
     * @throws WebException 400 if there are more than {@link #MAX_COOKIES} valid pairs (the
     *                      detail holds no names or values)
     */
    static Map<String, List<String>> parse(List<String> fields) {
        var found = new LinkedHashMap<String, List<String>>();
        int count = 0;
        for (var field : fields) {
            int start = 0;
            while (start <= field.length()) {
                int end = field.indexOf(';', start);
                if (end < 0) {
                    end = field.length();
                }
                var pair = trim(field, start, end);
                start = end + 1;
                int eq = pair.indexOf('=');
                if (eq < 0) {
                    continue; // also the empty piece after a trailing ";"
                }
                var name = pair.substring(0, eq);
                var value = pair.substring(eq + 1);
                if (!RouterBuilder.isToken(name) || !isCookieValue(value)) {
                    continue;
                }
                if (++count > MAX_COOKIES) {
                    throw new WebException(400, "Bad Request", "The request has more than " + MAX_COOKIES
                            + " cookies.");
                }
                found.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            }
        }
        var result = new LinkedHashMap<String, List<String>>();
        found.forEach((name, values) -> result.put(name, List.copyOf(values)));
        return Collections.unmodifiableSequencedMap(result);
    }

    /** {@code text[start, end)} without leading and trailing spaces and tabs. */
    private static String trim(String text, int start, int end) {
        while (start < end && isBlank(text.charAt(start))) {
            start++;
        }
        while (end > start && isBlank(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t';
    }

    /** Whether {@code value} is {@code *cookie-octet} or {@code DQUOTE *cookie-octet DQUOTE} (RFC 6265 §4.1.1). */
    static boolean isCookieValue(String value) {
        int start = 0;
        int end = value.length();
        if (end >= 2 && value.charAt(0) == '"' && value.charAt(end - 1) == '"') {
            start = 1;
            end--;
        }
        for (int i = start; i < end; i++) {
            if (!isCookieOctet(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code cookie-octet = %x21 / %x23-2B / %x2D-3A / %x3C-5B / %x5D-7E}: visible US-ASCII
     * without {@code "}, {@code ,}, {@code ;} and {@code \}.
     */
    static boolean isCookieOctet(char c) {
        return c >= 0x21 && c <= 0x7E && c != '"' && c != ',' && c != ';' && c != '\\';
    }
}
