package spola.security;

/**
 * Hashes passwords for storage and checks a password against a stored hash. The counterpart of
 * Spring Security's {@code PasswordEncoder}. {@link Pbkdf2PasswordHasher} is the implementation.
 *
 * <p>Implementations must be safe to use from any number of threads.
 */
public interface PasswordHasher {

    /**
     * Hashes a password with a fresh random salt.
     *
     * @param password the password
     * @return the encoded hash, to be stored
     * @throws NullPointerException     if {@code password} is {@code null}
     * @throws IllegalArgumentException if the implementation does not accept the password (for
     *                                  {@link Pbkdf2PasswordHasher}: empty, too long or not
     *                                  valid UTF-16)
     */
    String hash(CharSequence password);

    /**
     * Checks a password against a stored hash.
     *
     * @param password the password to check
     * @param encoded  a hash produced by {@link #hash}
     * @return {@code true} if the password is the one the hash was made from; {@code false}
     *         for any other password, including one that {@link #hash} would refuse
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code encoded} is not a well-formed hash; that is a
     *                                  data error, not a wrong password, and the message does
     *                                  not contain {@code encoded}
     */
    boolean matches(CharSequence password, String encoded);

    /**
     * Whether a stored hash was made with other settings than this hasher's, so that it should
     * be replaced by {@link #hash} of the password the next time the password is known.
     *
     * @param encoded a hash produced by {@link #hash}
     * @return {@code true} if the hash should be replaced
     * @throws NullPointerException     if {@code encoded} is {@code null}
     * @throws IllegalArgumentException if {@code encoded} is not a well-formed hash; the
     *                                  message does not contain {@code encoded}
     */
    boolean needsRehash(String encoded);
}
