package spola.validation;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import spola.test.Suite;

/**
 * Criterion 9: type errors are compile errors. Small sources that use the built module
 * ({@code out/classes/spola.validation}, on the module path) are compiled in memory with
 * {@code javax.tools}, with the build's lint options, and nothing is written.
 */
final class CompileTests {

    private CompileTests() {
    }

    static final Path MODULE = Path.of("out/classes/spola.validation");

    /** What javac said: whether it compiled, and its errors. */
    record Outcome(boolean compiled, List<String> errors) {
    }

    static Outcome compile(String body) throws Exception {
        assertTrue(Files.isRegularFile(MODULE.resolve("module-info.class")), "run from framework/ after the build");
        var source = """
                package check;

                import static spola.validation.Constraints.*;

                import java.util.List;
                import spola.validation.Constraint;
                import spola.validation.Validator;

                final class Check {
                    record Person(String name, int age, List<Person> friends) {
                    }

                    static void check() {
                %s
                    }
                }
                """.formatted(body.indent(8).stripTrailing());
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        var unit = new SimpleJavaFileObject(URI.create("string:///check/Check.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        var output = Files.createTempDirectory("spola-validation-compile");
        boolean ok;
        try {
            var options = List.of("--release", "25", "-proc:none", "-Xlint:all", "-Werror", "-implicit:none",
                    "--module-path", MODULE.toString(), "--add-modules", "spola.validation", "-d", output.toString());
            ok = compiler.getTask(null, files, diagnostics, options, null, List.of(unit)).call();
        } finally {
            try (var written = Files.walk(output)) {
                for (var file : written.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
        var errors = diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR || d.getKind() == Diagnostic.Kind.WARNING)
                .map(d -> d.getKind() + " line " + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT))
                .collect(Collectors.toList());
        return new Outcome(ok && errors.isEmpty(), errors);
    }

    static void assertCompiles(String body) throws Exception {
        var outcome = compile(body);
        assertTrue(outcome.compiled(), "expected to compile:\n" + body + "\n" + outcome.errors());
    }

    static void assertDoesNotCompile(String body, String error) throws Exception {
        var outcome = compile(body);
        assertFalse(outcome.compiled(), "expected a compile error:\n" + body);
        assertTrue(outcome.errors().stream().anyMatch(e -> e.startsWith("ERROR line 14:") && e.contains(error)),
                "expected an error on the line of the call mentioning \"" + error + "\": " + outcome.errors());
    }

    static Suite tests() {
        return suite("type errors are compile errors",
                test("notBlank() on an int component does not compile", () -> assertDoesNotCompile(
                        "Validator.<Person>builder().field(\"age\", Person::age, notBlank()).build();",
                        "inference variable V has incompatible bounds")),
                test("min(1) on a String component does not compile", () -> assertDoesNotCompile(
                        "Validator.<Person>builder().field(\"name\", Person::name, min(1)).build();",
                        "inference variable V has incompatible bounds")),
                test("past(...) on a String component does not compile", () -> assertDoesNotCompile(
                        "Validator.<Person>builder().field(\"name\", Person::name, past(() -> java.time.LocalDate.MIN)).build();",
                        "field")),
                test("min(0) on an int component compiles", () -> assertCompiles(
                        "Validator.<Person>builder().field(\"age\", Person::age, min(0)).build();")),
                test("a Constraint<Object> on a String component compiles", () -> assertCompiles("""
                        Constraint<Object> any = Constraint.of("any", o -> true, "anything");
                        Validator.<Person>builder().field("name", Person::name, any, notNull(), notBlank()).build();""")),
                test("eachNested with a Validator<Object> compiles, and so does the full set of steps", () -> assertCompiles("""
                        Validator<Object> anything = (value, errors) -> { };
                        Validator<Person> person = Validator.<Person>builder()
                                .field("age", Person::age, range(0, 150), positiveOrZero())
                                .each("friends", Person::friends, notNull())
                                .eachNested("friends", Person::friends, anything)
                                .nested("self", p -> p, anything)
                                .rule(p -> true, "ok", "ok")
                                .rule("name", p -> true, "ok", "ok")
                                .include(anything)
                                .when(p -> true, anything)
                                .field("friends", Person::friends, size(0, 10), notEmptyCollection())
                                .build();
                        person.check(new Person("a", 1, List.of()));""")),
                test("the compiler sees the module (a wrong method name is reported)", () -> {
                    var outcome = compile("Validator.<Person>builder().feld(\"age\", Person::age, min(0));");
                    assertFalse(outcome.compiled());
                    assertEquals(1, outcome.errors().size(), outcome.errors().toString());
                }));
    }
}
