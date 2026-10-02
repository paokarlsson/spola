package spola.json.codec;

import static spola.test.Tests.suite;

import spola.test.Suite;

/** The tests of {@code spola.json.codec}; part of the module's suite ({@code spola.json.JsonTests}). */
public final class CodecTests {

    private CodecTests() {
    }

    /**
     * Every test of the package.
     *
     * @return the suite
     */
    public static Suite tests() {
        return suite("spola.json.codec",
                RoundTripTests.tests(),
                StrictnessTests.tests(),
                ProblemTests.tests(),
                ObjectCodecTests.tests(),
                SealedTests.tests(),
                MappingTests.tests(),
                DepthTests.tests(),
                CodecConcurrencyTests.tests(),
                SourceRulesTests.tests());
    }
}
