package spola.security.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import spola.http.Response;
import spola.web.ResponseCookie;
import spola.web.Responses;
import spola.web.WebRequest;

/**
 * CSRF protection for a single-page application with Angular's standard pattern, the
 * counterpart of Spring Security's
 * {@code csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
 * .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))} together with the
 * {@code CsrfCookieFilter} that sets the cookie on the first response. Immutable and
 * thread-safe; turn it on with {@link WebSecurity.Builder#csrf(Csrf)}:
 *
 * <pre>{@code
 * WebSecurity security = WebSecurity.builder()
 *         .sessions(sessions)
 *         .csrf(Csrf.cookie())                           // XSRF-TOKEN / X-XSRF-TOKEN, 403 on a mismatch
 *         .rule(RequestMatcher.exactly("/api/auth/login"), AccessRule.permitAll())
 *         .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
 *         .build();
 * }</pre>
 *
 * <h2>The pattern: a double-submit cookie</h2>
 * The server sets the cookie {@value #COOKIE_NAME} with a random token. It is <b>not</b>
 * {@code HttpOnly}, so the application's JavaScript can read it, and Angular's
 * {@code HttpClient} (its default, {@code withXsrfConfiguration({ cookieName: 'XSRF-TOKEN',
 * headerName: 'X-XSRF-TOKEN' })}) copies it into the header {@value #HEADER_NAME} of every
 * request that may change something. The security filter compares the header with the cookie of
 * the same request. A page from another origin can make the browser send the cookie along, but
 * it cannot read the cookie, and it cannot put a custom header on a request to another origin
 * without a CORS preflight that the server allows; so a forged request has no matching header.
 *
 * <p><b>The server keeps no tokens</b>, not in memory and not in the session (as Spring's
 * {@code CookieCsrfTokenRepository}): the request carries both copies, and nothing on the server
 * has to be stored, expired, shared between servers or cleaned up. The protection works with
 * and without {@link WebSecurity.Builder#sessions sessions}, also with only {@link HttpBasic}.
 *
 * <p><b>What it rests on.</b> It protects against requests that another origin makes the
 * browser send. It does <b>not</b> protect against script that runs on the application's own
 * pages (XSS): such script can read the cookie and send the header. Nor does it protect against
 * an origin that CORS lets send the header with credentials: an origin on the same site (a
 * subdomain) can also set the cookie for the parent domain, and with the header allowed it can
 * then send a matching pair. Without CORS for other origins, a cookie such an origin plants
 * gives it nothing, because it still cannot send the header.
 *
 * <h2>The methods that are not checked</h2>
 * {@code GET}, {@code HEAD}, {@code OPTIONS} and {@code TRACE} are not checked: they must not
 * change anything (RFC 9110 §9.2.1, as Spring's {@code CsrfFilter}). The method is compared
 * exactly, as the router does, so {@code get} in lower case <b>is</b> checked, and so is every
 * other method ({@code POST}, {@code PUT}, {@code PATCH}, {@code DELETE}, {@code PURGE}, ...).
 *
 * <h2>The check</h2>
 * A checked request needs <b>exactly one</b> {@value #HEADER_NAME} field whose value is a
 * well-formed token, equal to one of the well-formed {@value #COOKIE_NAME} values among the
 * request's cookies (a browser can send two when an old cookie with another {@code Path} or
 * {@code Domain} is left). A token is well-formed when it is exactly 43 characters from
 * {@code [A-Za-z0-9_-]}; a header or cookie value of another form (quoted, padded, standard
 * Base64 with {@code +} or {@code /}, another length) counts as missing. The values are compared
 * in constant time ({@link MessageDigest#isEqual}). Otherwise the answer is <b>403</b>, the same
 * response as a denied access rule ({@code Problem.of(403)}, no {@code detail},
 * {@code Cache-Control: no-store}), and neither the handler nor any filter inside the security
 * filter runs. The check applies to every path: {@link AccessRule#permitAll() permitAll} paths,
 * the login and the logout, and requests with Basic credentials.
 *
 * <h2>Where in the filter</h2>
 * The security filter runs, in order: the session filter (with sessions), the 400 for more than
 * one {@code Authorization} field, <b>this check</b>, the identity (the session, then the
 * mechanisms) and the rules. A forged request therefore never reaches an {@code Authenticator}
 * (no PBKDF2 work) and gets 403 also when it is anonymous on a protected path, as in Spring,
 * where {@code CsrfFilter} runs before the authentication filters.
 *
 * <h2>When the cookie is set and replaced</h2>
 * <ul>
 *   <li><b>On the first response.</b> A request without a well-formed {@value #COOKIE_NAME}
 *       cookie gets a {@code Set-Cookie} with a new token on whatever response leaves the
 *       security filter: the handler's, 401, 403 (also the check's own), 404 and mapped errors.
 *       Angular has the cookie before its first {@code POST}, without a filter of its own (it
 *       replaces Spring's {@code CsrfCookieFilter}). A request that has a well-formed cookie gets
 *       no new one, so the token does not change on every request.</li>
 *   <li><b>At login and logout.</b> {@link WebSecurity#login} and {@link WebSecurity#logout} give
 *       the response a new token (Spring's {@code CsrfAuthenticationStrategy} and
 *       {@code CsrfLogoutHandler}). The browser replaces its cookie, so the old token no longer
 *       matches anything and stops working.</li>
 *   <li>A response gets at most one {@code Set-Cookie} for {@value #COOKIE_NAME} from the
 *       security filter; the cookie belongs to it, so handlers and other filters do not set it.
 *       A response that sets it must never be kept by a shared cache: it gets
 *       {@code Cache-Control: no-store} unless the handler's {@code Cache-Control} already keeps
 *       shared caches out ({@code no-store}, or {@code private} without a field list, and neither
 *       {@code public} nor {@code s-maxage}); any other value, {@code public, max-age=60},
 *       {@code s-maxage=60} and {@code max-age=60} among them, is replaced by
 *       {@code Cache-Control: no-store}.</li>
 *   <li>An exception that the router maps outside the security filter (the 400 for several
 *       {@code Authorization} fields or more than 200 cookies, an exception from a mechanism, a
 *       matcher or a rule) and a request body failure get no cookie.</li>
 * </ul>
 *
 * <h2>The cookie</h2>
 * A token is 32 bytes from a {@link SecureRandom} (one {@code new SecureRandom()} per
 * {@code WebSecurity}, not {@code getInstanceStrong()}, which can block), encoded as Base64url
 * without padding: 43 characters. The cookie is
 * {@code ResponseCookie.of("XSRF-TOKEN", token).httpOnly(false)} changed by the
 * {@link #withCookie} function; by default {@code XSRF-TOKEN=<token>; Path=/; SameSite=Lax},
 * without {@code HttpOnly}. The names are fixed (Angular's and Spring's defaults); there is no
 * form parameter ({@code _csrf}) and no masking of the token.
 *
 * <h2>{@code Secure}</h2>
 * As Spring's {@code CookieCsrfTokenRepository} sets {@code secure} from
 * {@code request.isSecure()}, the cookie gets {@code Secure} when the function sets it, <b>or</b>
 * when the request's {@link WebRequest#scheme()} is {@code https}:
 * {@code XSRF-TOKEN=<token>; Path=/; Secure; SameSite=Lax}. Nothing else in the line changes.
 * The rule applies wherever the cookie is set: on the first response, whatever its status (200,
 * 401, 403 from a rule or from the check, 404, ...), and at {@link WebSecurity#login} and
 * {@link WebSecurity#logout}.
 * <ul>
 *   <li><b>Which scheme.</b> The scheme of the request that the security filter got, decided
 *       when it starts, for each request on its own; the session cookie of
 *       {@link WebSecurity.Builder#sessions sessions(...)} follows the same request. A handler
 *       or a filter inside the security filter does not change it, so {@code login} and
 *       {@code logout} use the same scheme.</li>
 *   <li><b>Over TLS</b> the scheme is {@code https}. <b>Behind a TLS proxy</b> (Caddy, nginx) the
 *       connection is {@code http}, and the scheme is {@code https} only when
 *       {@link spola.web.ForwardedHeaders} is registered <b>before</b> {@code security.filter()}
 *       (first on the router) and trusts the proxy that sends {@code X-Forwarded-Proto: https}.
 *       Registered after the security filter, it comes too late: the cookie gets no
 *       {@code Secure} from the proxy.</li>
 *   <li><b>{@code Csrf.cookie().withCookie(c -> c.secure(true))}</b> gives {@code Secure} on
 *       every response, also over {@code http}, as before.</li>
 *   <li><b>There is no way to turn the rule off.</b> Spring can leave {@code secure} out over
 *       HTTPS with a cookie customizer ({@code c -> c.secure(false)}); here a cookie set over
 *       {@code https} always has {@code Secure}, since an application on the same origin never
 *       needs one without it. The checks in {@link WebSecurity.Builder#build()} do not depend on
 *       the rule: {@code SameSite=None} still needs {@code secure(true)} in the function.</li>
 * </ul>
 */
