package spola.web;

import java.util.Optional;
import spola.http.Response;

/**
 * The session state of one request, which the session filter puts on the request as an
 * attribute under a key only {@link Sessions} has. It holds the request's session (a
 * {@link Session} view, or none) and what the response must say about the cookie: the id of a
 * session created or renewed during the request, or that the cookie is to be removed. Only the
 * final outcome is sent, as one {@code Set-Cookie}, with {@code Secure} also when the scheme of
 * the request the filter got was {@code https} (fixed when the filter starts; nothing later in
 * the chain changes it). The new id lives here only until the filter has built the response.
 *
 * <p>After {@link #renewFor} it also holds the session's entry for the login until the filter
 * builds the response (or abandons the request): the session counts among the user's sessions at
 * once, but no other login of the same user can end it in the meantime, and the user's limit is
 * applied again when the hold ends (see {@link Sessions#renewFor}).
 *
 * <p>Synchronized, since a {@link WebRequest} may be handed to other threads.
 */
final class SessionState {

    private final Sessions owner;
    /** Whether the scheme of the request the filter got is {@code https}, so that the cookie gets {@code Secure}. */
    private final boolean https;
    private Session session;
    private String issued;
    private boolean cleared;
    private boolean finished;
    /** The entry {@link #renewFor} holds for a login until the response is built, or {@code null}. */
    private SessionEntry held;
    /** The user {@link #held} is held for. */
    private String heldUser;
    /** The most sessions {@link #heldUser} may have. */
    private int heldLimit;

    /**
     * @param session the session the filter found for the request, or {@code null}
     * @param https   whether the scheme of the request the filter got is {@code https}
     */
    SessionState(Sessions owner, Session session, boolean https) {
        this.owner = owner;
        this.session = session;
        this.https = https;
    }

    synchronized Optional<Session> find() {
        return Optional.ofNullable(current());
    }

    synchronized Session getOrCreate() {
        var current = current();
        return current != null ? current : create("getOrCreate");
    }

    synchronized Session renew() {
        requireOpen("renew");
        var current = current();
        if (current != null) {
            var id = current.renew();
            if (id != null) {
                issued = id;
                cleared = false;
                return current;
            }
            session = null;
        }
        return create("renew");
    }

    /**
     * {@link #renew} for a login of {@code user}: a session of another user ends first and a new,
     * empty one is created; the session is held for the login until the response is built; and
     * the user's least recently used sessions end so that, with this one, they have at most
     * {@code limit}, before a new session is created and again when the hold ends.
     */
    synchronized Session renewFor(String user, int limit) {
        requireOpen("renewFor");
        var current = current();
        var before = current == null ? null : current.user();
        if (before != null && !before.equals(user)) {
            invalidate(); // another user's session: its attributes are not the new user's
        } else if (current != null) {
            var id = current.entry().renewFor(current, user); // renewed and held in one step
            if (id != null) {
                issued = id;
                cleared = false;
                return recordHold(current, user, limit);
            }
            session = null; // renewed or ended by another request meanwhile
        }
        owner.limit(user, limit - 1, null); // room for a new session, also under maxSessions
        var created = create("renewFor");
        return created.entry().hold(created, user) ? recordHold(created, user, limit) : created;
    }

    /**
     * Notes that this request holds {@code view}'s entry for {@code user}'s login until
     * {@link #release}. An earlier hold of the request ends at once: its entry is this one, or it
     * has ended (another user's session, or one that was invalidated).
     */
    private Session recordHold(Session view, String user, int limit) {
        var previous = held;
        held = view.entry();
        heldUser = user;
        heldLimit = limit;
        if (previous != null) {
            previous.release();
        }
        return view;
    }

    synchronized void invalidate() {
        var current = current();
        if (current == null) {
            return;
        }
        current.invalidate();
        session = null;
        issued = null;
        cleared = !finished;
    }

    /**
     * The response with the outcome for the cookie: the new id, the removal, or nothing, with
     * {@code Secure} also when the request the filter got was {@code https}.
     * A response that gets a {@code Set-Cookie} here never stays storable by a shared cache
     * ({@link CacheControl#withoutSharedCache}). A session held for a login ({@link #renewFor})
     * is released now, and the user's least recently used sessions beyond the limit end.
     */
    synchronized Response finish(Response response) {
        finished = true;
        release();
        ResponseCookie cookie = null;
        if (issued != null) {
            cookie = owner.sessionCookie(issued, https);
            issued = null;
        } else if (cleared) {
            cookie = owner.removalCookie(https);
            cleared = false;
        }
        if (cookie == null) {
            return response;
        }
        return CacheControl.withoutSharedCache(Responses.withCookie(response, cookie));
    }

    /**
     * Ends the hold of {@link #renewFor}, if any, and keeps the user within the limit with the
     * session: it may have joined the user's sessions without a new one being created (an
     * anonymous session), and logins of the same user that were handled at the same time could
     * not end each other's sessions.
     */
    private void release() {
        var entry = held;
        if (entry == null) {
            return;
        }
        held = null;
        entry.release();
        owner.limit(heldUser, heldLimit, entry);
    }

    /**
     * The rest of the chain threw (a request body failure or an {@link Error}), so no response
     * will carry a new id: a session created or renewed during this request can never be
     * reached, and it ends now. A hold for a login ends too.
     */
    synchronized void abandon() {
        finished = true;
        if (issued != null && session != null) {
            session.invalidate();
            session = null;
        }
        issued = null;
        cleared = false;
        release();
    }

    /** The request's session if it is still visible to the request, else {@code null}. */
    private Session current() {
        if (session != null && !session.visible()) {
            session = null;
        }
        return session;
    }

    private Session create(String method) {
        requireOpen(method);
        var created = owner.create();
        session = created.session();
        issued = created.id();
        cleared = false;
        return session;
    }

    private void requireOpen(String method) {
        if (finished) {
            throw new IllegalStateException("Sessions." + method + " was called after the response of the request was"
                    + " built; call it while the request is handled");
        }
    }

    /** Never the id. */
    @Override
    public String toString() {
        return "SessionState";
    }
}
