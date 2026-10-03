package spola.security;

import java.time.Duration;
import spola.test.Suite;
import spola.test.Tests;

/** The tests of {@code spola.security}, the entry named in {@code Build.java}. */
public final class SecurityTests {

    private SecurityTests() {
    }

    /**
     * Every test of the module.
     *
     * @return the root suite
     */
    public static Suite suite() {
        return Tests.suite("spola.security",
                Pbkdf2Tests.tests(),
                HasherTests.tests(),
                MalformedHashTests.tests(),
                UserTests.tests(),
                AuthenticatorTests.tests(),
                ConcurrencyTests.tests(),
                SecretTests.tests(),
                ReadmeTests.tests(),
                SourceRulesTests.tests())
                .timeout(Duration.ofSeconds(60));
    }
}
