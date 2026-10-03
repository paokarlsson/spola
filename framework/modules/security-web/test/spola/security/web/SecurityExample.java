package spola.security.web;

import java.util.Set;
import spola.http.Response;
import spola.security.Authenticator;
import spola.security.InMemoryUserStore;
import spola.security.PasswordHasher;
import spola.security.User;
import spola.web.Router;

/** The README example: HTTP Basic, access rules, a protected group and the identity in a handler. */
public final class SecurityExample {

    private SecurityExample() {
    }

    /**
     * Two users in memory.
     *
     * @param hasher {@code Pbkdf2PasswordHasher.defaults()} in production
     * @return the authenticator
     */
    public static Authenticator authenticator(PasswordHasher hasher) {
        var users = new InMemoryUserStore();
        users.add(new User("ada", hasher.hash("correct horse battery staple"), Set.of("USER", "ADMIN"), true));
        users.add(new User("bob", hasher.hash("hunter2hunter2"), Set.of("USER"), true));
        return Authenticator.of(users, hasher);
    }

    /**
     * The rules: {@code /public} for everyone, {@code POST} below {@code /admin} for ADMIN,
     * everything else for any logged-in user.
     *
     * @param authenticator checks usernames and passwords
     * @return the security
     */
    public static WebSecurity security(Authenticator authenticator) {
        return WebSecurity.builder()
                .authentication(HttpBasic.of(authenticator, "app"))
                .rule(RequestMatcher.under("/public"), AccessRule.permitAll())
                .rule(RequestMatcher.method("POST", RequestMatcher.under("/admin")), AccessRule.hasRole("ADMIN"))
                .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
                .build();
    }

    /**
     * The routes, with the security filter first and the admin group protected as a whole.
     *
     * @param security the security
     * @return the router
     */
    public static Router router(WebSecurity security) {
        return Router.builder()
                .filter(security.filter())
                .get("/public/hello", request -> Response.text(200, "hello"))
                .get("/me", request -> Response.text(200, "you are " + WebSecurity.requireIdentity(request).name()))
                .group("/admin", admin -> admin
                        .filter(security.require(AccessRule.hasRole("ADMIN")))
                        .get("/users", request -> Response.text(200, "ada, bob"))
                        .post("/users", request -> Response.text(201, "created")))
                .build();
    }
}
