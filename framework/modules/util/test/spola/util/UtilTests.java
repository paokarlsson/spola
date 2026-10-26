package spola.util;

import spola.test.Suite;
import spola.test.Tests;

/** The tests of {@code spola.util}, the entry named in {@code Build.java}. */
public final class UtilTests {

    private UtilTests() {
    }

    /**
     * Every test of the module.
     *
     * @return the root suite
     */
    public static Suite suite() {
        return Tests.suite("spola.util",
                LogTests.tests(),
                LogFailureTests.tests(),
                LoggingTests.tests(),
                LazyTests.tests());
    }
}
