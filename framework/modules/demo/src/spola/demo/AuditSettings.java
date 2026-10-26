package spola.demo;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * The audit trail (keys {@code audit.*}).
 *
 * @param file where the audit trail is written ({@code audit.file}); empty: next to the order
 *     file, with {@code .audit} added to its name
 * @param secret the key that signs the audit trail ({@code audit.secret}); sensitive, never
 *     shown
 */
record AuditSettings(Optional<Path> file, String secret) {

    AuditSettings {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(secret, "secret");
    }

    /** Like a record's own {@code toString}, but without the secret. */
    @Override
    public String toString() {
        return "AuditSettings[file=" + file + ", secret=***]";
    }
}
