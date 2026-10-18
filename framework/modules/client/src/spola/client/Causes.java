package spola.client;

import java.io.IOException;
import java.net.SocketException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SSLException;

/**
 * The cause of a {@link RestClientTransportException}. A failure from {@code java.net.http} can
 * quote what the server sent: a bad status line or header ({@code ProtocolException}), the part
 * of a status line or header that came before the connection broke (an {@code IOException}), a
 * {@code Content-Length} that is not a number. A server that sends the request back makes those
 * texts quote the request: its line with the query, its headers, its body. So only the failures
 * whose text comes from the JDK or the operating system keep it; every other one is replaced by
 * a copy without text, and the message names only its classes.
 */
final class Causes {

    /** How many throwables (causes and suppressed) are searched for a value; past that, the text is not shown. */
    private static final int MAX_CHECKED = 64;

    /** How many causes of a failure are copied. */
    private static final int MAX_COPIED = 16;

    /** The longest text of a failure in a message, after escaping. */
    private static final int MAX_TEXT = 300;

    private Causes() {
    }

    /**
     * Whether the text of {@code failure} may be shown: its text comes from the JDK or the
     * operating system and never from the server (a socket error such as a refused connection,
     * TLS, the connect timeout), and neither it nor anything it carries (causes, suppressed)
     * holds one of {@code secrets}.
     */
    static boolean textIsSafe(Throwable failure, List<String> secrets) {
        boolean fromThisSide = failure instanceof SocketException || failure instanceof SSLException
                || failure instanceof HttpTimeoutException;
        return fromThisSide && quotesNone(failure, secrets);
    }

    /** Whether no text in {@code failure} or in what it carries contains one of {@code secrets}. */
    static boolean quotesNone(Throwable failure, List<String> secrets) {
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
                text = next.getMessage() + " " + next.getLocalizedMessage() + " " + next;
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

    /**
     * The chain of causes of {@code failure} without any text: for each of the first
     * {@value #MAX_COPIED}, an {@link IOException} that names its class and has its stack trace.
     * Suppressed throwables are left out.
     */
    static Throwable withoutText(Throwable failure) {
        var chain = chain(failure);
        Throwable copy = null;
        for (int i = chain.size() - 1; i >= 0; i--) {
            var original = chain.get(i);
            var standIn = new IOException(original.getClass().getName()
                    + " (its text is left out: it can quote what the server sent)", copy);
            standIn.setStackTrace(original.getStackTrace());
            copy = standIn;
        }
        return copy;
    }

    /**
     * {@code failure} for a message: with {@code withText}, its class and text (ASCII, short);
     * otherwise its class and that of its root cause, if it has one.
     */
    static String describe(Throwable failure, boolean withText) {
        var name = failure.getClass().getName();
        if (withText) {
            var text = failure.getMessage();
            return text == null || text.isBlank() ? name : name + ": " + Text.limit(Text.ascii(text), MAX_TEXT);
        }
        var chain = chain(failure);
        return chain.size() == 1 ? name : name + ", caused by " + chain.getLast().getClass().getName();
    }

    /** {@code failure} and its causes, at most {@value #MAX_COPIED}, without repeats. */
    private static List<Throwable> chain(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        var chain = new ArrayList<Throwable>();
        for (var next = failure; next != null && chain.size() < MAX_COPIED && seen.add(next); next = next.getCause()) {
            chain.add(next);
        }
        return chain;
    }
}
