package spola.security;

import java.util.Map;

/**
 * Hashes passwords for storage and checks a password against a stored hash. The counterpart of
 * Spring Security's {@code PasswordEncoder}. The implementations are
 * {@link Pbkdf2PasswordHasher} (PBKDF2-HMAC-SHA256 in the PHC string format),
 * {@link BCryptPasswordHasher} (bcrypt in the format of Spring's {@code BCryptPasswordEncoder})
 * and {@link #delegating} (a {@code {id}} prefix that chooses the hasher, like Spring's
 * {@code DelegatingPasswordEncoder}).
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
     *                                  valid UTF-16; for {@link BCryptPasswordHasher} also more
     *                                  than 72 bytes or containing U+0000)
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

    /**
     * A hasher that stores {@code {id}<hash>} and lets the id choose the hasher, like Spring
     * Security's {@code DelegatingPasswordEncoder}. New hashes are made with the default id's
     * hasher; stored hashes with any of the ids are verified, and {@link #needsRehash} moves
     * them to the default, so that {@link Authenticator}'s upgrade migrates users from one
     * algorithm to another as they log in:
     *
     * <pre>{@code
     * PasswordHasher hasher = PasswordHasher.delegating("bcrypt", Map.of(
     *         "bcrypt", BCryptPasswordHasher.defaults(),
     *         "pbkdf2", Pbkdf2PasswordHasher.defaults()));
     * hasher.hash("hemligt");                                   // "{bcrypt}$2a$10$..."
     * hasher.matches("hemligt", "{bcrypt}$2a$10$C/CQCvOTDfaWEPmZF/ycFuvu6miZtNk3jj/4zDoWuN3Icm9TmKt9e"); // true
     * }</pre>
     *
     * <h4>Ids</h4>
     * The ids are the application's names for its hashers. Each is 1 to 100 characters of
     * printable ASCII (0x21 to 0x7E) without <code>&#123;</code> and <code>&#125;</code>, and
     * they are compared exactly (case-sensitive). The id only chooses the hasher: {@code "pbkdf2"} above reads
     * Spola's PHC format ({@link Pbkdf2PasswordHasher}), not the hex format of Spring's
     * {@code Pbkdf2PasswordEncoder}, which is not supported.
     *
     * <h4>Behaviour</h4>
     * <ul>
     *   <li>{@code hash(p)} is {@code "{" + defaultId + "}" + hashers.get(defaultId).hash(p)}.
     *       The delegate's exceptions propagate (for example the
     *       {@link IllegalArgumentException} of {@link BCryptPasswordHasher} for a password of
     *       more than 72 bytes); a delegate that returns {@code null} gives
     *       {@link NullPointerException}.</li>
     *   <li>{@code matches(p, encoded)}: the string must start with <code>&#123;</code>; the
     *       id is the text up to the first <code>&#125;</code>, which is looked for at most 101
     *       characters in (an id has at most 100), and it must be exactly one of the ids. The
     *       rest goes to that id's
     *       hasher, and its {@link IllegalArgumentException} for a malformed rest propagates
     *       unchanged.</li>
     *   <li>Anything else is a data error, an {@link IllegalArgumentException} whose message
     *       contains neither the string nor the text between the braces (it may be a hash). In
     *       particular, a hash <b>without</b> a prefix is an error, as with Spring's default;
     *       there is no fallback hasher for unprefixed hashes (Spring's
     *       {@code setDefaultPasswordEncoderForMatches}). A bare hash from Spring's
     *       {@code BCryptPasswordEncoder} is verified with {@link BCryptPasswordHasher}
     *       directly.</li>
     *   <li>{@code needsRehash(encoded)} parses the prefix the same way and always asks the
     *       id's hasher about the rest, so a malformed rest throws for every id. It is
     *       {@code true} if the id is not the default id or that hasher says so.</li>
     *   <li>{@code null} arguments give {@link NullPointerException}.</li>
     *   <li>{@code toString()} shows the default id and the sorted ids, never a hash:
     *       {@code DelegatingPasswordHasher[default=bcrypt, ids=[bcrypt, pbkdf2]]}.</li>
     * </ul>
     * The map is copied, so later changes to it are not seen. The result is immutable, and
     * safe to use from any number of threads if the delegates are.
     *
     * @param defaultId the id of the hasher for new hashes; one of the keys of {@code hashers}
     * @param hashers   the hashers by id; not empty
     * @return the delegating hasher
     * @throws NullPointerException     if {@code defaultId}, {@code hashers} or one of its ids
     *                                  or hashers is {@code null}
     * @throws IllegalArgumentException if {@code hashers} is empty, an id is not 1 to 100
     *                                  characters of printable ASCII without braces, or
     *                                  {@code defaultId} is not one of the ids; the message
     *                                  names the problem
     */
    static PasswordHasher delegating(String defaultId, Map<String, ? extends PasswordHasher> hashers) {
        return DelegatingPasswordHasher.of(defaultId, hashers);
    }
}
