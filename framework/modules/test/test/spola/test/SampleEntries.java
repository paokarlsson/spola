package spola.test;

import static spola.test.Tests.suite;
import static spola.test.Tests.test;

/**
 * Test entries for the {@link TestTool} tests: some valid, some broken on purpose. Not an entry
 * of any module in the build.
 */
public final class SampleEntries {

    private SampleEntries() {
    }

    /**
     * A green suite.
     *
     * @return one passing test
     */
    public static Suite passing() {
        return suite("sample", test("passes", () -> { }));
    }

    /**
     * A red suite.
     *
     * @return one failing test
     */
    public static Suite failing() {
        return suite("sample", test("fails", () -> Assertions.assertEquals("left", "right")));
    }

    /**
     * Not an entry: wrong return type.
     *
     * @return a string
     */
    public static String notASuite() {
        return "no";
    }

    /**
     * Not an entry: an instance method.
     *
     * @return a suite
     */
    public Suite instance() {
        return passing();
    }

    /**
     * Not an entry: throws while building the suite.
     *
     * @return never
     */
    public static Suite throwing() {
        throw new IllegalStateException("cannot build the suite");
    }

    /**
     * Not an entry: returns null.
     *
     * @return {@code null}
     */
    public static Suite nothing() {
        return null;
    }
}
