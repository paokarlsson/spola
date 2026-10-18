package spola.security;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Users in memory: the counterpart of Spring Security's {@code InMemoryUserDetailsManager}.
 * Safe to use from any number of threads. Usernames are compared exactly (case-sensitive, no
 * normalisation).
 *
 * <p>{@link #updatePassword} is for password changes. Upgrades of old hashes go through
 * {@link #upgrader()}: {@code Authenticator.of(store, hasher, store.upgrader())}.
 */
public final class InMemoryUserStore implements UserStore {

    private final ConcurrentMap<String, User> users = new ConcurrentHashMap<>();

    /** An empty store. */
    public InMemoryUserStore() {
    }

    /**
     * Adds a user.
     *
     * @param user the user
     * @throws NullPointerException     if {@code user} is {@code null}
     * @throws IllegalArgumentException if a user with the same username exists
     */
    public void add(User user) {
        Objects.requireNonNull(user, "user");
        if (users.putIfAbsent(user.username(), user) != null) {
            throw new IllegalArgumentException("a user with this username already exists");
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws NullPointerException if {@code username} is {@code null}
     */
    @Override
    public Optional<User> find(String username) {
        Objects.requireNonNull(username, "username");
        return Optional.ofNullable(users.get(username));
    }

    /**
     * Replaces a user's stored hash, keeping the username, roles and enabled flag. Atomic with
     * respect to other calls on this store.
     *
     * @param username the username
     * @param newHash  the new hash
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if there is no such user
     */
    public void updatePassword(String username, String newHash) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(newHash, "newHash");
        var updated = users.computeIfPresent(username,
                (name, user) -> new User(user.username(), newHash, user.roles(), user.enabled()));
        if (updated == null) {
            throw new IllegalArgumentException("no user with this username");
        }
    }

    /**
     * The {@link PasswordUpgrade} for an {@link Authenticator} using this store: a
     * compare-and-set that replaces a user's hash with {@code newHash} only if the stored hash
     * is still exactly {@code verifiedHash} ({@link String#equals}), checked and replaced in one
     * atomic step. A hash that a password change or another login's upgrade has stored in the
     * meantime is therefore never overwritten, whatever settings it was made with. The username,
     * roles and enabled flag are kept, and a user that no longer exists is left alone. The stored
     * hash is not parsed.
     *
     * <p>Use {@link #updatePassword}, not this, for password changes.
     *
     * @return the upgrade
     */
    public PasswordUpgrade upgrader() {
        return (username, verifiedHash, newHash) -> {
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(verifiedHash, "verifiedHash");
            Objects.requireNonNull(newHash, "newHash");
            users.computeIfPresent(username, (name, user) -> user.passwordHash().equals(verifiedHash)
                    ? new User(user.username(), newHash, user.roles(), user.enabled())
                    : user);
        };
    }

    /**
     * The number of users, never names or hashes.
     *
     * @return {@code InMemoryUserStore[users=N]}
     */
    @Override
    public String toString() {
        return "InMemoryUserStore[users=" + users.size() + "]";
    }
}
