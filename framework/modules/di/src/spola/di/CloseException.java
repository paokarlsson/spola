package spola.di;

import java.io.Serial;
import java.util.List;

/**
 * Closing one or more components failed. Every component was still closed (or attempted);
 * the failures are the {@linkplain #getSuppressed() suppressed} exceptions, in the order the
 * components were closed, and the message names each component.
 */
public final class CloseException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    CloseException(String what, List<Lifecycle.Failure> failures) {
        super(message(what, failures));
        failures.forEach(failure -> addSuppressed(failure.error()));
    }

    private static String message(String what, List<Lifecycle.Failure> failures) {
        var text = new StringBuilder("closing " + what + ": " + failures.size() + " component"
                + (failures.size() == 1 ? "" : "s") + " failed to close:");
        for (var failure : failures) {
            text.append("\n  - ").append(failure.key()).append(": ").append(failure.error());
        }
        return text.toString();
    }
}
