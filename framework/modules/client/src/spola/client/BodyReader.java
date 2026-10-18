package spola.client;

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

/**
 * Reads one response body within the limits: for 2xx up to {@code maxResponseSize} bytes, for
 * any other status up to {@link RestClient#MAX_ERROR_BODY} bytes (or {@code maxResponseSize} if
 * smaller). A {@code Content-Length} over {@code maxResponseSize} is refused before a byte is read.
 * Past a limit it cancels the subscription, which makes {@code java.net.http} close the
 * connection, so a body that never ends cannot hold the call. One instance per call;
 * {@link #abort()} cancels the body from the calling thread when the call times out or is
 * interrupted.
 */
final class BodyReader implements HttpResponse.BodyHandler<BodyReader.Body> {

    /** How the reading ended. */
    enum Ending {
        /** The whole body was read. */
        COMPLETE,
        /** A 2xx body went past {@code maxResponseSize}; reading stopped there. */
        TOO_LARGE,
        /** The {@code Content-Length} was over {@code maxResponseSize}; nothing was read. */
        DECLARED_TOO_LARGE,
        /** A body outside 2xx reached its limit; reading stopped there. */
        CUT
    }

    /**
     * What was read.
     *
     * @param bytes    the bytes (the array may be longer than {@code length})
     * @param length   how many bytes were read
     * @param ending   how the reading ended
     * @param declared the {@code Content-Length}: -1 when there is none (or it is not a single
     *                 number), {@code Long.MAX_VALUE} when it has more digits than a
     *                 {@code long} holds
     */
    record Body(byte[] bytes, int length, Ending ending, long declared) {

        /** Whether the body went on past what was read: reading stopped at a limit. */
        boolean cut() {
            return ending == Ending.TOO_LARGE || ending == Ending.CUT;
        }
    }

    /**
     * A response as it came back.
     *
     * @param status  the status
     * @param headers the headers
     * @param body    the body, as far as it was read
     */
    record Received(int status, HttpHeaders headers, Body body) {
    }

    /** The largest buffer allocated before the bytes arrive. */
    private static final int INITIAL_MAX = 64 << 10;

    /** The first buffer for a body without {@code Content-Length}. */
    private static final int INITIAL_UNKNOWN = 8 << 10;

    /** The smallest first buffer. */
    private static final int INITIAL_MIN = 16;

    private final int maxResponseSize;
    private volatile Collector collector;
    private volatile boolean aborted;

    BodyReader(int maxResponseSize) {
        this.maxResponseSize = maxResponseSize;
    }

    @Override
    public HttpResponse.BodySubscriber<Body> apply(HttpResponse.ResponseInfo info) {
        int status = info.statusCode();
        boolean success = status >= 200 && status <= 299;
        int limit = success ? maxResponseSize : Math.min(RestClient.MAX_ERROR_BODY, maxResponseSize);
        var created = new Collector(info, limit, success, contentLength(info.headers()));
        collector = created;
        if (aborted) {
            created.abort();
        }
        return created;
    }

    /** Cancels the body, if it is being read; one that starts later is cancelled at once. */
    void abort() {
        aborted = true;
        var current = collector;
        if (current != null) {
            current.abort();
        }
    }

    /**
     * The response, with nothing read, if its {@code Content-Length} was over
     * {@code maxResponseSize}. {@code java.net.http} fails such an exchange itself when the
     * length has more digits than a {@code long} holds, after this handler has seen the head.
     */
    Optional<Received> refusedUnread() {
        var current = collector;
        if (current == null || current.declared <= maxResponseSize) {
            return Optional.empty();
        }
        return Optional.of(new Received(current.info.statusCode(), current.info.headers(),
                new Body(new byte[0], 0, Ending.DECLARED_TOO_LARGE, current.declared)));
    }

    /**
     * The {@code Content-Length}: -1 when it is missing or not a single number of digits,
     * {@code Long.MAX_VALUE} when it has more digits than a {@code long} holds.
     */
    private static long contentLength(HttpHeaders headers) {
        var values = headers.allValues("Content-Length");
        if (values.size() != 1) {
            return -1;
        }
        var value = values.getFirst();
        if (value.isEmpty() || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return -1;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) { // only digits: too many for a long, so over any limit
            return Long.MAX_VALUE;
        }
    }

    /** The subscriber for one body. */
    private final class Collector implements HttpResponse.BodySubscriber<Body> {

        private final HttpResponse.ResponseInfo info;
        private final int limit;
        private final boolean success;
        private final long declared;
        private final CompletableFuture<Body> result = new CompletableFuture<>();
        private Flow.Subscription subscription; // guarded by this
        private boolean done; // guarded by this
        private byte[] bytes;
        private int length;

        Collector(HttpResponse.ResponseInfo info, int limit, boolean success, long declared) {
            this.info = info;
            this.limit = limit;
            this.success = success;
            this.declared = declared;
            // grows as bytes arrive: a Content-Length alone never makes the client allocate much
            long expected = declared >= 0 ? Math.min(declared, limit) : Math.min(limit, INITIAL_UNKNOWN);
            this.bytes = new byte[(int) Math.max(INITIAL_MIN, Math.min(expected, INITIAL_MAX))];
        }

        @Override
        public CompletionStage<Body> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            boolean cancel;
            synchronized (this) {
                cancel = done;
                if (!done) {
                    subscription = s;
                }
            }
            if (cancel) {
                s.cancel();
            } else if (declared > maxResponseSize) {
                finish(Ending.DECLARED_TOO_LARGE);
            } else {
                s.request(Long.MAX_VALUE); // the limit bounds what is kept; cancelling stops the rest
            }
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            synchronized (this) {
                if (done) {
                    return;
                }
            }
            for (var buffer : buffers) {
                int take = Math.min(limit - length, buffer.remaining());
                if (take > 0) {
                    ensureCapacity(length + take);
                    buffer.get(bytes, length, take);
                    length += take;
                }
                if (buffer.hasRemaining()) {
                    finish(success ? Ending.TOO_LARGE : Ending.CUT);
                    return;
                }
            }
            if (!success && length == limit) {
                finish(Ending.CUT); // the excerpt needs no more
            }
        }

        @Override
        public void onError(Throwable failure) {
            end(future -> future.completeExceptionally(failure), false);
        }

        @Override
        public void onComplete() {
            end(future -> future.complete(new Body(bytes, length, Ending.COMPLETE, declared)), false);
        }

        /** Stops reading: completes the body and cancels the subscription (the connection closes). */
        private void finish(Ending ending) {
            end(future -> future.complete(new Body(bytes, length, ending, declared)), true);
        }

        /** Cancels from another thread; the caller has given up on the result. */
        void abort() {
            end(future -> future.cancel(false), true);
        }

        /**
         * Ends the body once: the first call marks it done, settles the result with
         * {@code outcome} and, if {@code cancel}, cancels the subscription; later calls do
         * nothing.
         */
        private void end(Consumer<CompletableFuture<Body>> outcome, boolean cancel) {
            Flow.Subscription toCancel;
            synchronized (this) {
                if (done) {
                    return;
                }
                done = true;
                toCancel = subscription;
                subscription = null;
            }
            outcome.accept(result);
            if (cancel && toCancel != null) {
                toCancel.cancel();
            }
        }

        private void ensureCapacity(int needed) {
            if (needed > bytes.length) {
                long grown = Math.max((long) bytes.length * 2, needed);
                bytes = Arrays.copyOf(bytes, (int) Math.min(grown, limit));
            }
        }
    }
}
