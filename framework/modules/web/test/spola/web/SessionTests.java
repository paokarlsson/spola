package spola.web;

import static spola.test.Tests.suite;

import spola.test.Suite;

/** The session tests (uppdrag 11a), the leak test last. */
final class SessionTests {

    private SessionTests() {
    }

    static Suite tests() {
        return suite("sessions",
                SessionCookieTests.tests(),
                SessionFlowTests.tests(),
                SessionExpiryTests.tests(),
                SessionSetupTests.tests(),
                SessionScaleTests.tests(),
                SessionReadmeTests.tests(),
                SessionLeakTests.tests());
    }
}
