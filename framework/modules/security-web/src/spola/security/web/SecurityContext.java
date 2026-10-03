package spola.security.web;

import java.util.Objects;
import java.util.Optional;
import spola.security.Identity;

/**
 * The security state of one request, the counterpart of Spring Security's
 * {@code SecurityContext}. The {@link WebSecurity} filter always puts one on the request, as
 * an attribute under a key only this module has; read it with {@link WebSecurity#identity} and
 * {@link WebSecurity#requireIdentity}. It lives on the request, not in a {@code ThreadLocal}.
 *
 * @param identity who sent the request, or empty for an anonymous request
 */
public record SecurityContext(Optional<Identity> identity) {

    /**
     * Checks the component.
     *
     * @throws NullPointerException if {@code identity} is {@code null}
     */
    public SecurityContext {
        Objects.requireNonNull(identity, "identity");
    }
}
