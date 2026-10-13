package spola.web;

import java.util.Optional;
import spola.http.Response;

/**
 * The session state of one request, which the session filter puts on the request as an
 * attribute under a key only {@link Sessions} has. It holds the request's session (a
 * {@link Session} view, or none) and what the response must say about the cookie: the id of a
 * session created or renewed during the request, or that the cookie is to be removed. Only the
 * final outcome is sent, as one {@code Set-Cookie}. The new id lives here only until the filter
 * has built the response.
 *
 * <p>Synchronized, since a {@link WebRequest} may be handed to other threads.
 */
final class SessionState {

    private final Sessions owner;
    private Session session;
    private String issued;
    private boolean cleared;
    private boolean finished;

    /** @param session the session the filter found for the request, or {@code null} */
    SessionState(Sessions owner, Session session) {
        this.owner = owner;
        this.session = session;
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
     * The response with the outcome for the cookie: the new id, the removal, or nothing.
     * A response that gets a {@code Set-Cookie} here gets {@code Cache-Control: no-store} too,
     * unless it has a {@code Cache-Control}.
     */
    synchronized Response finish(Response response) {
        finished = true;
        ResponseCookie cookie = null;
        if (issued != null) {
            cookie = owner.sessionCookie(issued);
            issued = null;
        } else if (cleared) {
            cookie = owner.removalCookie();
            cleared = false;
        }
        if (cookie == null) {
            return response;
        }
        var withCookie = Responses.withCookie(response, cookie);
        return withCookie.headers().contains("Cache-Control") ? withCookie
                : withCookie.withHeader("Cache-Control", "no-store");
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
