package spola.http;

import java.io.Serial;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Key or certificate material for {@link TlsOptions} could not be used: a file is missing or
 * unreadable, a password is wrong, an alias is unknown, a PEM file is malformed, or a private key
 * does not belong to its certificate. Thrown when the {@link TlsOptions}, {@link TlsIdentity} or
 * {@link TlsTrust} is created, never at the first connection.
 *
 * <p>The message names the file and what is wrong, and says how to fix it where that is known
 * (for example how to convert a key with {@code openssl}). It never holds a password or key
 * bytes; neither does the cause, which is only attached for I/O and keystore errors.
 */
public final class TlsMaterialException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The file the problem is in; not serialized ({@link Path} is not serializable). */
    private final transient Path file;

    TlsMaterialException(Path file, String message) {
        super(message);
        this.file = file;
    }

    TlsMaterialException(Path file, String message, Throwable cause) {
        super(message, cause);
        this.file = file;
    }

    /**
     * The file the problem is in.
     *
     * @return the file as it was given, or empty if the problem is not in one file (or after
     *         deserialization)
     */
    public Optional<Path> file() {
        return Optional.ofNullable(file);
    }
}
