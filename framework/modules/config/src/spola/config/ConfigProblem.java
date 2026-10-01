package spola.config;

import java.io.Serializable;
import java.util.Objects;

/**
 * One problem found while building a {@link Config} or binding it. A {@link ConfigException}
 * carries every problem found, not only the first. Messages never contain the value of a
 * sensitive key.
 */
public sealed interface ConfigProblem extends Serializable {

    /**
     * A one-line, human-readable description of the problem.
     *
     * @return the description
     */
    String message();

    /**
     * A source could not be read: a required file is missing or unreadable, a command-line
     * argument is malformed, two environment variables map to the same key, or a profile
     * source sets the profile key.
     *
     * @param source the source's description, e.g. {@code file /etc/app.properties}
     * @param detail what went wrong
     */
    record SourceError(String source, String detail) implements ConfigProblem {

        /**
         * Checks that both parts are present.
         *
         * @param source the source's description
         * @param detail what went wrong
         */
        public SourceError {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(detail, "detail");
        }

        @Override
        public String message() {
            return source + ": " + detail;
        }
    }

    /**
     * A placeholder in the value of {@code key} could not be resolved: a cycle (the detail
     * shows the whole chain, {@code a -> b -> a}), a reference to a key that has no value and
     * no default, or malformed placeholder syntax.
     *
     * @param key the key whose value contains the placeholder
     * @param source the description of the source the value came from
     * @param detail what went wrong
     */
    record PlaceholderError(String key, String source, String detail) implements ConfigProblem {

        /**
         * Checks that every part is present.
         *
         * @param key the key whose value contains the placeholder
         * @param source the description of the source the value came from
         * @param detail what went wrong
         */
        public PlaceholderError {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(detail, "detail");
        }

        @Override
        public String message() {
            return "'" + key + "' (from " + source + "): " + detail;
        }
    }

    /**
     * A key that a binding requires has no value in any source.
     *
     * @param key the full key, prefix included
     * @param expectedType the type the binding asked for, e.g. {@code int} or {@code Duration}
     */
    record MissingKey(String key, String expectedType) implements ConfigProblem {

        /**
         * Checks that both parts are present.
         *
         * @param key the full key
         * @param expectedType the type the binding asked for
         */
        public MissingKey {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(expectedType, "expectedType");
        }

        @Override
        public String message() {
            return "missing key '" + key + "' (expected " + expectedType + ")";
        }
    }

    /**
     * A value could not be converted to the type a binding asked for.
     *
     * @param key the full key, prefix included
     * @param expectedType the type the binding asked for
     * @param source the description of the source the value came from
     * @param value the value as written, or {@link Config#MASK} if the key is sensitive
     * @param detail why the conversion failed; empty if the key is sensitive, since a
     *     converter's message may repeat the value
     */
    record InvalidValue(String key, String expectedType, String source, String value, String detail)
            implements ConfigProblem {

        /**
         * Checks that every part is present.
         *
         * @param key the full key
         * @param expectedType the type the binding asked for
         * @param source the description of the source the value came from
         * @param value the value as written, or the mask
         * @param detail why the conversion failed, or empty
         */
        public InvalidValue {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(expectedType, "expectedType");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(detail, "detail");
        }

        @Override
        public String message() {
            return "invalid value for '" + key + "' from " + source + ": expected " + expectedType
                    + ", got '" + value + "'" + (detail.isEmpty() ? "" : " (" + detail + ")");
        }
    }
}
