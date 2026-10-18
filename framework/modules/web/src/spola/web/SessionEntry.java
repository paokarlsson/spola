package spola.web;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One session in the store of a {@link Sessions}: its attributes, the key it is stored under
 * (the SHA-256 of its id, never the id), when it was last used, its generation, which
 * {@link #renew} raises, and the user whose sessions it is counted among
 * ({@link Sessions#renewFor}). Every field is guarded by the entry's lock; the locks of the
 * store and of the index of users' sessions are taken inside it, never the other way round.
 *
 * <p>A {@link Session} is a view of an entry for one request, with the generation the request
 * knows. Once another request renews the entry, the views with the old generation see an ended
 * session: a request that came with the old id loses the session at once, also when it was
 * already being handled. An entry that ended (invalidated or expired) never comes back, and it
 * leaves the index of its user's sessions when it ends.
 */
final class SessionEntry {

    private final Sessions owner;
    private final Map<AttributeKey<?>, Object> attributes = new HashMap<>();
    private Sessions.Key key;
    private long generation;
    private Instant lastUsed;
    /** When it was last used, in the owner's order of uses: a larger number was used later. */
    private long lastUse;
    /** The user whose sessions this entry is counted among, or {@code null}. */
    private String user;
    private boolean ended;

    /** A new entry under {@code key}, used at {@code now}; the caller puts it in the store. */
    SessionEntry(Sessions owner, Sessions.Key key, Instant now) {
        this.owner = owner;
        this.key = key;
        this.lastUsed = now;
        this.lastUse = owner.nextUse();
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

    /** The user whose sessions {@code view}'s session is counted among, or {@code null} (none, or not visible). */
    synchronized String user(Session view) {
        return visible(view) ? user : null;
    }

    /**
     * Takes the entry out of its user's sessions while a login with it is handled
     * ({@link Sessions#renewFor}), so that no other login of the user can end it as the user's
     * oldest before the login has stored what it needs; {@link #join} puts it back.
     */
    synchronized void leave(Session view) {
        if (visible(view) && user != null) {
            owner.leave(user, this);
            user = null;
        }
    }

    /**
     * Counts the entry among {@code name}'s sessions (and no longer among another user's).
     *
     * @return whether {@code view} still sees the session; if not, nothing changed
     */
    synchronized boolean join(Session view, String name) {
        if (!visible(view)) {
            return false;
        }
        if (!name.equals(user)) {
            if (user != null) {
                owner.leave(user, this);
            }
            owner.join(name, this);
            user = name;
        }
        return true;
    }

    /**
     * When the entry was last used, in the owner's order of uses, if it is live and counted
     * among {@code name}'s sessions; otherwise -1 (an expired entry ends here).
     */
    synchronized long lastUseAs(String name) {
        return alive(owner.now()) && name.equals(user) ? lastUse : -1;
    }

    /**
     * Ends the entry as {@code name}'s least recently used session, unless it has been used
     * since {@code seen} (then it is no longer the least recently used, and the caller looks
     * again). An entry that has ended or is no longer counted among {@code name}'s sessions is
     * left as it is.
     *
     * @return {@code false} if it was used since {@code seen}
     */
    synchronized boolean endIfUnusedSince(String name, long seen) {
        if (!alive(owner.now()) || !name.equals(user)) {
            return true;
        }
        if (lastUse != seen) {
            return false;
        }
        end();
        return true;
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
        lastUse = owner.nextUse();
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
