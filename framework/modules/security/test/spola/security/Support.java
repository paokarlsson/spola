package spola.security;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Shared test helpers: a fast hasher, a counting hasher, a recording logger and hex. */
final class Support {

    private Support() {
    }

    /** The settings the tests use instead of the slow defaults. */
    static Pbkdf2PasswordHasher fast() {
        return Pbkdf2PasswordHasher.of(10_000, 16, 32);
    }

    static byte[] hex(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** Wraps a hasher and counts the calls of each method. */
    static final class CountingHasher implements PasswordHasher {
        final PasswordHasher delegate;
        final AtomicInteger hashCalls = new AtomicInteger();
        final AtomicInteger matchesCalls = new AtomicInteger();
        final AtomicInteger needsRehashCalls = new AtomicInteger();

        CountingHasher(PasswordHasher delegate) {
            this.delegate = delegate;
        }

        @Override
        public String hash(CharSequence password) {
            hashCalls.incrementAndGet();
            return delegate.hash(password);
        }

        @Override
        public boolean matches(CharSequence password, String encoded) {
            matchesCalls.incrementAndGet();
            return delegate.matches(password, encoded);
        }

        @Override
        public boolean needsRehash(String encoded) {
            needsRehashCalls.incrementAndGet();
            return delegate.needsRehash(encoded);
        }

        void reset() {
            hashCalls.set(0);
            matchesCalls.set(0);
            needsRehashCalls.set(0);
        }
    }

    /** One log record: level and message. */
    record Entry(System.Logger.Level level, String message, Throwable thrown) {
    }

    /** A System.Logger that keeps every record, at every level. */
    static final class RecordingLogger implements System.Logger {
        final List<Entry> entries = new ArrayList<>();

        @Override
        public String getName() {
            return "spola.security";
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public synchronized void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
            entries.add(new Entry(level, msg, thrown));
        }

        @Override
        public synchronized void log(Level level, ResourceBundle bundle, String format, Object... params) {
            entries.add(new Entry(level, params == null || params.length == 0 ? format
                    : java.text.MessageFormat.format(format, params), null));
        }

        @Override
        public void log(Level level, Supplier<String> supplier) {
            log(level, (ResourceBundle) null, supplier.get(), (Throwable) null);
        }

        synchronized List<Entry> entries() {
            return List.copyOf(entries);
        }
    }
}
