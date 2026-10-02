package spola.codegen;

import spola.test.Suite;
import spola.test.Tests;

/** The tests of {@code spola.codegen}, the entry named in {@code Build.java}. */
public final class CodegenTests {

    private CodegenTests() {
    }

    /**
     * Every test of the module.
     *
     * @return the root suite
     */
    public static Suite suite() {
        return Tests.suite("spola.codegen",
                GenerateTests.tests(),
                ShapeTests.tests(),
                ErrorTests.tests(),
                CheckTests.tests());
    }
}
