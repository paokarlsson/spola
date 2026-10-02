package spola.demo;

import spola.test.Suite;
import spola.test.Tests;

/** The tests of {@code spola.demo}, the entry named in {@code Build.java}. */
public final class DemoTests {

    private DemoTests() {
    }

    /**
     * Every test of the module.
     *
     * @return the root suite
     */
    public static Suite suite() {
        return Tests.suite("spola.demo",
                ProgramTests.tests(),
                PriorityTests.tests(),
                SecretTests.tests(),
                LifecycleTests.tests(),
                LogLevelTests.tests(),
                FailureTests.tests(),
                BoundaryTests.tests(),
                PartsTests.tests());
    }
}
