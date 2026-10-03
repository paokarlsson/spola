package spola.security;

import java.util.Objects;
import java.util.Set;

/**
 * Who has logged in: the counterpart of Spring Security's {@code Authentication} after a
 * successful login. It never carries a password or a hash.
 *
 * @param name  the username, 1 to 256 characters
 * @param roles the roles, each 1 to 100 characters of printable ASCII without spaces; copied
 *              into an unmodifiable, sorted set
 */
public record Identity(String name, Set<String> roles) {

    /**
     * Checks and copies the components.
     *
     * @throws NullPointerException     if a component or a role is {@code null}
     * @throws IllegalArgumentException if the name is empty or longer than 256 characters, or
     *                                  a role breaks the rules above
     */
    public Identity {
        Names.username(name, "name");
        roles = Names.roles(roles);
    }

    /**
     * Whether this identity has a role, compared exactly (case-sensitive).
     *
     * @param role the role, such as {@code "ADMIN"}
     * @return {@code true} if {@link #roles()} contains it
     * @throws NullPointerException if {@code role} is {@code null}
     */
    public boolean hasRole(String role) {
        Objects.requireNonNull(role, "role");
        return roles.contains(role);
    }

    /**
     * The name (escaped) and the roles.
     *
     * @return {@code Identity[name=..., roles=[...]]}
     */
    @Override
    public String toString() {
        return "Identity[name=" + Names.escape(name) + ", roles=" + roles + "]";
    }
}
