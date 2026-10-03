package spola.security;

import java.util.Objects;
import java.util.Set;

/**
 * A user as stored: the counterpart of Spring Security's {@code UserDetails}.
 *
 * <p>Usernames are compared exactly: case-sensitive and without any normalisation, so
 * {@code "Ada"} and {@code "ada"} are two users. Roles are plain names such as {@code "ADMIN"},
 * without a {@code ROLE_} prefix.
 *
 * @param username     the username, 1 to 256 characters
 * @param passwordHash the stored hash, as produced by a {@link PasswordHasher}; not checked
 *                     here (the hasher parses it when the user logs in), never shown by
 *                     {@link #toString()}
 * @param roles        the roles, each 1 to 100 characters of printable ASCII without spaces;
 *                     copied into an unmodifiable, sorted set, so later changes to the given
 *                     set do not show
 * @param enabled      {@code false} for a disabled account, which cannot log in
 */
public record User(String username, String passwordHash, Set<String> roles, boolean enabled) {

    /**
     * Checks and copies the components.
     *
     * @throws NullPointerException     if a component or a role is {@code null}
     * @throws IllegalArgumentException if the username is empty or longer than 256 characters,
     *                                  or a role breaks the rules above
     */
    public User {
        Names.username(username, "username");
        Objects.requireNonNull(passwordHash, "passwordHash");
        roles = Names.roles(roles);
    }

    /**
     * The username (escaped), roles and whether the account is enabled; never the hash.
     *
     * @return {@code User[username=..., roles=[...], enabled=...]}
     */
    @Override
    public String toString() {
        return "User[username=" + Names.escape(username) + ", roles=" + roles + ", enabled=" + enabled + "]";
    }
}
