package spola.security;

import java.util.Optional;

/**
 * Where users are looked up: the counterpart of Spring Security's {@code UserDetailsService}.
 * {@link InMemoryUserStore} is the in-memory implementation; a database-backed store is a
 * lambda or a class of the application.
 */
@FunctionalInterface
public interface UserStore {

    /**
     * Looks up a user by username, compared exactly (case-sensitive, no normalisation).
     *
     * @param username the username, 1 to 256 characters when called by {@link Authenticator}
     * @return the user, or empty if there is none; never {@code null}
     */
    Optional<User> find(String username);
}
