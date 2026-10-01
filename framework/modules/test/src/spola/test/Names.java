package spola.test;

import java.time.Duration;
import java.util.Objects;

/** Validation shared by {@link TestCase} and {@link Suite}. */
final class Names {

    private Names() {
    }

    /** A name must be non-blank and on one line, so that every report line names one node. */
    static String check(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a test or suite name must not be blank");
        }
        if (name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("a test or suite name must not contain line breaks or other"
                    + " control characters: " + name.replaceAll("\\p{Cntrl}", "?"));
        }
        return name;
    }

    /** A timeout must be positive. */
    static Duration checkTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }
        return timeout;
    }

    /** A skip reason must be given, so that the report says why. */
    static String checkReason(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("a skip reason must not be blank");
        }
        return reason;
    }
}
