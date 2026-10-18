package spola.security.web;

import static spola.test.Tests.suite;

import spola.test.Suite;

/**
 * The tests of uppdrag 15a, the fixes for G1–G5 of the security review of 2026-10-18 (sessions
 * per user, a new session for another user, {@code Cache-Control}), registered in
 * {@link SecurityWebTests}. {@link ReviewLeakTests} runs last: it checks every response, log
 * line and message the others produced for session ids, tokens and passwords.
 */
final class ReviewTests {

    private ReviewTests() {
    }

    static Suite tests() {
        return suite("security review part 1 (15a)",
                UserLimitTests.tests(),
                UserLimitConcurrencyTests.tests(),
                UserSwitchTests.tests(),
                CacheHeaderTests.tests(),
                ReviewReadmeTests.tests(),
                ReviewLeakTests.tests());
    }
}
