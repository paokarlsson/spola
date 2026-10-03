package spola.security.web;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import spola.security.Identity;
import spola.web.WebRequest;

/**
 * Whether a request may go on, the counterpart of Spring Security's {@code permitAll()},
 * {@code denyAll()}, {@code authenticated()}, {@code hasRole(...)} and
 * {@code hasAnyRole(...)}. A rule is a plain function of the identity and the request; a
 * lambda is a rule:
 *
 * <pre>{@code
 * AccessRule ownOrders = (identity, request) -> identity.isPresent()
 *         && request.pathParam("user").equals(identity.get().name());
 * }</pre>
 *
 * <p>A request that a rule does not allow gets 401 when it is anonymous and 403 when it is
 * authenticated (see {@link WebSecurity}). An exception from a rule goes to the router's
 * exception mappers (500 without one); the request is never allowed because of it.
 *
 * <p>Roles are compared exactly (case-sensitive, no {@code ROLE_} prefix) and follow the rules
 * of {@link Identity}: 1 to 100 characters of printable ASCII without spaces. The factories
 * check them when the rule is made.
 */
@FunctionalInterface
public interface AccessRule {

    /**
     * Whether the request may go on.
     *
     * @param identity who sent the request, or empty for an anonymous request
     * @param request  the request, with its {@link SecurityContext} attribute set
     * @return {@code true} to let it through
     */
    boolean allows(Optional<Identity> identity, WebRequest request);

    /**
     * Every request, anonymous or not. Wrong credentials still give 401: a
     * {@link AuthOutcome.Rejected} is answered before any rule is looked at.
     *
     * @return the rule
     */
    static AccessRule permitAll() {
        return Rules.PERMIT_ALL;
    }

    /**
     * No request: 401 for an anonymous one, 403 for an authenticated one.
     *
     * @return the rule
     */
    static AccessRule denyAll() {
        return Rules.DENY_ALL;
    }

    /**
     * Every authenticated request, whatever its roles.
     *
     * @return the rule
     */
    static AccessRule authenticated() {
        return Rules.AUTHENTICATED;
    }

    /**
     * Authenticated requests whose identity has {@code role}.
     *
     * @param role the role, such as {@code "ADMIN"}
     * @return the rule
     * @throws NullPointerException     if {@code role} is {@code null}
     * @throws IllegalArgumentException if {@code role} is not 1 to 100 characters of printable
     *                                  ASCII without spaces
     */
    static AccessRule hasRole(String role) {
        return new Rules.AnyRole(Rules.roles(List.of(Objects.requireNonNull(role, "role"))));
    }

    /**
     * Authenticated requests whose identity has at least one of {@code roles}.
     *
     * @param roles the roles, at least one
     * @return the rule
     * @throws NullPointerException     if {@code roles} or a role is {@code null}
     * @throws IllegalArgumentException if no role is given or a role is not 1 to 100 characters
     *                                  of printable ASCII without spaces
     */
    static AccessRule hasAnyRole(String... roles) {
        Objects.requireNonNull(roles, "roles");
        if (roles.length == 0) {
            throw new IllegalArgumentException("hasAnyRole needs at least one role (use denyAll() to deny everyone)");
        }
        for (var role : roles) {
            Objects.requireNonNull(role, "role");
        }
        return new Rules.AnyRole(Rules.roles(List.of(roles)));
    }
}
