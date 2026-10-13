package spola.security.web;

import static spola.test.Tests.suite;

import spola.test.Suite;

/**
 * The tests of the CSRF protection (uppdrag 11c), registered in {@link SecurityWebTests}.
 * {@link CsrfLeakTests} runs last: it checks every response, log line and message the others
 * (and the tests before them) produced for passwords, CSRF tokens and session ids.
 */
final class CsrfTests {

    private CsrfTests() {
    }

    static Suite tests() {
        return suite("CSRF protection",
                CsrfFlowTests.tests(),
                CsrfCookieTests.tests(),
                CsrfCheckTests.tests(),
                CsrfModeTests.tests(),
                CsrfSetupTests.tests(),
                CsrfConcurrencyTests.tests(),
                CsrfReadmeTests.tests(),
                CsrfLeakTests.tests());
    }
}
