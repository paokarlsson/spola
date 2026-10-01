package spola.test;

import static spola.test.Assertions.assertAnyOrder;
import static spola.test.Assertions.assertContains;
import static spola.test.Assertions.assertEmpty;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertInOrder;
import static spola.test.Assertions.assertNotEquals;
import static spola.test.Assertions.assertNotNull;
import static spola.test.Assertions.assertNull;
import static spola.test.Assertions.assertSame;
import static spola.test.Assertions.assertSize;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Assertions.fail;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Every assertion passes on a match and fails with the expected and the actual value. */
final class AssertionsTests {

    private AssertionsTests() {
    }

    /** The failure {@code body} throws; fails the test if it throws none. */
    private static AssertionFailure failure(Body body) {
        try {
            body.run();
        } catch (AssertionFailure f) {
            return f;
        } catch (Exception e) {
            throw new AssertionFailure("expected an AssertionFailure but got " + e);
        }
        throw new AssertionFailure("expected an AssertionFailure but nothing was thrown");
    }

    /** Checks the shown values and that the message carries them on lines of their own. */
    private static void shows(AssertionFailure failure, String expected, String actual) {
        assertEquals(Optional.of(expected), failure.expected());
        assertEquals(Optional.of(actual), failure.actual());
        assertTrue(failure.getMessage().endsWith("\n  expected: " + expected + "\n  actual:   " + actual),
                "message ends with the expected and actual lines: " + failure.getMessage());
    }

