package spola.security.web;

import spola.web.WebRequest;

/**
 * One way to authenticate a request, such as {@link HttpBasic}. Register mechanisms with
 * {@link WebSecurity.Builder#authentication}; they are tried in registration order and the
 * first one that does not answer {@link AuthOutcome.None} decides. A lambda is a mechanism:
 *
 * <pre>{@code
 * RequestAuthentication apiKey = request -> request.headers().first("X-Api-Key")
 *         .map(key -> keys.find(key).<AuthOutcome>map(AuthOutcome.Success::new).orElse(new AuthOutcome.Rejected()))
 *         .orElse(new AuthOutcome.None());
 * }</pre>
 *
 * <p>An exception from a mechanism is not an authentication failure: it goes to the router's
 * exception mappers (500 without one), and the request never reaches a handler.
 */
@FunctionalInterface
public interface RequestAuthentication {

    /**
     * Authenticates one request.
     *
     * @param request the request, before any rule has been applied
     * @return the outcome, never {@code null} (a {@code null} gives 500)
     */
    AuthOutcome authenticate(WebRequest request);
}
