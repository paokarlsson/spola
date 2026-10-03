/**
 * Spola's security, part 2: the bridge between {@link spola.security} and {@link spola.web}. It
 * replaces the core of Spring Security's web part: the {@code SecurityFilterChain} with
 * {@code authorizeHttpRequests} ({@code requestMatchers(...).hasRole(...)}, {@code permitAll()},
 * {@code authenticated()}, {@code denyAll()}, {@code anyRequest()}), {@code httpBasic()}, the
 * logged-in user per request ({@code SecurityContext}, {@code @AuthenticationPrincipal}),
 * {@code @PreAuthorize("hasRole('ADMIN')")} as a filter on a group, and the 401
 * ({@code AuthenticationEntryPoint}) and 403 ({@code AccessDeniedHandler}) responses.
 *
 * <ul>
 *   <li>{@link spola.security.web.WebSecurity}: built with
 *       {@link spola.security.web.WebSecurity#builder()}; its
 *       {@link spola.security.web.WebSecurity#filter() filter()} must be registered on the
 *       router <b>first</b>, so that it is the outermost filter and sees every request
 *       (also the router's 400, 404, 405 and automatic {@code OPTIONS}).</li>
 *   <li>{@link spola.security.web.RequestAuthentication},
 *       {@link spola.security.web.AuthOutcome} and {@link spola.security.web.HttpBasic}
 *       (RFC 7617): how a request is authenticated.</li>
 *   <li>{@link spola.security.web.RequestMatcher} and {@link spola.security.web.AccessRule}:
 *       which requests are allowed; the first matching rule decides and a request no rule
 *       matches is denied.</li>
 *   <li>{@link spola.security.web.SecurityContext}: the identity of the request, read with
 *       {@link spola.security.web.WebSecurity#identity} and
 *       {@link spola.security.web.WebSecurity#requireIdentity}.</li>
 * </ul>
 *
 * <p>{@code spola.web} and {@code spola.security} are required transitively because both are
 * part of this module's API ({@code Filter}, {@code WebRequest}, {@code Authenticator},
 * {@code Identity}). Neither of them depends on this module or on each other: hashing and
 * authentication work without HTTP, and the web layer works without security.
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
