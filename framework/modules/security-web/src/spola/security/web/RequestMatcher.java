package spola.security.web;

import java.util.Locale;
import java.util.Objects;
import spola.web.WebRequest;

/**
 * Which requests a rule of {@link WebSecurity} is about, the counterpart of Spring Security's
 * {@code requestMatchers(...)}. A matcher is a plain function of the request; the factories
 * cover the usual cases and a lambda covers the rest:
 *
 * <pre>{@code
 * RequestMatcher.under("/admin")                                  // /admin, /admin/, /admin/users, ...
 * RequestMatcher.exactly("/health")                               // /health only
 * RequestMatcher.method("POST", RequestMatcher.under("/orders"))  // POST below /orders
 * RequestMatcher.anyRequest()                                     // everything, last
 * request -> request.headers().contains("X-Internal")             // anything else
 * }</pre>
 *
 * <p><b>Segments, decoded.</b> {@link #under} and {@link #exactly} compare whole path segments
 * with the request's decoded {@link WebRequest#pathSegments()}, case-sensitively like the
 * router. {@code under("/admin")} therefore matches {@code /admin}, {@code /admin/} and
 * {@code /admin/x}, but not {@code /administrator} or {@code /Admin}, and it matches
 * {@code /%61dmin} (which the router routes as {@code /admin}). There is no Ant syntax
 * ({@code /admin/**}): a prefix is literal segments, so what a rule protects is exactly what a
 * route below the same prefix serves. A request whose path the router rejects with 400 (its
 * {@code pathSegments()} are empty while its path is not {@code /}) matches no {@code under}
 * and no {@code exactly}.
 *
 * <p><b>{@code HEAD} is {@code GET}.</b> {@code method("GET", ...)} also matches {@code HEAD},
 * because the router answers {@code HEAD} with the {@code GET} route; otherwise a rule for
 * {@code GET} could be bypassed by sending {@code HEAD}.
 *
 * <p>An exception from a matcher goes to the router's exception mappers (500 without one); the
 * request is never allowed because of it.
 */
@FunctionalInterface
public interface RequestMatcher {

    /**
     * Whether this matcher is about {@code request}.
     *
     * @param request the request, with its {@link SecurityContext} attribute set
     * @return {@code true} if it matches
     */
    boolean matches(WebRequest request);

    /**
     * Every request whose decoded path starts with the segments of {@code prefix}. A prefix
     * starts with {@code /} and has no empty segments (so no {@code //} and no trailing
     * {@code /}), no {@code .} or {@code ..} segment, and none of {@code { } * ? % \} and no
     * control character (U+0000–U+001F, U+007F). {@code under("/")} matches every path the
     * router accepts.
     *
     * @param prefix the prefix, such as {@code "/admin"}
     * @return the matcher
     * @throws NullPointerException     if {@code prefix} is {@code null}
     * @throws IllegalArgumentException if {@code prefix} breaks the rules above
     */
    static RequestMatcher under(String prefix) {
        var segments = Matchers.segments(prefix, "prefix");
        return new Matchers.Path(segments, false, prefix);
    }

    /**
     * Every request whose decoded path is exactly {@code path}: as many segments, all equal.
     * The rules for {@code path} are those of {@link #under}; {@code exactly("/")} matches only
     * the root. Like the router, it is strict about a trailing slash: {@code exactly("/admin")}
     * does not match {@code /admin/} (use {@link #under} for both).
     *
     * @param path the path, such as {@code "/health"}
     * @return the matcher
     * @throws NullPointerException     if {@code path} is {@code null}
     * @throws IllegalArgumentException if {@code path} breaks the rules of {@link #under}
     */
    static RequestMatcher exactly(String path) {
        var segments = Matchers.segments(path, "path");
        return new Matchers.Path(segments, true, path);
    }

    /**
     * Requests with exactly this method (case-sensitive, like the router) that {@code inner}
     * matches. {@code method("GET", ...)} also matches {@code HEAD}; see the class
     * documentation.
     *
     * @param method the method, a token (RFC 9110 §5.6.2) such as {@code "POST"}
     * @param inner  the matcher for the rest, such as {@code under("/admin")}
     * @return the matcher
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code method} is not a token, or is a standard method
     *                                  in the wrong case (such as {@code "get"}), which would never
     *                                  match and let requests fall through to later rules
     */
    static RequestMatcher method(String method, RequestMatcher inner) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(inner, "inner");
        if (!Matchers.isToken(method)) {
            throw new IllegalArgumentException("method " + Text.quote(method) + " is not a token (RFC 9110 5.6.2)");
        }
        var upper = method.toUpperCase(Locale.ROOT);
        if (!upper.equals(method) && Matchers.STANDARD_METHODS.contains(upper)) {
            throw new IllegalArgumentException("method " + Text.quote(method)
                    + " never matches: methods are case-sensitive, use " + Text.quote(upper));
        }
        return new Matchers.Method(method, inner);
    }

    /**
     * Every request. A rule with this matcher must be the last one:
     * {@link WebSecurity.Builder#build()} refuses rules after it (they could never match).
     *
     * @return the matcher, always the same instance
     */
    static RequestMatcher anyRequest() {
        return Matchers.ANY;
    }
}
