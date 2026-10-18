package spola.security;

import java.lang.System.Logger.Level;
import java.util.Objects;

/**
 * Checks a username and a password against a {@link UserStore} with a {@link PasswordHasher}:
 * the counterpart of Spring Security's {@code DaoAuthenticationProvider}. Immutable and safe to
 * use from any number of threads (as far as the store, hasher and upgrade are).
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li><b>Unknown users cost the same, within one algorithm and cost.</b> When the store has
 *       no such user, the hasher is still called exactly once, with
 *       {@link PasswordHasher#matches} against a fixed dummy hash made with the same hasher when
 *       the authenticator is created, and the result is {@link Failure#BAD_CREDENTIALS}. A
 *       username that is {@code null}, empty or longer than 256 characters, and a {@code null}
 *       password, also give {@code BAD_CREDENTIALS} after one check against the dummy hash (and
 *       without asking the store). The work is therefore the same as for a known user with a
 *       wrong password whose stored hash has the hasher's current algorithm and cost.</li>
 *   <li><b>Old hashes can be told apart.</b> The dummy hash has the hasher's current settings
 *       (with {@link PasswordHasher#delegating} the default id's algorithm), while a known user
 *       is checked with the algorithm and cost of the stored hash. A user whose hash is still
 *       stored with another algorithm or cost (a {@code {pbkdf2}} hash when the default is
 *       {@code bcrypt}, a lower bcrypt cost, other PBKDF2 settings) takes a different time from
 *       an unknown user, so an attacker who measures it can tell that the username exists, until
 *       the user has logged in once and the hash has been upgraded (only with a
 *       {@link PasswordUpgrade}; without one the difference stays). A password that one
 *       algorithm refuses without any work and the other computes makes the difference free of
 *       noise: bcrypt refuses more than 72 bytes, PBKDF2 computes up to 1 024 characters. A
 *       stored bcrypt hash with a <em>higher</em> cost is never upgraded, so give the hasher at
 *       least the highest stored cost (see {@link BCryptPasswordHasher}). The time is not evened
 *       out between algorithms.</li>
 *   <li><b>A malformed stored hash is a failed login.</b> When {@link PasswordHasher#matches}
 *       throws {@link IllegalArgumentException} for a user that the store found (its stored hash
 *       cannot be parsed: a leftover {@code {noop}}, {@code {sha256}} or {@code {MD5}} row after
 *       a move from Spring, a truncated bcrypt hash, an empty string), the dummy hash is checked
 *       once, as for an unknown user, a line is logged at {@code WARNING} on the
 *       {@link System.Logger} {@code spola.security} with only the exception's class name (never
 *       the hash, the password or the username), and the result is {@code BAD_CREDENTIALS}. The
 *       data error shows in the log, but the answer is the same as for an unknown user, so it
 *       does not tell that the username exists. The hashers themselves still throw.</li>
 *   <li><b>{@link Failure#DISABLED} only after the right password.</b> With a wrong password a
 *       disabled account gives {@code BAD_CREDENTIALS}, so someone who does not know the
 *       password cannot find out whether an account is disabled.</li>
 *   <li><b>Upgrade.</b> When the password is right, the account is enabled and
 *       {@link PasswordHasher#needsRehash} is {@code true}, the {@link PasswordUpgrade} (if
 *       any) is called with the stored hash that was verified and a new hash of the same
 *       password, and replaces the stored hash only if it is still the verified one. A
 *       {@link RuntimeException} from hashing or from the upgrade is caught and logged at
 *       {@code WARNING} with only the exception's class name, and the login succeeds anyway; an
 *       {@link Error} is not caught.</li>
 *   <li><b>Store errors are not login failures.</b> An exception from {@link UserStore#find}
 *       propagates unchanged. A store that returns {@code null} instead of an {@code Optional}
 *       gives {@link NullPointerException}.</li>
 * </ul>
 * The {@link Identity} of a successful login has the username and roles of the {@link User}
 * the store returned. There is no lockout after failed attempts and no rate limiting.
 */
public final class Authenticator {

    /** Hashed once per authenticator to make the dummy hash; never matched by a real login. */
    private static final String DUMMY_PASSWORD = "spola.security: no such user";
    private static final AuthResult BAD_CREDENTIALS = new AuthResult.Failed(Failure.BAD_CREDENTIALS);
    private static final AuthResult DISABLED = new AuthResult.Failed(Failure.DISABLED);

