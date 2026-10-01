package spola.config;

import spola.test.Suite;
import spola.test.Tests;

/** The tests of {@code spola.config}, the entry named in {@code Build.java}. */
public final class ConfigTests {

    private ConfigTests() {
    }

    /**
     * Every test of the module.
     *
     * @return the root suite
     */
    public static Suite suite() {
        return Tests.suite("spola.config",
                SourceTests.tests(),
                ProfileTests.tests(),
                PlaceholderTests.tests(),
                BindingTests.tests(),
                SecretTests.tests());
    }
}
