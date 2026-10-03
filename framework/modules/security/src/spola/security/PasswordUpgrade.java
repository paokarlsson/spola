package spola.security;

/**
 * Stores a new hash for a user after a login whose stored hash was made with old settings: the
 * counterpart of Spring Security's {@code UserDetailsPasswordService}. See
 * {@link Authenticator#of(UserStore, PasswordHasher, PasswordUpgrade)}; for an
 * {@link InMemoryUserStore} use {@link InMemoryUserStore#upgrader}.
 *
 * <p><b>Make the replacement conditional.</b> The authenticator computes the new hash after the
 * password has been verified, which takes as long as one hash. If the password is changed in
 * the meantime, an unconditional write would store a hash of the <em>old</em> password over
 * the new one, undoing the change. So replace the stored hash only if it still
 * {@linkplain PasswordHasher#needsRehash needs a rehash}, in one atomic step (for example a
 * transaction that reads the row with {@code SELECT ... FOR UPDATE}, checks it and writes, or a
 * check inside {@code ConcurrentMap.computeIfPresent}), and otherwise do nothing.
 */
@FunctionalInterface
public interface PasswordUpgrade {

    /**
     * Replaces the stored hash if it still needs a rehash; otherwise does nothing.
     *
     * @param username the user, as returned by the {@link UserStore}
     * @param newHash  the new hash of the same password, made with the current settings
     */
    void upgrade(String username, String newHash);
}
