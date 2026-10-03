package spola.security.web;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import spola.security.Identity;
import spola.web.WebRequest;

/** The implementations behind {@link AccessRule}'s factories. */
final class Rules {

    /** The longest role, as in {@code spola.security}. */
    static final int MAX_ROLE_CHARS = 100;

    static final AccessRule PERMIT_ALL = new Named("permitAll()", true);
    static final AccessRule DENY_ALL = new Named("denyAll()", false);
    static final AccessRule AUTHENTICATED = new AccessRule() {
        @Override
        public boolean allows(Optional<Identity> identity, WebRequest request) {
            return identity.isPresent();
        }

        @Override
        public String toString() {
            return "authenticated()";
        }
    };

    private Rules() {
    }

    /** {@code permitAll()} or {@code denyAll()}. */
    private record Named(String name, boolean allowed) implements AccessRule {
        @Override
        public boolean allows(Optional<Identity> identity, WebRequest request) {
            Objects.requireNonNull(identity, "identity");
            return allowed;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** {@code hasRole} and {@code hasAnyRole}: authenticated with one of the roles. */
    record AnyRole(List<String> roles) implements AccessRule {
        @Override
        public boolean allows(Optional<Identity> identity, WebRequest request) {
            if (identity.isEmpty()) {
                return false;
            }
            for (var role : roles) {
                if (identity.get().hasRole(role)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return "hasAnyRole(" + String.join(", ", roles) + ")";
        }
    }

    /** The roles in order without duplicates; each 1 to 100 characters of printable ASCII without spaces. */
    static List<String> roles(List<String> roles) {
        var checked = new LinkedHashSet<String>();
        for (var role : roles) {
            if (role.isEmpty() || role.length() > MAX_ROLE_CHARS) {
                throw new IllegalArgumentException("a role must be 1 to " + MAX_ROLE_CHARS + " characters, was "
                        + role.length());
            }
            for (int i = 0; i < role.length(); i++) {
                char c = role.charAt(i);
                if (c < 0x21 || c > 0x7e) {
                    throw new IllegalArgumentException("role " + Text.quote(role)
                            + " must be printable ASCII without spaces");
                }
            }
            checked.add(role);
        }
        return List.copyOf(checked);
    }
}
