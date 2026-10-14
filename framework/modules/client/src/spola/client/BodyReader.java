package spola.client;

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

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
     * @param declared the {@code Content-Length}, or -1
     */
    record Body(byte[] bytes, int length, Ending ending, long declared) {
    }

    /** The largest buffer allocated before the bytes arrive. */
    private static final int INITIAL_MAX = 64 << 10;

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
        long declared = contentLength(info.headers());
        int limit = success ? maxResponseSize : Math.min(RestClient.MAX_ERROR_BODY, maxResponseSize);
        var created = new Collector(limit, success, declared);
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

    /** The {@code Content-Length}, or -1 when it is missing or not a single number. */
    private static long contentLength(HttpHeaders headers) {
        var values = headers.allValues("Content-Length");
        if (values.size() != 1) {
            return -1;
        }
        var value = values.getFirst();
        if (value.isEmpty() || value.length() > 18 || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return -1;
        }
        return Long.parseLong(value);
    }

    /** The subscriber for one body. */
    private final class Collector implements HttpResponse.BodySubscriber<Body> {

        private final int limit;
        private final boolean success;
        private final long declared;
        private final CompletableFuture<Body> result = new CompletableFuture<>();
        private Flow.Subscription subscription; // guarded by this
        private boolean done; // guarded by this
        private byte[] bytes;
        private int length;

        Collector(int limit, boolean success, long declared) {
            this.limit = limit;
            this.success = success;
            this.declared = declared;
            // grows as bytes arrive: a Content-Length alone never makes the client allocate much
            long expected = declared >= 0 ? Math.min(declared, limit) : Math.min(limit, 8_192);
            this.bytes = new byte[(int) Math.max(16, Math.min(expected, INITIAL_MAX))];
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
                return;
            }
            if (declared > maxResponseSize) {
                finish(Ending.DECLARED_TOO_LARGE);
                return;
            }
            if (limit == 0) { // never: maxResponseSize is at least 1
                finish(Ending.CUT);
                return;
            }
            s.request(Long.MAX_VALUE); // the limit bounds what is kept; cancelling stops the rest
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            synchronized (this) {
                if (done) {
                    return;
                }
            }
            for (var buffer : buffers) {
                int room = limit - length;
                int take = Math.min(room, buffer.remaining());
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
            synchronized (this) {
                if (done) {
                    return;
                }
                done = true;
                subscription = null;
            }
            result.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            synchronized (this) {
                if (done) {
                    return;
                }
                done = true;
                subscription = null;
            }
            result.complete(new Body(bytes, length, Ending.COMPLETE, declared));
        }

        /** Stops reading: completes the body and cancels the subscription (the connection closes). */
        private void finish(Ending ending) {
            Flow.Subscription toCancel;
            synchronized (this) {
                if (done) {
                    return;
                }
                done = true;
                toCancel = subscription;
                subscription = null;
            }
            result.complete(new Body(bytes, length, ending, declared));
            if (toCancel != null) {
                toCancel.cancel();
            }
        }

        /** Cancels from another thread; the caller has given up on the result. */
        void abort() {
            Flow.Subscription toCancel;
            synchronized (this) {
                if (done) {
                    return;
                }
                done = true;
                toCancel = subscription;
                subscription = null;
            }
            result.cancel(false);
            if (toCancel != null) {
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
