package spola.security;

/**
 * Stores a new hash for a user after a login whose stored hash was made with old settings: the
 * counterpart of Spring Security's {@code UserDetailsPasswordService}. See
 * {@link Authenticator#of(UserStore, PasswordHasher, PasswordUpgrade)}.
 */
@FunctionalInterface
public interface PasswordUpgrade {

    /**
     * Replaces the stored hash.
     *
     * @param username the user, as returned by the {@link UserStore}
     * @param newHash  the new hash of the same password, made with the current settings
     */
    void upgrade(String username, String newHash);
}