    private final UserStore store;
    private final PasswordHasher hasher;
    /** {@code null} only from {@link #of(UserStore, PasswordHasher)}: no upgrade. */
    private final PasswordUpgrade upgrade;
    private final System.Logger log;
    private final String dummyHash;

    /** {@code upgrade} may be {@code null} (no upgrade); the public factories decide that. */
    private Authenticator(UserStore store, PasswordHasher hasher, PasswordUpgrade upgrade, System.Logger log) {
        this.store = Objects.requireNonNull(store, "store");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.upgrade = upgrade;
        this.log = Objects.requireNonNull(log, "log");
        this.dummyHash = Objects.requireNonNull(hasher.hash(DUMMY_PASSWORD), "hasher.hash returned null");
    }

    /**
     * An authenticator without upgrade of old hashes. Hashes the dummy password once.
     *
     * @param store  where users are looked up
     * @param hasher how passwords are checked
     * @return the authenticator
     * @throws NullPointerException if an argument is {@code null}
     */
    public static Authenticator of(UserStore store, PasswordHasher hasher) {
        return new Authenticator(store, hasher, null, System.getLogger("spola.security"));
    }

    /**
     * An authenticator that upgrades hashes made with old settings. Hashes the dummy password
     * once.
     *
     * @param store   where users are looked up
     * @param hasher  how passwords are checked and new hashes made
     * @param upgrade where new hashes are stored, such as {@link InMemoryUserStore#upgrader()};
     *                it must replace the stored hash only if it is still the verified one (see
     *                {@link PasswordUpgrade})
     * @return the authenticator
     * @throws NullPointerException if an argument is {@code null}
     */
    public static Authenticator of(UserStore store, PasswordHasher hasher, PasswordUpgrade upgrade) {
        return of(store, hasher, upgrade, System.getLogger("spola.security"));
    }

    /**
     * As {@link #of(UserStore, PasswordHasher, PasswordUpgrade)}, logging to {@code log} (for
     * tests). Like it, {@code upgrade} may not be {@code null}.
     */
    static Authenticator of(UserStore store, PasswordHasher hasher, PasswordUpgrade upgrade, System.Logger log) {
        return new Authenticator(store, hasher, Objects.requireNonNull(upgrade, "upgrade"), log);
    }

    /**
     * Checks a username and a password.
     *
     * @param username the username, compared exactly; {@code null}, empty or longer than 256
     *                 characters gives {@code BAD_CREDENTIALS}
     * @param password the password; {@code null} gives {@code BAD_CREDENTIALS}
     * @return {@link AuthResult.Authenticated} or {@link AuthResult.Failed}; also
     *         {@code BAD_CREDENTIALS} when the user's stored hash is malformed (logged)
     * @throws RuntimeException whatever {@link UserStore#find} throws
     */
    public AuthResult authenticate(String username, CharSequence password) {
        if (username == null || username.isEmpty() || username.length() > Names.MAX_USERNAME_CHARS
                || password == null) {
            hasher.matches(password == null ? DUMMY_PASSWORD : password, dummyHash);
            return BAD_CREDENTIALS;
        }
        var found = Objects.requireNonNull(store.find(username), "UserStore.find returned null");
        if (found.isEmpty()) {
            hasher.matches(password, dummyHash);
            return BAD_CREDENTIALS;
        }
        var user = found.get();
        var stored = user.passwordHash();
        boolean matches;
        try {
            matches = hasher.matches(password, stored);
        } catch (IllegalArgumentException e) {
            // A malformed stored hash: the same work and answer as for an unknown user.
            hasher.matches(password, dummyHash);
            log.log(Level.WARNING, "login refused, the stored password hash is malformed: "
                    + Names.escape(e.getClass().getName()));
            return BAD_CREDENTIALS;
        }
        if (!matches) {
            return BAD_CREDENTIALS;
        }
        if (!user.enabled()) {
            return DISABLED;
        }
        if (upgrade != null && hasher.needsRehash(stored)) {
            try {
                upgrade.upgrade(user.username(), stored, hasher.hash(password));
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "password upgrade failed: " + Names.escape(e.getClass().getName()));
            }
        }
        return new AuthResult.Authenticated(new Identity(user.username(), user.roles()));
    }

    /**
     * What this authenticator uses, never a hash.
     *
     * @return {@code Authenticator[hasher=..., upgrade=true|false]}
     */
    @Override
    public String toString() {
        return "Authenticator[hasher=" + hasher + ", upgrade=" + (upgrade != null) + "]";
    }
}