public final class Csrf {

    /** The cookie that carries the token: {@code XSRF-TOKEN}. */
    public static final String COOKIE_NAME = "XSRF-TOKEN";

    /** The header that must repeat the token: {@code X-XSRF-TOKEN}. */
    public static final String HEADER_NAME = "X-XSRF-TOKEN";

    /** Random bytes per token. */
    static final int TOKEN_BYTES = 32;
    /** Characters per token: 32 bytes in Base64url without padding. */
    static final int TOKEN_LENGTH = 43;
    /** A well-formed value for building the cookie with the function once; never issued. */
    private static final String PLACEHOLDER = "A".repeat(TOKEN_LENGTH);
    /** The methods that are not checked, compared exactly. */
    private static final Set<String> UNCHECKED = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private static final Csrf DEFAULT = new Csrf(UnaryOperator.identity());

    private final UnaryOperator<ResponseCookie.Builder> cookie;

    private Csrf(UnaryOperator<ResponseCookie.Builder> cookie) {
        this.cookie = cookie;
    }

    /**
     * The protection with the cookie {@value #COOKIE_NAME} and the header {@value #HEADER_NAME}:
     * the cookie {@code XSRF-TOKEN=<token>; Path=/; SameSite=Lax}.
     *
     * @return the protection, for {@link WebSecurity.Builder#csrf(Csrf)}
     */
    public static Csrf cookie() {
        return DEFAULT;
    }

