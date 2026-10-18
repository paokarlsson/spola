package spola.security.web;

import static spola.test.Tests.suite;

import spola.test.Suite;

/**
 * The tests of the sessions per user, the new session for another user and the
 * {@code Cache-Control} rules (uppdrag 15a), registered in {@link SecurityWebTests}.
 * {@link UserSessionLeakTests} runs last: it checks every response, log line and message the
 * others produced for session ids, tokens and passwords.
 */
final class UserSessionTests {

    private UserSessionTests() {
    }

    static Suite tests() {
        return suite("sessions per user, another user's login and Cache-Control (15a)",
                UserLimitTests.tests(),
                UserLimitConcurrencyTests.tests(),
                UserLimitRaceTests.tests(),
                UserSwitchTests.tests(),
                CacheHeaderTests.tests(),
                CacheControlTests.tests(),
                UserSessionReadmeTests.tests(),
                UserSessionLeakTests.tests());
    }
}
