package spola.client;

import spola.test.Suite;
import spola.test.Tests;

/** The test entry of spola.client (named in Build.java). */
public final class ClientTests {

    private ClientTests() {
    }

    /**
     * Every test of the module.
     *
     * @return the suite
     */
    public static Suite suite() {
        return Tests.suite("spola.client",
                ModuleTests.tests(),
                AnthropicTests.tests(),
                ItemApiTests.tests(),
                ResponseTests.tests(),
                LimitTests.tests(),
                TransportTests.tests(),
                ExcerptTests.tests(),
                MessageTests.tests(),
                SecretTests.tests(),
                SetupTests.tests(),
                RequestTests.tests(),
                ConcurrencyTests.tests(),
                CloseTests.tests(),
                SwitchTests.tests(),
                ReadmeTests.tests());
    }
}
