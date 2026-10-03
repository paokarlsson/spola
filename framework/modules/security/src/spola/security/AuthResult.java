package spola.security;

import java.util.Objects;

/**
 * The result of {@link Authenticator#authenticate}: {@link Authenticated} or {@link Failed}.
 * Neither carries a password or a hash.
 */
public sealed interface AuthResult {

    /**
     * The login succeeded.
     *
     * @param identity who logged in
     */
    record Authenticated(Identity identity) implements AuthResult {

        /**
         * Checks the component.
         *
         * @throws NullPointerException if {@code identity} is {@code null}
         */
        public Authenticated {
            Objects.requireNonNull(identity, "identity");
        }
    }

    /**
     * The login failed.
     *
     * @param reason why
     */
    record Failed(Failure reason) implements AuthResult {

        /**
         * Checks the component.
         *
         * @throws NullPointerException if {@code reason} is {@code null}
         */
        public Failed {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
