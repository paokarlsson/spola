package spola.http;

import java.time.Duration;
import spola.test.Suite;
import spola.test.Tests;

/** The tests of {@code spola.http}, the entry named in {@code Build.java}. */
public final class HttpTests {

    private HttpTests() {
    }

    /**
     * Every test of the module.
     *
     * @return the root suite
     */
    public static Suite suite() {
        return Tests.suite("spola.http",
                ClientTests.tests(),
                ConnectionTests.tests(),
                MalformedRequestTests.tests(),
                ChunkedTests.tests(),
                ExpectTests.tests(),
                TimeoutTests.tests(),
                MaxConnectionsTests.tests(),
                ErrorTests.tests(),
                ResponseTests.tests(),
                ShutdownTests.tests(),
                ConcurrencyTests.tests(),
                SourceRulesTests.tests(),
                TlsTests.tests())
                .timeout(Duration.ofSeconds(30));
    }
}
