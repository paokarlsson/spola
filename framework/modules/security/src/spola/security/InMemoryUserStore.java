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
 * <p>{@link #updatePassword} has the shape of {@link PasswordUpgrade}, so
 * {@code Authenticator.of(store, hasher, store::updatePassword)} upgrades old hashes in place.
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
     * The number of users, never names or hashes.
     *
     * @return {@code InMemoryUserStore[users=N]}
     */
    @Override
    public String toString() {
        return "InMemoryUserStore[users=" + users.size() + "]";
    }
}
