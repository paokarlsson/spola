package spola.client;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The cause of a {@link RestClientTransportException}. A failure from {@code java.net.http} can
 * quote what the server sent: a server that echoes the request makes the JDK's "Invalid status
 * line" quote the request line with its query (or the body, if that is what comes back first).
 * Such a failure is replaced by a stand-in without its text, so that no header value, query or
 * body reaches a message or a printed stack trace.
 */
final class Causes {

    /** How many throwables (causes and suppressed) are looked at; past that the failure is replaced. */
    private static final int MAX_CHECKED = 64;

    private Causes() {
    }

    /**
     * {@code failure} when no text in it or in what it carries (causes, suppressed) contains one
     * of {@code secrets}; otherwise an {@link IOException} that names the failure's class and has
     * its stack trace, but not its text.
     */
    static Throwable safe(Throwable failure, List<String> secrets) {
        if (isClean(failure, secrets)) {
            return failure;
        }
        var standIn = new IOException(failure.getClass().getName()
                + " (its message is left out: it quotes a header value, the query or the body of the request)");
        standIn.setStackTrace(failure.getStackTrace());
        return standIn;
    }

    /** {@code text} for a message: the failure's class and, if clean, its message (ASCII, short). */
    static String describe(Throwable failure, boolean clean) {
        var message = clean ? failure.getMessage() : null;
        var name = failure.getClass().getName();
        return message == null || message.isBlank() ? name : name + ": " + Text.limit(Text.ascii(message), 300);
    }

    static boolean isClean(Throwable failure, List<String> secrets) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        var queue = new ArrayDeque<Throwable>();
        queue.add(failure);
        while (!queue.isEmpty()) {
            var next = queue.poll();
            if (!seen.add(next)) {
                continue;
            }
            if (seen.size() > MAX_CHECKED) {
                return false;
            }
            String text;
            try {
                text = next.getClass().getName() + " " + next.getMessage() + " " + next.getLocalizedMessage() + " " + next;
            } catch (RuntimeException e) { // a broken toString: treat as unsafe
                return false;
            }
            for (var secret : secrets) {
                if (!secret.isEmpty() && text.contains(secret)) {
                    return false;
                }
            }
            if (next.getCause() != null) {
                queue.add(next.getCause());
            }
            Collections.addAll(queue, next.getSuppressed());
        }
        return true;
    }
}
