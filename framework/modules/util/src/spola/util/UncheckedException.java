package spola.util;

import java.io.Serial;

/**
 * A checked exception wrapped by {@link Unchecked}. The original is always the
 * {@linkplain #getCause() cause}, and the message is the original's {@code toString()}.
 * {@link java.io.IOException}s are wrapped in {@link java.io.UncheckedIOException} instead.
 */
public final class UncheckedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Wraps a checked exception.
     *
     * @param cause the checked exception
     */
    public UncheckedException(Exception cause) {
        super(String.valueOf(cause), cause);
    }

    /**
     * The checked exception that was wrapped.
     *
     * @return the cause
     */
    @Override
    public synchronized Exception getCause() {
        return (Exception) super.getCause();
    }
}
