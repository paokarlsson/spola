package spola.web;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * The settings of {@link Sessions#inMemory(SessionOptions)}, the counterpart of Spring Boot's
 * {@code server.servlet.session.*} properties. Start from {@link #DEFAULT} and change what you
 * need:
 *
 * <pre>{@code
 * var options = SessionOptions.DEFAULT
 *         .withIdleTimeout(Duration.ofMinutes(30))     // server.servlet.session.timeout=30m
 *         .withCookie(cookie -> cookie.secure(true));  // server.servlet.session.cookie.secure=true
 * }</pre>
 *
 * <p>Without {@code secure(true)} the cookie is still {@code Secure} when the request's scheme
 * is {@code https}, as with Tomcat; {@code secure(true)} makes it {@code Secure} over
 * {@code http} too. For an application reached only over https the production setting is
 *
 * <pre>{@code
 * SessionOptions.DEFAULT
 *         .withCookieName("__Host-SESSION")             // only this host, only over https, Path=/
 *         .withCookie(cookie -> cookie.secure(true));   // Secure whatever the scheme
 * }</pre>
 *
 * so that {@code Secure} does not depend on {@link ForwardedHeaders} trusting the proxy's
 * address, and a sibling subdomain cannot plant a session cookie of its own (cookie tossing:
 * {@code Domain=<parent>; Path=/api} would be sent first, and the first live value is used).
 *
 * <p>The canonical constructor, and so every {@code withX} method, checks the values and
 * reports every problem at once: one {@link IllegalArgumentException} with one line per
 * problem. The {@link #cookie} function is checked by {@link Sessions#inMemory(SessionOptions)},
 * which builds the cookie with it once.
 *
 * @param idleTimeout how long a session lives after it was last used: a session has expired
 *                    when {@code now >= last use + idleTimeout}. At least 1 second and at most
 *                    365 days. Default 30 minutes, as in Spring Boot.
 * @param maxSessions the most sessions in memory at once, 1 to 10 000 000. When the limit is
 *                    reached (after removing expired sessions), creating a session gives 503;
 *                    existing sessions are never removed to make room. Default 10 000.
 * @param cookieName  the name of the session cookie, a {@code token} (RFC 9110 §5.6.2).
 *                    Default {@code SESSION}, as in Spring Session. In production, for an
 *                    application reached only over https, {@code __Host-SESSION} with
 *                    {@code secure(true)} is recommended: the browser then keeps the cookie
 *                    only for this host, over HTTPS, with {@code Path=/}, so another subdomain
 *                    can neither set nor shadow it.
 * @param cookie      changes the attributes of the session cookie: it gets the builder of
 *                    {@code ResponseCookie.of(cookieName, id)} (defaults {@code Path=/},
 *                    {@code HttpOnly}, {@code SameSite=Lax}, not {@code Secure}, no
 *                    {@code Domain}, no {@code Max-Age}, so a session cookie) and returns that
 *                    builder with attributes changed, such as {@code cookie -> cookie.secure(true)}.
 *                    Default: the builder unchanged.
 *                    {@link Sessions#inMemory(SessionOptions)} applies it once, to a stand-in
 *                    id, and uses the attributes it sets for every session cookie and for the
 *                    removal. The cookie and the removal get {@code Secure} when this function
 *                    sets it (then on every response, also over {@code http}) <b>or</b> when the
 *                    scheme of the request that the session filter got is {@code https}: over
 *                    TLS, or behind a TLS proxy when {@link ForwardedHeaders} is registered
 *                    before {@code sessions.filter()} (or before the security filter that runs
 *                    it). Registered after it, the cookie gets no {@code Secure} from the proxy.
 *                    The rule cannot be turned off. See "{@code Secure}" in {@link Sessions}.
 */
public record SessionOptions(Duration idleTimeout, int maxSessions, String cookieName,
                             UnaryOperator<ResponseCookie.Builder> cookie) {

    /** The shortest idle timeout. */
    static final Duration MIN_IDLE_TIMEOUT = Duration.ofSeconds(1);
    /** The longest idle timeout. */
    static final Duration MAX_IDLE_TIMEOUT = Duration.ofDays(365);
    /** The highest limit on sessions. */
    static final int MAX_MAX_SESSIONS = 10_000_000;

    /**
     * The defaults: 30 minutes idle timeout, at most 10 000 sessions, the cookie
     * {@code SESSION} with {@code Path=/}, {@code HttpOnly} and {@code SameSite=Lax}.
     */
    public static final SessionOptions DEFAULT = new SessionOptions(Duration.ofMinutes(30), 10_000, "SESSION",
            UnaryOperator.identity());

    /**
     * Checks the values.
     *
     * @throws NullPointerException     if a component is {@code null}
     * @throws IllegalArgumentException if {@code idleTimeout} is under 1 second or over 365 days,
     *                                  {@code maxSessions} is outside 1–10 000 000 or
     *                                  {@code cookieName} is not a token; the message has one
     *                                  line per problem
     */
    public SessionOptions {
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        Objects.requireNonNull(cookieName, "cookieName");
        Objects.requireNonNull(cookie, "cookie");
        var problems = new ArrayList<String>();
        if (idleTimeout.compareTo(MIN_IDLE_TIMEOUT) < 0 || idleTimeout.compareTo(MAX_IDLE_TIMEOUT) > 0) {
            problems.add("idleTimeout must be at least 1 second and at most 365 days, was " + idleTimeout);
        }
        if (maxSessions < 1 || maxSessions > MAX_MAX_SESSIONS) {
            problems.add("maxSessions must be between 1 and " + MAX_MAX_SESSIONS + ", was " + maxSessions);
        }
        if (!RouterBuilder.isToken(cookieName)) {
            problems.add("cookieName must be a token (RFC 9110 5.6.2), was " + Text.quote(cookieName));
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", problems));
        }
    }

    /**
     * A copy with another idle timeout ({@code server.servlet.session.timeout}).
     *
     * @param idleTimeout at least 1 second, at most 365 days
     * @return the copy
     */
    public SessionOptions withIdleTimeout(Duration idleTimeout) {
        return new SessionOptions(idleTimeout, maxSessions, cookieName, cookie);
    }

    /**
     * A copy with another limit on sessions in memory.
     *
     * @param maxSessions 1 to 10 000 000
     * @return the copy
     */
    public SessionOptions withMaxSessions(int maxSessions) {
        return new SessionOptions(idleTimeout, maxSessions, cookieName, cookie);
    }

    /**
     * A copy with another cookie name ({@code server.servlet.session.cookie.name}).
     *
     * @param cookieName a token, such as {@code __Host-SESSION}
     * @return the copy
     */
    public SessionOptions withCookieName(String cookieName) {
        return new SessionOptions(idleTimeout, maxSessions, cookieName, cookie);
    }

    /**
     * A copy with another function for the cookie's attributes
     * ({@code server.servlet.session.cookie.*}).
     *
     * <pre>{@code
     * SessionOptions.DEFAULT.withCookie(cookie -> cookie.secure(true).sameSite(ResponseCookie.SameSite.STRICT))
     * }</pre>
     *
     * @param cookie gets the session cookie's builder and returns it with attributes changed
     * @return the copy
     */
    public SessionOptions withCookie(UnaryOperator<ResponseCookie.Builder> cookie) {
        return new SessionOptions(idleTimeout, maxSessions, cookieName, cookie);
    }
}
