package spola.validation;

import java.time.Duration;
import spola.test.Suite;
import spola.test.Tests;

/** The tests of {@code spola.validation}, the entry named in {@code Build.java}. */
public final class ValidationTests {

    private ValidationTests() {
    }

    /**
     * Every test of the module.
     *
     * @return the root suite
     */
    public static Suite suite() {
        return Tests.suite("spola.validation",
                ConstraintTableTests.tests(),
                EmailTests.tests(),
                PathTests.tests(),
                OrderExampleTests.tests(),
                OrderExampleTests.readme(),
                LimitTests.tests(),
                CustomValidatorTests.tests(),
                CompileTests.tests(),
                UserCodeTests.tests(),
                ConcurrencyTests.tests(),
                SafetyTests.tests(),
                SourceRulesTests.tests())
                .timeout(Duration.ofSeconds(60));
    }
}
