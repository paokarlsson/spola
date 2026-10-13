package spola.security.web;

import java.time.Duration;
import spola.test.Suite;
import spola.test.Tests;

/** The tests of {@code spola.security.web}, the entry named in {@code Build.java}. */
public final class SecurityWebTests {

    private SecurityWebTests() {
    }

    /**
     * Every test of the module. {@code LeakTests} runs last: it checks every response and log
     * line the other tests produced.
     *
     * @return the root suite
     */
    public static Suite suite() {
        return Tests.suite("spola.security.web",
                SourceRulesTests.tests(),
                BasicTests.tests(),
                MalformedTests.tests(),
                CountingTests.tests(),
                MatcherTests.tests(),
                RuleTests.tests(),
                RequireTests.tests(),
                RouterTests.tests(),
                ConcurrencyTests.tests(),
                SessionLoginTests.tests(),
                CsrfTests.tests(),
                ReadmeTests.tests(),
                LeakTests.tests())
                .timeout(Duration.ofSeconds(120));
    }
}
