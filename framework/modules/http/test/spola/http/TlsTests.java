package spola.http;

import static spola.test.Tests.suite;

import spola.test.Suite;

/**
 * The TLS tests (07b). The certificate material is created once, before the first of them
 * ({@link TestPki}), and deleted after the last.
 */
final class TlsTests {

    private TlsTests() {
    }

    static Suite tests() {
        return suite("TLS",
                TlsMaterialTests.tests(),
                TlsHandshakeTests.tests(),
                TlsServerTests.tests(),
                RemoteAddressTests.tests(Transport.Tls.standard()))
                .beforeAll(TestPki::get)
                .afterAll(TestPki::delete);
    }
}