    /**
     * A copy whose cookie gets its attributes from {@code cookie}, which replaces the function
     * of this one. It gets the builder of {@code ResponseCookie.of("XSRF-TOKEN", token)
     * .httpOnly(false)} and returns that builder with attributes changed:
     *
     * <pre>{@code
     * Csrf.cookie().withCookie(c -> c.secure(true))   // also over http: XSRF-TOKEN=...; Path=/; Secure; SameSite=Lax
     * }</pre>
     *
     * {@link WebSecurity.Builder#build()} applies the function once, to a stand-in token, and
     * uses the attributes it sets ({@code Path}, {@code Domain}, {@code Max-Age},
     * {@code Secure}, {@code SameSite}) for every token. Without {@code secure(true)} the cookie
     * still gets {@code Secure} when the scheme of the request is {@code https} (see
     * "{@code Secure}" in {@link Csrf}). {@code build()} fails with an
     * {@link IllegalArgumentException} if the function returns {@code null} or a builder for
     * another cookie, turns {@code HttpOnly} on (the application's JavaScript could not read the
     * cookie), sets {@code Max-Age} to zero (the browser would drop the cookie at once), or gives
     * a cookie that {@link ResponseCookie.Builder#build()} rejects (such as {@code SameSite=None}
     * without {@code Secure}). An exception the function throws passes unchanged. With
     * {@code Max-Age} the JDK's {@code CookieManager} (not a browser) sends the value back in
     * quotes, which does not count as a token.
     *
     * @param cookie gets the cookie's builder and returns it with attributes changed
     * @return the copy
     * @throws NullPointerException if {@code cookie} is {@code null}
     */
    public Csrf withCookie(UnaryOperator<ResponseCookie.Builder> cookie) {
        return new Csrf(Objects.requireNonNull(cookie, "cookie"));
    }

