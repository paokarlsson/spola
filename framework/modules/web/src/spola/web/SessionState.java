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
 * <p>After {@link #renewFor} it also holds the user the session is for and their limit: the
 * session is counted among the user's sessions only when the filter builds the response, so
 * that no other login of the same user can end it in the meantime (see {@link Sessions#renewFor}).
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
    /** The user {@link #renewFor} renewed {@link #session} for, or {@code null}; counted at {@link #finish}. */
    private String pendingUser;
    /** The most sessions {@link #pendingUser} may have. */
    private int pendingLimit;

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
     * {@link #renew} for {@code user}: a session of another user ends first and a new, empty one
     * is created; the user's least recently used sessions end so that, with this one, they have
     * at most {@code limit}; and this one is counted among them when the response is built.
     */
    synchronized Session renewFor(String user, int limit) {
        requireOpen("renewFor");
        var current = current();
        if (current != null) {
            var before = pendingUser != null ? pendingUser : current.user();
            if (before != null && !before.equals(user)) {
                invalidate(); // another user's session: its attributes are not the new user's
            } else {
                current.entry().leave(current); // not among the user's sessions until the response is built
            }
        }
        owner.limit(user, limit - 1, null); // room for this one, also under maxSessions
        var renewed = renew();
        pendingUser = user;
        pendingLimit = limit;
        return renewed;
    }

    synchronized void invalidate() {
        var current = current();
        if (current == null) {
            return;
        }
        current.invalidate();
        session = null;
        issued = null;
        pendingUser = null;
        cleared = !finished;
    }

    /**
     * The response with the outcome for the cookie: the new id, the removal, or nothing, with
     * {@code Secure} also when the request the filter got was {@code https}.
     * A response that gets a {@code Set-Cookie} here never stays storable by a shared cache
     * ({@link CacheControl#withoutSharedCache}). A session renewed with {@link #renewFor} is
     * counted among its user's sessions now, and their least recently used ones beyond the
     * limit end.
     */
    synchronized Response finish(Response response) {
        finished = true;
        countPending();
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

    /** Counts the session that {@link #renewFor} renewed among its user's sessions, and keeps them within the limit. */
    private void countPending() {
        var user = pendingUser;
        pendingUser = null;
        var current = current();
        if (user != null && current != null && current.entry().join(current, user)) {
            owner.limit(user, pendingLimit, current.entry());
        }
    }

    /**
     * The rest of the chain threw (a request body failure or an {@link Error}), so no response
     * will carry a new id: a session created or renewed during this request can never be
     * reached, and it ends now.
     */
    synchronized void abandon() {
        finished = true;
        if (issued != null && session != null) {
            session.invalidate();
            session = null;
        }
        issued = null;
        cleared = false;
        pendingUser = null;
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
        pendingUser = null;
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
