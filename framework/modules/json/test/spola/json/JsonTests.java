package spola.json;

import spola.test.Suite;
import spola.test.Tests;

/** The tests of {@code spola.json}, the entry named in {@code Build.java}. */
public final class JsonTests {

    private JsonTests() {
    }

    /**
     * Every test of the module.
     *
     * @return the root suite
     */
    public static Suite suite() {
        return Tests.suite("spola.json",
                RfcExampleTests.tests(),
                ConformanceTests.tests(),
                ErrorMessageTests.tests(),
                LimitsTests.tests(),
                NumberTests.tests(),
                ObjectTests.tests(),
                WriterTests.tests(),
                AccessTests.tests(),
                ConcurrencyTests.tests(),
                Utf8Tests.tests(),
                // the mapping package; the tree model's main code does not depend on it
                spola.json.codec.CodecTests.tests());
    }
}
