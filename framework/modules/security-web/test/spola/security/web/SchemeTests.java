package spola.security.web;

import static spola.test.Tests.suite;

import spola.test.Suite;

/**
 * The tests of 13b: {@code Secure} on the session cookie and {@code XSRF-TOKEN} when the request's
 * scheme is {@code https}, also behind a proxy with {@code ForwardedHeaders}. Registered in
 * {@link CsrfTests} before {@link CsrfLeakTests}, so that the leak tests also read every reply
 * and log line of these. Since 15b also {@link CaddyExampleTests}, the production setting with
 * {@code __Host-SESSION} and {@code secure(true)} on both cookies.
 */
final class SchemeTests {

    private SchemeTests() {
    }

    static Suite tests() {
        return suite("Secure from the scheme",
                SchemeFlowTests.tests(),
                SchemeCookieTests.tests(),
                SchemeConcurrencyTests.tests(),
                SchemeReadmeTests.tests(),
                CaddyExampleTests.tests());
    }
}
