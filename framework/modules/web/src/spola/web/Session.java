package spola.web;

import java.util.Objects;
import java.util.Optional;

/**
 * An HTTP session: typed attributes kept in memory between requests from one browser, the
 * counterpart of the servlet API's {@code HttpSession}. Get it in a handler or a filter with
 * {@link Sessions#getOrCreate} ({@code request.getSession()}) or {@link Sessions#find}
 * ({@code request.getSession(false)}); the session filter ({@link Sessions#filter()}) must have
 * run for the request.
 *
 * <pre>{@code
 * static final AttributeKey<Cart> CART = AttributeKey.of("cart", Cart.class);
 *
 * Session session = Sessions.getOrCreate(request);
 * Cart cart = session.get(CART).orElseGet(Cart::empty);
 * session.put(CART, cart.add(item));
 * }</pre>
 *
 * <p>The attributes use the same {@link AttributeKey} as request attributes: a key is compared
 * by identity, and {@link #put} checks the value against the key's type. Values are kept as
 * references (nothing is copied or serialized), so store immutable values, or values that are
 * safe to use from several requests at once. Thread-safe.
 *
 * <p>There is no {@code getId()}: the id is a secret that only the session cookie carries, and
 * the application never needs it. {@link #toString()} shows how many attributes there are,
 * never the id or the values.
 *
 * <p><b>An ended session.</b> Once the session has been invalidated ({@link Sessions#invalidate},
 * by any request) or has expired ({@link SessionOptions#idleTimeout}), and once another request
 * has given it a new id with {@link Sessions#renew} (a request that came with the old id loses
 * the session at once), this object no longer sees it: {@link #get} gives empty,
 * {@link #remove} does nothing and {@link #put} throws {@link IllegalStateException}, as
 * {@code HttpSession} does after {@code invalidate()}. A {@code renew} in the same request
 * keeps this object working, with the new id.
 */
public final class Session {

    private final SessionEntry entry;
    /** The entry's generation this view knows; guarded by the entry's lock. */
    long generation;

    Session(SessionEntry entry, long generation) {
        this.entry = entry;
        this.generation = generation;
    }

    /**
     * An attribute, the counterpart of {@code HttpSession.getAttribute}.
     *
     * @param key the key
     * @param <T> the value type
     * @return the value, or empty if none was put or the session has ended
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public <T> Optional<T> get(AttributeKey<T> key) {
        Objects.requireNonNull(key, "key");
        return entry.get(this, key).map(key.type()::cast);
    }

    /**
     * Sets an attribute (replacing the key's earlier value), the counterpart of
     * {@code HttpSession.setAttribute}. The value is kept as a reference.
     *
     * @param key   the key
     * @param value the value, an instance of the key's type
     * @param <T>   the value type
     * @throws NullPointerException  if {@code key} or {@code value} is {@code null} (remove an
     *                               attribute with {@link #remove})
     * @throws ClassCastException    if {@code value} is not an instance of the key's type (only
     *                               possible through a raw key)
     * @throws IllegalStateException if the session has ended (see the class documentation)
     */
    public <T> void put(AttributeKey<T> key, T value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (!key.type().isInstance(value)) {
            throw new ClassCastException("a value of type " + value.getClass().getName() + " cannot be put under "
                    + key);
        }
        entry.put(this, key, value);
    }

    /**
     * Removes an attribute, the counterpart of {@code HttpSession.removeAttribute}. Nothing
     * happens if the key has no value or the session has ended.
     *
     * @param key the key
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public void remove(AttributeKey<?> key) {
        Objects.requireNonNull(key, "key");
        entry.remove(this, key);
    }

    /** Whether this view still sees the session. */
    boolean visible() {
        return entry.visible(this);
    }

    /** Gives the session a new id; the id, or {@code null} if this view no longer sees the session. */
    String renew() {
        return entry.renew(this);
    }

    /** Ends the session if this view still sees it; whether it did. */
    boolean invalidate() {
        return entry.invalidate(this);
    }

    /** The entry this view shows. */
    SessionEntry entry() {
        return entry;
    }

    /** The user whose sessions this one is counted among ({@link Sessions#renewFor}), or {@code null}. */
    String user() {
        return entry.user(this);
    }

    /**
     * {@code Session[n attributes]}, or {@code Session[ended]}: never the id or the values.
     *
     * @return the description
     */
    @Override
    public String toString() {
        if (!visible()) {
            return "Session[ended]";
        }
        int size = entry.size();
        return "Session[" + size + (size == 1 ? " attribute]" : " attributes]");
    }
}
