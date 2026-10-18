package spola.web;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One session in the store of a {@link Sessions}: its attributes, the key it is stored under
 * (the SHA-256 of its id, never the id), when it was last used (for expiry, and its place in the
 * order of uses), its generation, which {@link #renew} raises, the user it is logged in for
 * ({@link Sessions#renewFor}) and how many logins are being handled with it. Every field is
 * guarded by the entry's lock; the order in which the locks are taken is in {@link Sessions}.
 *
 * <p>A {@link Session} is a view of an entry for one request, with the generation the request
 * knows. Once another request renews the entry, the views with the old generation see an ended
 * session: a request that came with the old id loses the session at once, also when it was
 * already being handled. An entry that ended (invalidated or expired) never comes back, and it
 * leaves the index of its user's sessions when it ends.
 *
 * <p>The user is set when a login holds the entry: in the same step as the renewal
 * ({@link #renewFor}), or right after the login created it, before any other request can know its
 * id ({@link #hold}). It stays until the entry ends, so a session that holds a user's login is
 * always among the user's sessions, whatever other requests do with it.
 */
final class SessionEntry {

    private final Sessions owner;
    private final Map<AttributeKey<?>, Object> attributes = new HashMap<>();
    private Sessions.Key key;
    private long generation;
    private Instant lastUsed;
    /** Its place in the owner's order of uses: a larger number was used later. */
    private long useOrder;
    /** The user the session is logged in for ({@link Sessions#renewFor}), or {@code null}; cleared only when it ends. */
    private String user;
    /** How many logins are being handled with the entry ({@link #hold} minus {@link #release}). */
    private int logins;
    private boolean ended;

    /** A new entry under {@code key}, used at {@code now}; the caller puts it in the store. */
    SessionEntry(Sessions owner, Sessions.Key key, Instant now) {
        this.owner = owner;
        this.key = key;
        this.lastUsed = now;
        this.useOrder = owner.nextUse();
    }

    /** A view for the request that created this entry. */
    synchronized Session firstView() {
        return new Session(this, generation);
    }

    /**
     * The filter found this entry under {@code via}: a use, which moves the expiry forward.
     *
     * @return a view for the request, or {@code null} if the entry has ended, has expired (it
     *         ends now) or is no longer stored under {@code via} (renewed meanwhile)
     */
    synchronized Session use(Sessions.Key via, Instant now) {
        if (!alive(now) || !key.equals(via)) {
            return null;
        }
        touch(now);
        return new Session(this, generation);
    }

    /**
     * Whether {@code view} still sees the session: not ended, not expired (an expired entry
     * ends here) and not renewed by another request.
     */
    synchronized boolean visible(Session view) {
        return alive(owner.now()) && view.generation == generation;
    }

    synchronized Optional<Object> get(Session view, AttributeKey<?> name) {
        return visible(view) ? Optional.ofNullable(attributes.get(name)) : Optional.empty();
    }

    synchronized void put(Session view, AttributeKey<?> name, Object value) {
        if (!visible(view)) {
            throw new IllegalStateException("the session has been invalidated, has expired or has a new id that"
                    + " this request does not know");
        }
        attributes.put(name, value);
    }

    synchronized void remove(Session view, AttributeKey<?> name) {
        if (visible(view)) {
            attributes.remove(name);
        }
    }

    /**
     * Gives the entry a new id: stored under the new key first, then removed from the old one,
     * both under this lock, so the old id stops working when this returns and a lookup that
     * raced with it finds the key changed. Attributes stay; the generation goes up, so other
     * requests' views of the entry end; {@code view} follows.
     *
     * @return the new id, or {@code null} if {@code view} no longer sees the session
     */
    synchronized String renew(Session view) {
        if (!visible(view)) {
            return null;
        }
        var now = owner.now();
        while (true) {
            var id = owner.newId();
            var next = Sessions.keyOf(id);
            if (owner.claim(next, this)) {
                owner.unlink(key, this);
                key = next;
                generation++;
                view.generation = generation;
                touch(now);
                return id;
            }
        }
    }

    /** Ends the entry if {@code view} still sees it; whether it did. */
    synchronized boolean invalidate(Session view) {
        if (!visible(view)) {
            return false;
        }
        end();
        return true;
    }

    /**
     * The cleanup's step: ends the entry if it has expired at {@code now}.
     *
     * @return when the entry expires if it stays unused, or {@code null} if it has ended
     */
    synchronized Instant expireIfDue(Instant now) {
        return alive(now) ? lastUsed.plus(owner.idleTimeout()) : null;
    }

    /** The user {@code view}'s session is logged in for, or {@code null} (none, or not visible). */
    synchronized String user(Session view) {
        return visible(view) ? user : null;
    }

    /**
     * {@link #renew} for a login of {@code name}, in one step: the entry gets a new id, is logged
     * in for {@code name} and is held by the login ({@link #hold}), so that the limit per user
     * cannot end it between the renewal and the hold.
     *
     * @return the new id, or {@code null} if {@code view} no longer sees the session (then
     *         nothing changed and nothing is held)
     */
    synchronized String renewFor(Session view, String name) {
        var id = renew(view);
        if (id != null) {
            hold(view, name);
        }
        return id;
    }

    /**
     * Holds the entry for a login of {@code name} until {@link #release}: it is logged in for
     * {@code name} (counted among the user's sessions) and the limit per user does not end it
     * while a login is being handled with it.
     *
     * @return whether {@code view} still sees the session; if not, nothing changed and nothing
     *         is held
     */
    synchronized boolean hold(Session view, String name) {
        if (!visible(view)) {
            return false;
        }
        if (!name.equals(user)) {
            if (user != null) {
                owner.leave(user, this); // not through renewFor, which ends another user's session instead
            }
            owner.join(name, this);
            user = name;
        }
        logins++;
        return true;
    }

    /** A login that {@link #hold held} the entry is done with it. */
    synchronized void release() {
        logins--;
    }

    /** Whether a login is being handled with the entry: then the limit per user does not end it. */
    synchronized boolean held() {
        return logins > 0;
    }

    /**
     * The entry's place in the owner's order of uses if it is live and logged in for
     * {@code name}; otherwise -1 (an expired entry ends here).
     */
    synchronized long useOrderAs(String name) {
        return alive(owner.now()) && name.equals(user) ? useOrder : -1;
    }

    /**
     * Ends the entry as {@code name}'s least recently used session, unless it has been used
     * since {@code seen} or a login is being handled with it: then it is no longer the one to
     * end, and the caller looks again. An entry that has ended or is not logged in for
     * {@code name} is left as it is.
     */
    synchronized void endIfUnusedSince(String name, long seen) {
        if (alive(owner.now()) && name.equals(user) && useOrder == seen && logins == 0) {
            end();
        }
    }

    /** The number of attributes, 0 once ended. */
    synchronized int size() {
        return attributes.size();
    }

    /** Whether the entry is live at {@code now}; an expired entry ends here. */
    private boolean alive(Instant now) {
        if (ended) {
            return false;
        }
        if (!now.isBefore(lastUsed.plus(owner.idleTimeout()))) {
            end();
            return false;
        }
        return true;
    }

    private void touch(Instant now) {
        if (now.isAfter(lastUsed)) {
            lastUsed = now;
        }
        useOrder = owner.nextUse();
    }

    private void end() {
        ended = true;
        owner.release(key, this);
        if (user != null) {
            owner.leave(user, this);
            user = null;
        }
        attributes.clear();
    }

    /** No id, no values. */
    @Override
    public String toString() {
        return "SessionEntry";
    }
}
