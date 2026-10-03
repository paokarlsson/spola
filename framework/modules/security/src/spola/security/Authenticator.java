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
 *   <li><b>Unknown users cost the same.</b> When the store has no such user, the hasher is
 *       still called exactly once, with {@link PasswordHasher#matches} against a fixed dummy
 *       hash made with the same hasher when the authenticator is created, and the result is
 *       {@link Failure#BAD_CREDENTIALS}. A username that is {@code null}, empty or longer than
 *       256 characters, and a {@code null} password, also give {@code BAD_CREDENTIALS} after
 *       one check against the dummy hash (and without asking the store). The work is therefore
 *       the same as for a known user with a wrong password.</li>
 *   <li><b>{@link Failure#DISABLED} only after the right password.</b> With a wrong password a
 *       disabled account gives {@code BAD_CREDENTIALS}, so someone who does not know the
 *       password cannot find out whether an account is disabled.</li>
 *   <li><b>Upgrade.</b> When the password is right, the account is enabled and
 *       {@link PasswordHasher#needsRehash} is {@code true}, the {@link PasswordUpgrade} (if
 *       any) is called with a new hash of the same password. A {@link RuntimeException} from
 *       hashing or from the upgrade is caught and logged at {@code WARNING} on the
 *       {@link System.Logger} {@code spola.security} with only the exception's class name, and
 *       the login succeeds anyway; an {@link Error} is not caught.</li>
 *   <li><b>Data errors are not login failures.</b> An exception from {@link UserStore#find}
 *       and the {@link IllegalArgumentException} for a malformed stored hash propagate
 *       unchanged. A store that returns {@code null} instead of an {@code Optional} gives
 *       {@link NullPointerException}.</li>
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
     * @param upgrade where new hashes are stored, such as {@link InMemoryUserStore#upgrader}
     *                {@code (hasher)}; it must only replace a hash that still needs a rehash (see
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
     * @return {@link AuthResult.Authenticated} or {@link AuthResult.Failed}
     * @throws IllegalArgumentException if the user's stored hash is malformed
     * @throws RuntimeException         whatever {@link UserStore#find} throws
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
        if (!hasher.matches(password, user.passwordHash())) {
            return BAD_CREDENTIALS;
        }
        if (!user.enabled()) {
            return DISABLED;
        }
        if (upgrade != null && hasher.needsRehash(user.passwordHash())) {
            try {
                upgrade.upgrade(user.username(), hasher.hash(password));
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
