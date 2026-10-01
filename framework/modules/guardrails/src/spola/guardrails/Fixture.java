package spola.guardrails;

import java.util.List;

/**
 * A deliberately broken example under {@code modules/guardrails/fixtures/<name>/} and the
 * exact violations guardrails must report for it.
 *
 * @param name     directory name below the fixtures directory
 * @param kind     which check the fixture exercises, and how it is prepared
 * @param expected every violation the check must report, no more and no less
 */
record Fixture(String name, Kind kind, List<Violation> expected) {

    /**
     * Whether the self-test reads {@code file} (relative to the fixture directory, {@code /}
     * as separator) when it runs this fixture. Any other file in the fixture directory would
     * be excluded from the main check without being checked by the self-test, so it is an error.
     */
    boolean uses(String file) {
        return switch (kind) {
            case SOURCE, BYTECODE -> file.endsWith(".java");
            case DEPENDENCIES -> file.startsWith("src/") && file.endsWith(".java");
            case FILES -> file.equals("tree.txt");
        };
    }

    /** How a fixture is prepared and which check runs on it. */
    enum Kind {
        /** Source check on the fixture's {@code .java} files (unnamed module, not compiled). */
        SOURCE,
        /**
         * Source check on the fixture's {@code .java} files, which are then compiled to a
         * scratch directory; bytecode check on the result.
         */
        BYTECODE,
        /**
         * Source check on the fixture's {@code src/} (one directory per module), which is then
         * compiled as modules to a scratch directory; dependency and bytecode check on the result.
         */
        DEPENDENCIES,
        /** Each path in the fixture's {@code tree.txt} is created as an empty file in a scratch directory; file check on it. */
        FILES
    }
}
