package spola.json.codec;

import static spola.test.Assertions.assertTrue;
import static spola.test.Assertions.fail;

import java.util.List;
import java.util.function.Supplier;

/** Helpers for asserting on {@link JsonMappingException}. */
final class Problems {

    private Problems() {
    }

    /** The problems of the {@link JsonMappingException} that {@code body} throws; fails on any other outcome. */
    static List<JsonProblem> of(Supplier<?> body) {
        return exception(body).problems();
    }

    /** The {@link JsonMappingException} that {@code body} throws; fails on any other outcome. */
    static JsonMappingException exception(Supplier<?> body) {
        Object result;
        try {
            result = body.get();
        } catch (JsonMappingException e) {
            assertSafe(e.getMessage());
            for (var problem : e.problems()) {
                assertSafe(problem.path());
                assertSafe(problem.message());
            }
            return e;
        } catch (Throwable e) {
            throw new AssertionError("expected JsonMappingException but got " + e, e);
        }
        fail("expected JsonMappingException but got a result: " + result);
        return null;
    }

    static JsonProblem problem(String path, String message) {
        return new JsonProblem(path, message);
    }

    /** Every character is printable ASCII: no control, no line break, no bidi control, no U+2028. */
    static void assertSafe(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            assertTrue(c >= 0x20 && c < 0x7F, "character U+" + Integer.toHexString(c) + " at " + i);
        }
    }
}
