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

    /** How a fixture is prepared and which check runs on it. */
    enum Kind {
        /** Source check on the fixture's {@code .java} files (unnamed module, not compiled). */
        SOURCE,
        /** Fixture's {@code .java} files are compiled to a scratch directory; bytecode check on the result. */
        BYTECODE,
        /** Fixture's {@code src/} is compiled as modules to a scratch directory; dependency check on the result. */
        DEPENDENCIES,
        /** Each path in the fixture's {@code tree.txt} is created as an empty file in a scratch directory; file check on it. */
        FILES
    }
}
