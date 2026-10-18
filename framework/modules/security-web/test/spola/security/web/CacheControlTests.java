package spola.security.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import spola.http.Response;
import spola.test.Suite;

/**
 * {@link CacheControl}, the copy of {@code spola.web}'s parser with the rules for
 * {@code XSRF-TOKEN} and for logged-in responses (15a): it has the same code as the original,
 * apart from {@link CacheControl#forIdentity}, and gives the outcomes of the original's table of
 * values ({@code SessionCacheTests} in {@code spola.web}), so the two cannot drift apart unnoticed.
 */
final class CacheControlTests {

    private CacheControlTests() {
    }

    static final List<String> NO_STORE = List.of("no-store");

    /**
     * The handler's {@code Cache-Control} fields, the outcome for a response that sets a cookie
     * ({@link CacheControl#withoutSharedCache}) and for a logged-in one
     * ({@link CacheControl#forIdentity}).
     */
    record Case(String name, List<String> handler, List<String> withCookie, List<String> loggedIn) {
        @Override
        public String toString() {
            return name;
        }

        /** A case whose value the handler keeps in both rules. */
        static Case kept(String name, List<String> handler) {
            return new Case(name, handler, handler, handler);
        }
    }

    /** The cases of {@code SessionCacheTests} in {@code spola.web}, in its order, with the outcome when logged in. */
    static final List<Case> CASES = List.of(
            new Case("none", List.of(), NO_STORE, NO_STORE),
            new Case("public, max-age=60", List.of("public, max-age=60"), NO_STORE, NO_STORE),
            new Case("s-maxage=60", List.of("s-maxage=60"), NO_STORE, NO_STORE),
            Case.kept("private, max-age=60 stays", List.of("private, max-age=60")),
            Case.kept("no-store stays", List.of("no-store")),
            Case.kept("no-cache, no-store, max-age=0, must-revalidate (Spring Security's) stays",
                    List.of("no-cache, no-store, max-age=0, must-revalidate")),
            new Case("max-age=60 lets a shared cache store it", List.of("max-age=60"), NO_STORE, List.of("max-age=60")),
            new Case("no-cache lets a shared cache store it", List.of("no-cache"), NO_STORE, List.of("no-cache")),
            new Case("must-revalidate", List.of("must-revalidate"), NO_STORE, List.of("must-revalidate")),
            new Case("PUBLIC in capitals", List.of("PUBLIC, MAX-AGE=60"), NO_STORE, NO_STORE),
            Case.kept("Private in capitals stays", List.of("Private")),
            new Case("private with s-maxage", List.of("private, s-maxage=60"), NO_STORE, NO_STORE),
            new Case("no-store with public", List.of("no-store, public"), NO_STORE, NO_STORE),
            new Case("private=\"Set-Cookie\" lets a shared cache store the rest", List.of("private=\"Set-Cookie\""),
                    NO_STORE, List.of("private=\"Set-Cookie\"")),
            Case.kept("a quoted public is not a directive", List.of("private, no-cache=\"public, s-maxage\"")),
            Case.kept("an escaped quote in a quoted string", List.of("no-cache=\"a\\\"b\", private")),
            Case.kept("private in a second field stays", List.of("max-age=60", "private")),
            new Case("s-maxage in a second field", List.of("private", "s-maxage=60"), NO_STORE, NO_STORE),
            Case.kept("empty list elements and whitespace stay", List.of(",  private ,\t,max-age=60")),
            new Case("a semicolon is not a separator", List.of("private; max-age=60"), NO_STORE, NO_STORE),
            new Case("an open quoted string", List.of("private, no-cache=\"x"), NO_STORE, NO_STORE),
            new Case("an empty argument", List.of("private, max-age="), NO_STORE, NO_STORE),
            new Case("an empty value", List.of(""), NO_STORE, NO_STORE),
            new Case("a lone equals sign", List.of("=private"), NO_STORE, NO_STORE));

    /** A response with the case's {@code Cache-Control} fields between two other fields. */
    static Response response(Case c) {
        var response = Response.text(200, "body").withHeader("X-Before", "kept");
        for (var value : c.handler()) {
            response = response.withHeader("Cache-Control", value);
        }
        return response.withHeader("X-After", "kept");
    }

    static void assertOutcome(List<String> expected, Response before, Response after) {
        assertEquals(expected, after.headers().all("Cache-Control"));
        assertEquals(List.of("kept"), after.headers().all("X-Before"), "other fields stay");
        assertEquals(List.of("kept"), after.headers().all("X-After"), "other fields stay");
        assertEquals(before.status(), after.status());
        assertTrue(before.body() == after.body(), "the body stays");
        if (expected.equals(before.headers().all("Cache-Control"))) {
            assertTrue(before == after, "a kept value leaves the response as it was");
        }
    }

    /** The code of a source file: no comments, no {@code package} line, whitespace runs as one space. */
    static String code(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ")
                .replaceFirst("package [a-z.]+;", " ").replaceAll("\\s+", " ").strip();
    }

    /** {@code code} without the method that starts with {@code signature}, up to its closing brace. */
    static String without(String code, String signature) {
        int start = code.indexOf(signature);
        assertTrue(start >= 0, "no " + signature);
        int depth = 0;
        for (int i = code.indexOf('{', start); i < code.length(); i++) {
            char c = code.charAt(i);
            depth += c == '{' ? 1 : c == '}' ? -1 : 0;
            if (depth == 0) {
                return (code.substring(0, start) + code.substring(i + 1)).replaceAll("\\s+", " ").strip();
            }
        }
        throw new AssertionError("no end of " + signature);
    }

    static Suite tests() {
        return suite("the Cache-Control rules of the security filter",
                test("the parser and withoutSharedCache have the same code as spola.web's CacheControl", () -> {
                    var original = code(Files.readString(Path.of("modules/web/src/spola/web/CacheControl.java")));
                    var copy = code(Files.readString(
                            SourceRulesTests.SOURCES.resolve("spola/security/web/CacheControl.java")));
                    assertTrue(copy.contains("static Response forIdentity(Response response) {"));
                    assertEquals(original, without(copy, "static Response forIdentity(Response response) {"));
                }),
                parameterized("a response that sets XSRF-TOKEN: withoutSharedCache", CASES, c -> {
                    var response = response(c);
                    assertOutcome(c.withCookie(), response, CacheControl.withoutSharedCache(response));
                }),
                parameterized("a response to a logged-in request: forIdentity", CASES, c -> {
                    var response = response(c);
                    assertOutcome(c.loggedIn(), response, CacheControl.forIdentity(response));
                }));
    }
}
