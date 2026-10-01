package spola.di;

import spola.test.Suite;
import spola.test.Tests;

/** The tests of {@code spola.di}, the entry named in {@code Build.java}. */
public final class DiTests {

    private DiTests() {
    }

    /**
     * Every test of the module.
     *
     * @return the root suite
     */
    public static Suite suite() {
        return Tests.suite("spola.di",
                KeyTests.tests(),
                BindingTests.tests(),
                ValidationTests.tests(),
                ScopeTests.tests(),
                ConcurrencyTests.tests(),
                LifecycleTests.tests());
    }
}
