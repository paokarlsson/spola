package spola.security;

import java.util.Set;

/** The README example: users in memory, PBKDF2 hashes, and an authenticator that upgrades old hashes. */
public final class LoginExample {

    private LoginExample() {
    }

    /**
     * The users and the authenticator, wired by hand.
     *
     * @param hasher {@code Pbkdf2PasswordHasher.defaults()} in production
     * @param users  where the users live; also stores upgraded hashes
     * @return the authenticator
     */
    public static Authenticator authenticator(PasswordHasher hasher, InMemoryUserStore users) {
        users.add(new User("ada", hasher.hash("correct horse battery staple"), Set.of("USER", "ADMIN"), true));
        users.add(new User("bob", hasher.hash("hunter2hunter2"), Set.of("USER"), false));
        return Authenticator.of(users, hasher, users.upgrader());
    }

    /**
     * One login attempt, as a controller would answer it.
     *
     * @param authenticator the authenticator
     * @param username      the username from the request
     * @param password      the password from the request
     * @return what to tell the user
     */
    public static String login(Authenticator authenticator, String username, String password) {
        return switch (authenticator.authenticate(username, password)) {
            case AuthResult.Authenticated(Identity identity) when identity.hasRole("ADMIN") ->
                    "welcome, administrator " + identity.name();
            case AuthResult.Authenticated(Identity identity) -> "welcome, " + identity.name();
            case AuthResult.Failed(Failure reason) when reason == Failure.DISABLED -> "your account is disabled";
            case AuthResult.Failed failed -> "wrong username or password";
        };
    }
}