    static Suite tests() {
        return suite("assertions",
                suite("equality",
                        test("assertEquals passes on equal values, arrays by content", () -> {
                            assertEquals("a", "a");
                            assertEquals(null, null);
                            assertEquals(List.of(1, 2), new ArrayList<>(List.of(1, 2)));
                            assertEquals(new int[] {1, 2}, new int[] {1, 2});
                            assertEquals(new String[][] {{"x"}}, new String[][] {{"x"}});
                        }),
                        test("assertEquals shows expected and actual", () -> {
                            var f = failure(() -> assertEquals(3, 4));
                            assertEquals("values differ\n  expected: 3\n  actual:   4", f.getMessage());
                            shows(f, "3", "4");
                        }),
                        test("assertEquals quotes strings and shows escapes", () ->
                                shows(failure(() -> assertEquals("a\nb", "a b")), "\"a\\nb\"", "\"a b\"")),
                        test("assertEquals shows the classes when both values read the same", () ->
                                shows(failure(() -> assertEquals(1, 1L)), "1 (java.lang.Integer)", "1 (java.lang.Long)")),
                        test("assertEquals shows arrays and collections element by element", () -> {
                            shows(failure(() -> assertEquals(new int[] {1}, new int[] {2})), "[1]", "[2]");
                            shows(failure(() -> assertEquals(List.of("a"), List.of("b"))), "[\"a\"]", "[\"b\"]");
                        }),
                        test("assertEquals with a message puts it first", () -> {
                            var f = failure(() -> assertEquals("x", null, "the name"));
                            assertTrue(f.getMessage().startsWith("the name\n"), f.getMessage());
                            shows(f, "\"x\"", "null");
                        }),
                        test("assertEquals with a tolerance", () -> {
                            assertEquals(0.3, 0.1 + 0.2, 1e-9);
                            assertEquals(Double.NaN, Double.NaN, 0);
                            shows(failure(() -> assertEquals(1.0, 1.5, 0.1)), "1.0", "1.5");
                            shows(failure(() -> assertEquals(1.0, Double.NaN, 0.1)), "1.0", "NaN");
                            assertThrows(IllegalArgumentException.class, () -> assertEquals(1.0, 1.0, -1));
                        }),
                        test("assertNotEquals", () -> {
                            assertNotEquals(1, 2);
                            shows(failure(() -> assertNotEquals("a", "a")), "anything but \"a\"", "\"a\"");
                        }),
                        test("assertNotEquals compares boxed numbers of different types by value", () -> {
                            var f = failure(() -> assertNotEquals(0, 0L));
                            assertTrue(f.getMessage().startsWith("values are numerically equal"), f.getMessage());
                            shows(f, "anything but 0 (java.lang.Integer)", "0 (java.lang.Long)");
                            failure(() -> assertNotEquals((byte) 7, (short) 7));
                            failure(() -> assertNotEquals(0.5f, 0.5));
                            failure(() -> assertNotEquals(2, 2.0));
                            failure(() -> assertNotEquals(Float.NaN, Double.NaN));
                            assertNotEquals(1, 2L);
                            assertNotEquals(0.1f, 0.1); // 0.1f widened is not the double 0.1
                            assertNotEquals(Long.MAX_VALUE, (double) Long.MAX_VALUE); // 2^63 - 1 vs 2^63
                            assertNotEquals(Float.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
                            assertNotEquals(0, "0");
                        }),
                        test("assertSame", () -> {
                            var value = new Object();
                            assertSame(value, value);
                            var f = failure(() -> assertSame(new String("a"), new String("a")));
                            assertTrue(f.expected().orElseThrow().startsWith("\"a\" (java.lang.String@"), f.getMessage());
                        })),
                suite("truth and null",
                        test("assertTrue and assertFalse", () -> {
                            assertTrue(true);
                            assertFalse(false);
                            shows(failure(() -> assertTrue(false)), "true", "false");
                            shows(failure(() -> assertFalse(true)), "false", "true");
                            assertTrue(failure(() -> assertTrue(false, "ready")).getMessage().startsWith("ready\n"));
                        }),
                        test("assertNull and assertNotNull", () -> {
                            assertNull(null);
                            assertEquals("v", assertNotNull("v"));
                            shows(failure(() -> assertNull("x")), "null", "\"x\"");
                            shows(failure(() -> assertNotNull(null)), "not null", "null");
                        }),
                        test("fail", () -> {
                            var f = failure(() -> fail("boom"));
                            assertEquals("boom", f.getMessage());
                            assertEquals(Optional.empty(), f.expected());
                        })),
                suite("exceptions",
                        test("assertThrows returns the exception, subtypes included", () -> {
                            var e = assertThrows(IllegalStateException.class, () -> {
                                throw new IllegalStateException("bad");
                            });
                            assertEquals("bad", e.getMessage());
                            assertThrows(RuntimeException.class, () -> {
                                throw new IllegalArgumentException();
                            });
                            assertThrows(IOException.class, () -> {
                                throw new IOException("checked");
                            });
                        }),
                        test("assertThrows fails when nothing is thrown", () ->
                                shows(failure(() -> assertThrows(IllegalStateException.class, () -> { })),
                                        "java.lang.IllegalStateException", "no exception")),
                        test("assertThrows fails on another type, with it as cause", () -> {
                            var thrown = new UncheckedIOException(new IOException("disk"));
                            var f = failure(() -> assertThrows(IllegalStateException.class, () -> {
                                throw thrown;
                            }));
                            shows(f, "java.lang.IllegalStateException", thrown.toString());
                            assertSame(thrown, f.getCause());
                        }),
                        test("assertThrows checks the message", () -> {
                            assertThrows(IllegalStateException.class, "exact", () -> {
                                throw new IllegalStateException("exact");
                            });
                            var f = failure(() -> assertThrows(IllegalStateException.class, "exact", () -> {
                                throw new IllegalStateException("other");
                            }));
                            shows(f, "\"exact\"", "\"other\"");
                            assertTrue(f.getCause() instanceof IllegalStateException);
                        })),
                suite("collections",
                        test("assertSize", () -> {
                            assertSize(2, List.of(1, 2));
                            shows(failure(() -> assertSize(3, List.of("a"))), "3", "1 [\"a\"]");
                        }),
                        test("assertEmpty", () -> {
                            assertEmpty(List.of());
                            shows(failure(() -> assertEmpty(Set.of(7))), "[]", "[7]");
                        }),
                        test("assertContains checks contents in any order", () -> {
                            assertContains(List.of(1, 2, 3), 3, 1);
                            var f = failure(() -> assertContains(List.of(1, 2), 2, 5));
                            assertTrue(f.getMessage().startsWith("collection lacks [5]\n"), f.getMessage());
                            shows(f, "contains [2, 5]", "[1, 2]");
                        }),
                        test("assertContains shows classes when a missing element reads like a present one", () -> {
                            var f = failure(() -> assertContains(List.of(1L), 1));
                            shows(f, "contains [1 (java.lang.Integer)]", "[1 (java.lang.Long)]");
                        }),
                        test("assertInOrder checks order and names the first difference", () -> {
                            assertInOrder(List.of("a", "b"), List.of("a", "b"));
                            var f = failure(() -> assertInOrder(List.of("a", "b"), List.of("b", "a")));
                            assertTrue(f.getMessage().startsWith("lists differ at index 0: expected \"a\" but was \"b\"\n"),
                                    f.getMessage());
                            shows(f, "[\"a\", \"b\"]", "[\"b\", \"a\"]");
                        }),
                        test("assertInOrder checks the size", () -> {
                            var f = failure(() -> assertInOrder(List.of(1, 2), List.of(1, 2, 3)));
                            assertTrue(f.getMessage().startsWith("lists differ in size: expected 2 elements but was 3\n"),
                                    f.getMessage());
                            shows(f, "[1, 2]", "[1, 2, 3]");
                        }),
                        test("assertAnyOrder compares contents and counts, not order", () -> {
                            assertAnyOrder(List.of(1, 2, 2), List.of(2, 1, 2));
                            var f = failure(() -> assertAnyOrder(List.of(1, 2, 2), List.of(2, 1, 3)));
                            assertTrue(f.getMessage().startsWith("collections differ: missing [2], unexpected [3]\n"),
                                    f.getMessage());
                            shows(f, "[1, 2, 2]", "[2, 1, 3]");
                        }),
                        test("assertAnyOrder and assertInOrder show classes when elements read the same", () -> {
                            var f = failure(() -> assertAnyOrder(List.of(1), List.of(1L)));
                            assertTrue(f.getMessage().startsWith(
                                    "collections differ: missing [1 (java.lang.Integer)], unexpected [1 (java.lang.Long)]\n"),
                                    f.getMessage());
                            shows(f, "[1 (java.lang.Integer)]", "[1 (java.lang.Long)]");
                            shows(failure(() -> assertInOrder(List.of(1), List.of(1L))),
                                    "[1 (java.lang.Integer)]", "[1 (java.lang.Long)]");
                        }),
                        test("collections that contain each other are shown without overflowing", () -> {
                            var a = new ArrayList<Object>();
                            var b = new ArrayList<Object>();
                            a.add(b);
                            b.add(a);
                            a.add(1);
                            var f = failure(() -> assertEmpty(a));
                            shows(f, "[]", "[[(cycle)], 1]");
                        })));
    }
}