    /**
     * The fixed names, never a token.
     *
     * @return {@code Csrf[cookie XSRF-TOKEN, header X-XSRF-TOKEN]}
     */
    @Override
    public String toString() {
        return "Csrf[cookie " + COOKIE_NAME + ", header " + HEADER_NAME + "]";
    }

    /**
     * The protection of one {@code WebSecurity}: applies the function once and checks the cookie
     * it gives. Adds one line per problem to {@code problems} and returns {@code null} if there
     * is one.
     */
    Protection protection(List<String> problems) {
        var builder = cookie.apply(ResponseCookie.of(COOKIE_NAME, PLACEHOLDER).httpOnly(false));
        if (builder == null) {
            problems.add("csrf(...): the cookie function returned null; return the builder it gets");
            return null;
        }
        ResponseCookie template;
        try {
            template = builder.build();
        } catch (IllegalArgumentException e) {
            problems.add("csrf(...): the cookie function gives an invalid cookie: " + e.getMessage());
            return null;
        }
        int before = problems.size();
        if (!template.name().equals(COOKIE_NAME) || !template.value().equals(PLACEHOLDER)) {
            problems.add("csrf(...): the cookie function must return the builder it gets, with attributes changed (it"
                    + " returned one for the cookie " + Text.quote(template.name()) + ")");
        }
        if (template.httpOnly()) {
            problems.add("csrf(...): the cookie function turns HttpOnly on, so the application's JavaScript (Angular)"
                    + " cannot read " + COOKIE_NAME + "; leave httpOnly off");
        }
        if (template.maxAge().filter(Duration::isZero).isPresent()) {
            problems.add("csrf(...): the cookie function sets Max-Age=0, which removes the cookie at once; leave"
                    + " maxAge out for a session cookie");
        }
        return problems.size() == before ? new Protection(template) : null;
    }

