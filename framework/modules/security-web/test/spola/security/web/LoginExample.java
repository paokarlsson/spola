package spola.security.web;

import static spola.json.codec.JsonCodec.field;
import static spola.json.codec.JsonCodecs.STRING;

import java.time.Duration;
import java.time.InstantSource;
import spola.json.JsonBoolean;
import spola.json.JsonObject;
import spola.json.JsonString;
import spola.json.codec.JsonCodec;
import spola.security.AuthResult;
import spola.security.Authenticator;
import spola.security.Identity;
import spola.web.Responses;
import spola.web.Router;
import spola.web.RouterBuilder;
import spola.web.SessionOptions;
import spola.web.Sessions;

/** The README example "Inloggning med session": a JSON API whose login is kept in a session cookie. */
public final class LoginExample {

    private LoginExample() {
    }

    /**
     * The body of {@code POST /api/auth/login}.
     *
     * @param username the username
     * @param password the password
     */
    public record Login(String username, String password) {

        /**
         * Without the password, so that it never ends up in a log line.
         *
         * @return {@code Login[username=...]}
         */
        @Override
        public String toString() {
            return "Login[username=" + username + "]";
        }
    }

    /** {@code {"username":"...","password":"..."}}, mapped explicitly (no reflection). */
    public static final JsonCodec<Login> LOGIN = JsonCodec.object(Login::new,
            field("username", STRING, Login::username),
            field("password", STRING, Login::password));

    /**
     * The sessions: 30 minutes idle, the cookie {@code SESSION} with {@code Path=/},
     * {@code HttpOnly} and {@code SameSite=Lax}.
     *
     * @param secure {@code config.booleanValue("cookie.secure")}: {@code true} sets {@code Secure}
     *               on every response, also over {@code http}; with {@code false} the cookie is
     *               {@code Secure} when the request's scheme is {@code https} (over TLS, or behind
     *               a TLS proxy with {@code ForwardedHeaders} registered first)
     * @param clock  {@code InstantSource.system()} in production; the tests move their own
     * @return the sessions
     */
    public static Sessions sessions(boolean secure, InstantSource clock) {
        return Sessions.inMemory(SessionOptions.DEFAULT
                .withIdleTimeout(Duration.ofMinutes(30))
                .withCookie(cookie -> cookie.secure(secure)), clock); // HttpOnly, SameSite=Lax
    }

    /**
     * The rules: login, logout and the health check are open, everything else needs a login.
     *
     * @param sessions the sessions that keep the login
     * @return the security
     */
    public static WebSecurity security(Sessions sessions) {
        return WebSecurity.builder()
                .sessions(sessions) // the identity is read from the session
                .rule(RequestMatcher.exactly("/api/auth/login"), AccessRule.permitAll())
                .rule(RequestMatcher.exactly("/api/auth/logout"), AccessRule.permitAll())
                .rule(RequestMatcher.exactly("/actuator/health"), AccessRule.permitAll())
                .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
                .build();
    }

    /**
     * The routes, behind the security filter alone.
     *
     * @param routes        {@code Router.builder()}; the tests pass one that logs to them
     * @param security      the security
     * @param authenticator checks usernames and passwords
     * @return the router
     */
    public static Router router(RouterBuilder routes, WebSecurity security, Authenticator authenticator) {
        return routes
                .filter(security.filter()) // only this one: it runs sessions.filter() itself
                .post("/api/auth/login", request -> {
                    var body = request.readJson(LOGIN);
                    return switch (authenticator.authenticate(body.username(), body.password())) {
                        case AuthResult.Authenticated(Identity identity) -> {
                            WebSecurity.login(request, identity); // a new session id with the identity
                            yield Responses.json(request, 200, JsonObject.of("success", JsonBoolean.TRUE,
                                    "username", JsonString.of(identity.name())));
                        }
                        case AuthResult.Failed failed -> Responses.json(request, 401,
                                JsonObject.of("success", JsonBoolean.FALSE));
                    };
                })
                .post("/api/auth/logout", request -> {
                    WebSecurity.logout(request); // the session is removed, the cookie expires
                    return Responses.json(request, 200, JsonObject.of("success", JsonBoolean.TRUE));
                })
                .get("/api/auth/me", request -> Responses.json(request, 200,
                        JsonObject.of("username", JsonString.of(WebSecurity.requireIdentity(request).name()))))
                .get("/actuator/health", request -> Responses.json(request, 200,
                        JsonObject.of("status", JsonString.of("UP"))))
                .build();
    }
}
