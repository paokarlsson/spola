package spola.security;

/**
 * Stores a new hash for a user after a login whose stored hash was made with old settings: the
 * counterpart of Spring Security's {@code UserDetailsPasswordService}. See
 * {@link Authenticator#of(UserStore, PasswordHasher, PasswordUpgrade)}; for an
 * {@link InMemoryUserStore} use {@link InMemoryUserStore#upgrader()}.
 *
 * <p><b>Replace only the verified hash.</b> The authenticator computes the new hash after the
 * password has been verified, which takes as long as one hash. If the password is changed in
 * the meantime, an unconditional write would store a hash of the <em>old</em> password over
 * the new one, undoing the change. So the upgrade is a compare-and-set: replace the stored hash
 * with {@code newHash} only if it is still exactly {@code verifiedHash}, the hash the login read
 * and verified, in one atomic step, and otherwise do nothing. In SQL that is one statement:
 *
 * <pre>{@code
 * UPDATE users SET hash = ? WHERE username = ? AND hash = ?   -- newHash, username, verifiedHash
 * }</pre>
 *
 * <p>No row updated means that the hash has changed since the login read it (a password change,
 * or another login's upgrade) or that the user is gone; that is not an error. A stored hash that
 * is still {@code verifiedHash} is a hash of the password that was just verified, so replacing it
 * never changes which password works, even if the same string was stored again in between.
 */
@FunctionalInterface
public interface PasswordUpgrade {

    /**
     * Replaces the stored hash with {@code newHash} if it is still {@code verifiedHash};
     * otherwise does nothing.
     *
     * @param username     the user, as returned by the {@link UserStore}
     * @param verifiedHash the stored hash that the login read and verified, exactly as the
     *                     {@link UserStore} returned it
     * @param newHash      the new hash of the same password, made with the current settings
     */
    void upgrade(String username, String verifiedHash, String newHash);
}
