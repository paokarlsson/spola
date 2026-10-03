package spola.security.web;

import java.util.Objects;
import spola.security.Identity;

/**
 * What a {@link RequestAuthentication} made of a request: {@link None} (no credentials for this
 * mechanism), {@link Success} or {@link Rejected} (credentials were there but wrong or
 * malformed). The {@link WebSecurity} filter tries its mechanisms in order and the first one
 * that does not answer {@code None} decides; a {@code Rejected} always gives 401, also on a
 * path that is {@link AccessRule#permitAll() permitAll}, so wrong credentials are never ignored.
 */
public sealed interface AuthOutcome {

    /** The request has no credentials for this mechanism; the next mechanism is tried. */
    record None() implements AuthOutcome {
    }

    /**
     * The credentials were right.
     *
     * @param identity who sent the request
     */
    record Success(Identity identity) implements AuthOutcome {

        /**
         * Checks the component.
         *
         * @throws NullPointerException if {@code identity} is {@code null}
         */
        public Success {
            Objects.requireNonNull(identity, "identity");
        }
    }

    /** The request had credentials for this mechanism, but they were wrong or malformed: 401. */
    record Rejected() implements AuthOutcome {
    }
}
