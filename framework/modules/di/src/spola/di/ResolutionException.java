package spola.di;

import java.io.Serial;
import java.util.List;

/**
 * A component could not be fetched or created. The message starts with the dependency chain
 * being resolved ({@code cannot resolve A -> B: ...}); a factory's exception is the cause.
 */
public final class ResolutionException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    ResolutionException(List<? extends Key<?>> path, String reason) {
        super("cannot resolve " + Key.chain(path) + ": " + reason);
    }

    ResolutionException(List<? extends Key<?>> path, String reason, Throwable cause) {
        super("cannot resolve " + Key.chain(path) + ": " + reason, cause);
    }
}
