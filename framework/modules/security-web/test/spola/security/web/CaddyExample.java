package spola.security.web;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.time.InstantSource;
import spola.http.HttpServer;
import spola.http.HttpServerOptions;
import spola.security.Authenticator;
import spola.security.Pbkdf2PasswordHasher;
import spola.security.UserStore;
import spola.web.ForwardedHeaders;
import spola.web.Router;
import spola.web.SessionOptions;
import spola.web.Sessions;
import spola.web.TrustedProxies;

/** The README part "Bakom Caddy": {@link CsrfExample} in production, reached only over https through Caddy. */
public final class CaddyExample {

    private CaddyExample() {
    }

    /**
     * The sessions for an application reached only over https: {@code __Host-SESSION} with
     * {@code Secure} on every response, also when the scheme is {@code http}.
     *
     * @param clock {@code InstantSource.system()} in production; the tests move their own
     * @return the sessions
     */
    public static Sessions sessions(InstantSource clock) {
        return Sessions.inMemory(SessionOptions.DEFAULT
                .withIdleTimeout(Duration.ofMinutes(30))
                .withCookieName("__Host-SESSION")                   // only this host, only over https
                .withCookie(cookie -> cookie.secure(true)), clock); // Secure whatever the proxy's address
    }

    /**
     * The rules of {@link CsrfExample#security}, with {@code XSRF-TOKEN} {@code Secure} on every
     * response.
     *
     * @param sessions the sessions that keep the login
     * @return the security
     */
    public static WebSecurity security(Sessions sessions) {
        return WebSecurity.builder()
                .sessions(sessions)
                .csrf(Csrf.cookie().withCookie(cookie -> cookie.secure(true))) // Secure whatever the proxy's address
                .rule(RequestMatcher.exactly("/api/auth/login"), AccessRule.permitAll())
                .rule(RequestMatcher.exactly("/api/auth/logout"), AccessRule.permitAll())
                .rule(RequestMatcher.exactly("/actuator/health"), AccessRule.permitAll())
                .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
                .build();
    }

    /**
     * The routes of {@link CsrfExample#router}, behind {@code ForwardedHeaders}.
     *
     * @param proxies       {@code TrustedProxies.of("172.18.0.5")}: only Caddy, at its fixed address
     * @param sessions      the sessions that keep the login
     * @param authenticator checks usernames and passwords
     * @return the router
     */
    public static Router router(TrustedProxies proxies, Sessions sessions, Authenticator authenticator) {
        return CsrfExample.router(
                Router.builder().filter(ForwardedHeaders.trusting(proxies)), // first
                security(sessions), authenticator);
    }

    /**
     * Port 8080 on {@code 0.0.0.0}, every IPv4 address of the container, so that Caddy in another
     * container can connect; {@code HttpServerOptions.DEFAULT} listens on loopback only, which
     * nothing outside the application's own container reaches.
     *
     * @return the options
     */
    public static HttpServerOptions options() {
        return HttpServerOptions.DEFAULT.withAddress(InetAddress.ofLiteral("0.0.0.0")).withPort(8080);
    }

    /**
     * Starts the application in its container, behind Caddy at {@code 172.18.0.5}.
     *
     * @param users the application's users
     * @return the server
     * @throws IOException if the port cannot be bound
     */
    public static HttpServer start(UserStore users) throws IOException {
        var router = router(TrustedProxies.of("172.18.0.5"), sessions(InstantSource.system()),
                Authenticator.of(users, Pbkdf2PasswordHasher.defaults()));
        // on every response, also over http and from a sender that ForwardedHeaders does not trust:
        //   Set-Cookie: XSRF-TOKEN=<token>; Path=/; Secure; SameSite=Lax
        //   Set-Cookie: __Host-SESSION=<id>; Path=/; Secure; HttpOnly; SameSite=Lax
        //   Set-Cookie: __Host-SESSION=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax   (logout)
        return HttpServer.start(options(), router);
    }
}
