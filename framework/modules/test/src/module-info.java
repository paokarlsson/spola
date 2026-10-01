/**
 * Spola's test runner, a replacement for JUnit without annotations.
 *
 * <p>Tests are values: a {@link spola.test.TestCase} is a name and a lambda, a
 * {@link spola.test.Suite} groups tests and other suites and carries the fixtures
 * (before/after each test and the whole suite). {@link spola.test.Tests} has the factory
 * methods, {@link spola.test.Assertions} the assertions and {@link spola.test.Runner} runs a
 * suite and prints the report. Nothing is discovered: a module's tests are whatever its test
 * entry method returns.
 *
 * <p>The build runs the tests through {@link spola.test.TestTool}, a
 * {@link java.util.spi.ToolProvider} named {@code spola-test}.
 */
module spola.test {
    exports spola.test;

    provides java.util.spi.ToolProvider with spola.test.TestTool;
}