    /** Whether {@code value} has the form of a token: 43 characters from {@code [A-Za-z0-9_-]}. */
    static boolean isWellFormed(String value) {
        if (value.length() != TOKEN_LENGTH) {
            return false;
        }
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            char c = value.charAt(i);
            boolean base64url = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-'
                    || c == '_';
            if (!base64url) {
                return false;
            }
        }
        return true;
    }

    /**
     * The CSRF protection of one {@code WebSecurity}: the cookie's attributes and the random
     * source. Thread-safe.
     */
    static final class Protection {

        /** The cookie as the function made it, with {@link #PLACEHOLDER} as value. */
        private final ResponseCookie template;
        private final SecureRandom random = new SecureRandom();

        private Protection(ResponseCookie template) {
            this.template = template;
        }

        /**
         * Checks {@code request} and notes whether it has a well-formed cookie and whether its
         * scheme is {@code https} (then a new cookie gets {@code Secure}).
         *
         * @throws spola.web.WebException 400 if the request has more than 200 cookies
         */
        Exchange begin(WebRequest request) {
            var cookies = request.cookieAll(COOKIE_NAME);
            boolean hasCookie = false;
            for (var value : cookies) {
                hasCookie |= isWellFormed(value);
            }
            return new Exchange(this, hasCookie, UNCHECKED.contains(request.method()) || matches(request, cookies),
                    request.scheme().equals("https"));
        }

        /**
         * Whether the request has exactly one well-formed header equal to one of the well-formed
         * {@code cookies}, compared in constant time; every cookie is compared.
         */
        private static boolean matches(WebRequest request, List<String> cookies) {
            var fields = request.headers().all(HEADER_NAME);
            if (fields.size() != 1 || !isWellFormed(fields.getFirst())) {
                return false;
            }
            var header = fields.getFirst().getBytes(StandardCharsets.US_ASCII);
            boolean match = false;
            for (var value : cookies) {
                if (isWellFormed(value)) {
                    match |= MessageDigest.isEqual(header, value.getBytes(StandardCharsets.US_ASCII));
                }
            }
            return match;
        }

        /** A cookie with a new token and the template's attributes, and {@code Secure} also when {@code https}. */
        ResponseCookie newCookie(boolean https) {
            var builder = ResponseCookie.of(COOKIE_NAME, newToken()).path(template.path())
                    .secure(template.secure() || https).httpOnly(false).sameSite(template.sameSite());
            if (template.domain().isPresent()) {
                builder = builder.domain(template.domain().get());
            }
            if (template.maxAge().isPresent()) {
                builder = builder.maxAge(template.maxAge().get());
            }
            return builder.build();
        }

        /** A new token: 32 bytes from this protection's {@link SecureRandom}, Base64url without padding. */
        private String newToken() {
            var bytes = new byte[TOKEN_BYTES];
            random.nextBytes(bytes);
            var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            Arrays.fill(bytes, (byte) 0);
            return token;
        }

        /** Never a token. */
        @Override
        public String toString() {
            return "Csrf.Protection";
        }
    }

    /**
     * The CSRF state of one request, which the security filter puts on the request as an
     * attribute under a key only {@link WebSecurity} has: whether the request passed the check,
     * whether it had a well-formed cookie, whether the request the security filter got was
     * {@code https}, and whether {@code login} or {@code logout} asked for a new token. Holds no
     * token. Synchronized, since a {@link WebRequest} may be handed to other threads.
     */
    static final class Exchange {

        private final Protection protection;
        private final boolean hasCookie;
        private final boolean allowed;
        /** Whether the request the security filter got is {@code https}: then a new cookie gets {@code Secure}. */
        private final boolean https;
        private boolean renew;
        private boolean finished;

        private Exchange(Protection protection, boolean hasCookie, boolean allowed, boolean https) {
            this.protection = protection;
            this.hasCookie = hasCookie;
            this.allowed = allowed;
            this.https = https;
        }

        /** Whether the request passed the check: an unchecked method or a matching header. */
        boolean allowed() {
            return allowed;
        }

        /**
         * The response gets a new token ({@code login} and {@code logout}).
         *
         * @throws IllegalStateException if the response was already built
         */
        synchronized void renew(String method) {
            if (finished) {
                throw new IllegalStateException("WebSecurity." + method + " was called after the response of the"
                        + " request was built; call it while the request is handled");
            }
            renew = true;
        }

        /**
         * {@code response} with a new token (with {@code Secure} also when the request was
         * {@code https}) if the request had no well-formed cookie or a new one was asked for, and
         * then never storable by a shared cache ({@link WebSecurity.CacheControl#withoutSharedCache}).
         */
        synchronized Response finish(Response response) {
            finished = true;
            if (hasCookie && !renew) {
                return response;
            }
            return WebSecurity.CacheControl.withoutSharedCache(Responses.withCookie(response, protection.newCookie(https)));
        }

        /** The rest of the chain threw: no response will carry a token. */
        synchronized void abandon() {
            finished = true;
        }

        /** Never a token. */
        @Override
        public String toString() {
            return "Csrf.Exchange";
        }
    }
}
