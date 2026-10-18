package spola.web;

import static spola.test.Tests.suite;

import spola.test.Suite;

/** The tests of 13a: the client's scheme and address behind a reverse proxy (and the warning of 15b). */
final class ProxyTests {

    private ProxyTests() {
    }

    static Suite tests() {
        return suite("behind a reverse proxy",
                IpLiteralTests.tests(),
                TrustedProxiesTests.tests(),
                ForwardedForTests.tests(),
                ForwardedProtoTests.tests(),
                ForwardedChainTests.tests(),
                ForwardedConcurrencyTests.tests(),
                ForwardedWarningTests.tests(),
                ForwardedLeakTests.tests(),
                ForwardedReadmeTests.tests());
    }
}
