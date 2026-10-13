package spola.web;

import java.time.Duration;
import spola.test.Suite;
import spola.test.Tests;

/** The tests of {@code spola.web}, the entry named in {@code Build.java}. */
public final class WebTests {

    private WebTests() {
    }

    /**
     * Every test of the module.
     *
     * @return the root suite
     */
    public static Suite suite() {
        return Tests.suite("spola.web",
                DecodingTests.tests(),
                ClientTests.tests(),
                MatchingTests.tests(),
                StatusTests.tests(),
                DefinitionTests.tests(),
                PathTests.tests(),
                QueryTests.tests(),
                ParamTests.tests(),
                FilterTests.tests(),
                ErrorTests.tests(),
                ProblemTests.tests(),
                LogTests.tests(),
                ScaleTests.tests(),
                MediaTypeTests.tests(),
                AcceptTests.tests(),
                CrudTests.tests(),
                JsonBodyTests.tests(),
                ResponsesTests.tests(),
                CookieTests.tests(),
                ResponseCookieTests.tests(),
                FormTests.tests(),
                FormTests.readme(),
                FieldErrorTests.tests(),
                ValidatedJsonTests.tests(),
                FormBinderTests.tests(),
                ValidatedFormTests.tests(),
                ValidationReadmeTests.tests(),
                SessionTests.tests(),
                SourceRulesTests.tests())
                .timeout(Duration.ofSeconds(60));
    }
}
