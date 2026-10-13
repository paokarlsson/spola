package spola.security.web;

import static spola.test.Tests.suite;

import spola.test.Suite;

/**
 * The tests of the login with a session (uppdrag 11b), registered in {@link SecurityWebTests}.
 * {@link LoginLeakTests} runs last: it checks every response, log line and message the others
 * produced for passwords and session ids.
 */
final class SessionLoginTests {

    private SessionLoginTests() {
    }

    static Suite tests() {
        return suite("login with a session",
                LoginFlowTests.tests(),
                FixationTests.tests(),
                LoginRuleTests.tests(),
                LoginExpiryTests.tests(),
                LoginMisuseTests.tests(),
                LoginConcurrencyTests.tests(),
                LoginReadmeTests.tests(),
                LoginLeakTests.tests());
    }
}
