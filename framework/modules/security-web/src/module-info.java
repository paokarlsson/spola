/**
 * Spola's security, part 2: the bridge between {@link spola.security} and {@link spola.web}. It
 * replaces the core of Spring Security's web part: the {@code SecurityFilterChain} with
 * {@code authorizeHttpRequests} ({@code requestMatchers(...).hasRole(...)}, {@code permitAll()},
 * {@code authenticated()}, {@code denyAll()}, {@code anyRequest()}), {@code httpBasic()}, the
 * logged-in user per request ({@code SecurityContext}, {@code @AuthenticationPrincipal}),
 * {@code @PreAuthorize("hasRole('ADMIN')")} as a filter on a group, the 401
 * ({@code AuthenticationEntryPoint}, also {@code HttpStatusEntryPoint}) and 403
 * ({@code AccessDeniedHandler}) responses, a login kept in the HTTP session
 * ({@code HttpSessionSecurityContextRepository}, {@code changeSessionId()} at login,
 * {@code invalidate()} at logout), and CSRF protection for a single-page application
 * ({@code csrf()} with {@code CookieCsrfTokenRepository.withHttpOnlyFalse()},
 * {@code CsrfTokenRequestAttributeHandler} and a filter that sets the cookie on the first
 * response).
 *
 * <ul>
 *   <li>{@link spola.security.web.WebSecurity}: built with
 *       {@link spola.security.web.WebSecurity#builder()}; its
 *       {@link spola.security.web.WebSecurity#filter() filter()} must be registered on the
 *       router <b>first</b>, so that it is the outermost filter and sees every request
 *       (also the router's 400, 404, 405 and automatic {@code OPTIONS}); behind a reverse
 *       proxy right after {@link spola.web.ForwardedHeaders}, so that it sees the client's
 *       scheme and address.</li>
 *   <li>{@link spola.security.web.RequestAuthentication},
 *       {@link spola.security.web.AuthOutcome} and {@link spola.security.web.HttpBasic}
 *       (RFC 7617): how a request is authenticated.</li>
 *   <li>{@link spola.security.web.RequestMatcher} and {@link spola.security.web.AccessRule}:
 *       which requests are allowed; the first matching rule decides and a request no rule
 *       matches is denied.</li>
 *   <li>{@link spola.security.web.SecurityContext}: the identity of the request, read with
 *       {@link spola.security.web.WebSecurity#identity} and
 *       {@link spola.security.web.WebSecurity#requireIdentity}.</li>
 *   <li>Sessions: {@link spola.security.web.WebSecurity.Builder#sessions} keeps the login in
 *       the {@link spola.web.Sessions} of {@code spola.web}. The security filter then runs the
 *       session filter itself, outermost (register only the security filter), and takes the
 *       identity from the request's session before any mechanism.
 *       {@link spola.security.web.WebSecurity#login} gives the session a new id (protection
 *       against session fixation) and stores the identity in it, a snapshot; a session that
 *       holds another user's identity is replaced by a new, empty one, and a user has at most
 *       {@link spola.security.web.WebSecurity.Builder#maximumSessions} sessions (10 unless set;
 *       the least recently used end). {@link spola.security.web.WebSecurity#logout} removes the
 *       session. Without {@code sessions(...)} both throw
 *       {@link java.lang.IllegalStateException} (500).</li>
 *   <li>Caches: a response to a logged-in request gets {@code Cache-Control: no-store} unless
 *       the handler set one without {@code public} and {@code s-maxage}, and a response that
 *       sets the session cookie or {@code XSRF-TOKEN} is never storable by a shared cache.</li>
 *   <li>CSRF protection: {@link spola.security.web.WebSecurity.Builder#csrf} with
 *       {@link spola.security.web.Csrf#cookie()}, Angular's double-submit cookie. The response
 *       sets the cookie {@code XSRF-TOKEN} (not {@code HttpOnly}) on the first response and
 *       with a new token at login and logout, and a request with a method other than
 *       {@code GET}, {@code HEAD}, {@code OPTIONS} and {@code TRACE} must repeat it in the
 *       header {@code X-XSRF-TOKEN}, or it gets 403 before any authentication. The server
 *       keeps no tokens.</li>
 *   <li>{@code Secure}: the session cookie and {@code XSRF-TOKEN} get {@code Secure} when their
 *       cookie function sets it or when the scheme of the request the security filter got is
 *       {@code https} (over TLS, or behind a TLS proxy that {@code ForwardedHeaders}, registered
 *       before the security filter, trusts), as Tomcat and Spring Security do with
 *       {@code request.isSecure()}. An application reached only over https sets
 *       {@code secure(true)} on both, with the session cookie named
 *       {@code __Host-SESSION}.</li>
 * </ul>
 *
 * <p>{@code spola.web} and {@code spola.security} are required transitively because both are
 * part of this module's API ({@code Filter}, {@code WebRequest}, {@code Sessions},
 * {@code Authenticator}, {@code Identity}). Neither of them depends on this module or on each
 * other: hashing and authentication work without HTTP, and the web layer works without
 * security.
 * {@code requires static java.net.http} is for the module's tests only (they talk to a real
 * server with {@link java.net.http.HttpClient}); no main code uses it. The module writes no log
 * lines, uses no reflection and no {@code ThreadLocal}. This code is security critical; have a
 * human review it before relying on it.
 */
module spola.security.web {
    requires transitive spola.web;
    requires transitive spola.security;
    requires static java.net.http;

    exports spola.security.web;
}
