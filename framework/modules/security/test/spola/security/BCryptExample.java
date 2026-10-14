package spola.security;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** The README example: hashes from Spring's BCryptPasswordEncoder, verified and made in the same format. */
public final class BCryptExample {

    private BCryptExample() {
    }

    /**
     * An account as the application reads it, from {@code APP_AUTH_USERS_<n>_USERNAME} and
     * {@code APP_AUTH_USERS_<n>_PASSWORD_HASH}.
     *
     * @param username     the username
     * @param passwordHash the stored hash, such as {@code {bcrypt}$2a$10$...} from Spring
     */
    public record Account(String username, String passwordHash) {
    }

    /**
     * The hasher: new hashes with bcrypt, and stored hashes with {@code {bcrypt}} or
     * {@code {pbkdf2}}.
     *
     * @return the hasher
     */
    public static PasswordHasher hasher() {
        // Verifiera hashar som Spring skrev, och skapa nya i samma format.
        PasswordHasher bcrypt = BCryptPasswordHasher.defaults();          // kostnad 10, $2a$ som Spring
        PasswordHasher hasher = PasswordHasher.delegating("bcrypt", Map.of(
                "bcrypt", bcrypt,
                "pbkdf2", Pbkdf2PasswordHasher.defaults()));
        return hasher;
    }

    /**
     * Checks a password against a hash that Spring wrote.
     *
     * @param hasher           the hasher from {@link #hasher()}
     * @param password         the password
     * @param storedFromSpring the stored hash
     * @return whether the password is right
     */
    public static boolean check(PasswordHasher hasher, String password, String storedFromSpring) {
        return hasher.matches(password, storedFromSpring);               // "{bcrypt}$2a$10$...", som i Spring
    }

    /**
     * A new hash to store, for example from the command {@code --hash-password=...}.
     *
     * @param hasher   the hasher from {@link #hasher()}
     * @param password the password
     * @return the hash, which Spring's {@code DelegatingPasswordEncoder} also accepts
     */
    public static String hashPassword(PasswordHasher hasher, String password) {
        String stored = hasher.hash(password);                           // "{bcrypt}$2a$10$..."
        return stored;
    }

    /**
     * The users and the authenticator.
     *
     * @param hasher   the hasher from {@link #hasher()}
     * @param accounts the accounts from the environment
     * @return the authenticator
     */
    public static Authenticator authenticator(PasswordHasher hasher, List<Account> accounts) {
        var users = new InMemoryUserStore();
        for (var account : accounts) {                                    // från APP_AUTH_USERS_<n>_*
            users.add(new User(account.username(), account.passwordHash(), Set.of("USER"), true));
        }
        var authenticator = Authenticator.of(users, hasher);
        return authenticator;
    }
}
