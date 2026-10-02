package spola.codegen;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertSize;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import spola.test.Suite;

/**
 * Generated files on disk: writing, recognizing by the header, the build's check (stale after a
 * changed record or a hand edit) and regenerating.
 */
final class CheckTests {

    private CheckTests() {
    }

    static final String PERSON = "package p;\n\nrecord Person(String name, int age) implements PersonWith {\n}\n";

    /** A workspace with {@code p/Person.java} and its generated {@code p/PersonWith.java}. */
    static Workspace withGeneratedPerson() {
        var workspace = Workspace.create();
        var written = GeneratedFiles.write(workspace.write("p/Person.java", PERSON), "Person");
        assertEquals(workspace.path("p/PersonWith.java"), written.file());
        assertTrue(written.changed());
        assertTrue(written.note().isEmpty(), "the record implements the interface");
        return workspace;
    }

    /** The single problem of the check, which must name the file. */
    static String singleProblem(Workspace workspace, String file) {
        var problems = GeneratedFiles.check(workspace.roots());
        assertSize(1, problems);
        assertEquals(workspace.path(file), problems.getFirst().file());
        return problems.getFirst().toString();
    }

    static Suite tests() {
        return suite("generated files",
                test("a fresh generated file passes the check, and writing it again changes nothing", () -> {
                    try (var workspace = withGeneratedPerson()) {
                        assertEquals(List.of(), GeneratedFiles.check(workspace.roots()));
                        assertEquals(List.of(workspace.path("p/PersonWith.java")), GeneratedFiles.find(workspace.roots()));
                        var again = GeneratedFiles.write(workspace.path("p/Person.java"), "Person");
                        assertFalse(again.changed());
                    }
                }),
                test("a component added to the record makes the generated file stale", () -> {
                    try (var workspace = withGeneratedPerson()) {
                        workspace.write("p/Person.java", PERSON.replace("int age)", "int age, String email)"));
                        var problem = singleProblem(workspace, "p/PersonWith.java");
                        assertTrue(problem.startsWith(workspace.path("p/PersonWith.java") + ": out of date"), problem);
                        assertTrue(problem.contains("java Build.java codegen"), problem);
                        assertTrue(problem.contains("first difference at line"), problem);
                    }
                }),
                test("a renamed component makes the generated file stale", () -> {
                    try (var workspace = withGeneratedPerson()) {
                        workspace.write("p/Person.java", PERSON.replace("int age", "int years"));
                        var problem = singleProblem(workspace, "p/PersonWith.java");
                        assertTrue(problem.contains("out of date"), problem);
                        assertTrue(problem.contains("java Build.java codegen"), problem);
                    }
                }),
                test("a hand edit, even a \\r\\n line end or a trailing space, makes the generated file stale", () -> {
                    for (var edit : List.of("withAge(int age)=withAge(long age)", "\n=\r\n", "}\n=} \n")) {
                        try (var workspace = withGeneratedPerson()) {
                            var parts = edit.split("=");
                            workspace.write("p/PersonWith.java", workspace.read("p/PersonWith.java").replaceFirst(
                                    java.util.regex.Pattern.quote(parts[0]), java.util.regex.Matcher.quoteReplacement(parts[1])));
                            var problem = singleProblem(workspace, "p/PersonWith.java");
                            assertTrue(problem.contains("edited by hand"), problem);
                            assertTrue(problem.contains("java Build.java codegen"), problem);
                        }
                    }
                }),
                test("a renamed or removed record, a damaged header and a wrongly named copy are reported", () -> {
                    try (var workspace = withGeneratedPerson()) {
                        workspace.write("p/Person.java", PERSON.replace("record Person(", "record Human("));
                        assertTrue(singleProblem(workspace, "p/PersonWith.java")
                                .contains("cannot be regenerated: " + workspace.path("p/Person.java")
                                        + ": cannot generate PersonWith for record Person: the file declares no top-level type named Person"));
                        Files.delete(workspace.path("p/Person.java"));
                        assertTrue(singleProblem(workspace, "p/PersonWith.java").contains("does not exist"));
                    }
                    try (var workspace = withGeneratedPerson()) {
                        var generated = workspace.read("p/PersonWith.java");
                        workspace.write("p/PersonWith.java", generated.replace("record Person.", "record Persson,"));
                        assertTrue(singleProblem(workspace, "p/PersonWith.java").contains("header on its first line is damaged"));
                    }
                    try (var workspace = withGeneratedPerson()) {
                        workspace.write("p/CopyWith.java", workspace.read("p/PersonWith.java"));
                        var problem = singleProblem(workspace, "p/CopyWith.java");
                        assertTrue(problem.contains("whose generated file is PersonWith.java"), problem);
                    }
                }),
                test("a record that does not implement its interface fails the check, and generating notes it", () -> {
                    try (var workspace = Workspace.create()) {
                        var source = workspace.write("p/Person.java", PERSON.replace(" implements PersonWith", ""));
                        var written = GeneratedFiles.write(source, "Person");
                        assertTrue(written.changed(), "the file is written anyway");
                        assertEquals("record Person does not implement PersonWith yet; add `implements PersonWith` to it"
                                + " (the build does not compile until it does)", written.note().orElseThrow());
                        assertTrue(singleProblem(workspace, "p/PersonWith.java").contains("does not implement PersonWith yet"));
                        var generic = workspace.write("g/Pair.java", "package g;\n\nrecord Pair<A, B>(A a, B b) implements PairWith<B, A> {\n}\n");
                        assertEquals("record Pair implements PairWith<B, A>, but the generated interface needs `implements"
                                + " PairWith<A, B>`", GeneratedFiles.write(generic, "Pair").note().orElseThrow());
                    }
                }),
                test("files are recognized by the header only, and the tool reports the path and the command", () -> {
                    try (var workspace = withGeneratedPerson()) {
                        // Named like a generated file, but without the header: hand-written, never touched.
                        workspace.write("q/OtherWith.java", "package q;\n\ninterface OtherWith {\n}\n");
                        workspace.write("q/Other.java", "package q;\n\nrecord Other(int x) {\n}\n");
                        assertEquals(List.of(workspace.path("p/PersonWith.java")), GeneratedFiles.find(workspace.roots()));
                        var refused = assertThrows(CodegenException.class,
                                () -> GeneratedFiles.write(workspace.path("q/Other.java"), "Other"));
                        assertTrue(refused.getMessage().contains("is not a generated file"), refused.getMessage());
                        assertEquals("package q;\n\ninterface OtherWith {\n}\n", workspace.read("q/OtherWith.java"));

                        workspace.write("p/Person.java", PERSON.replace("int age", "int years"));
                        var out = new ByteArrayOutputStream();
                        var err = new ByteArrayOutputStream();
                        var exit = new CodegenTool().run(new PrintStream(out, true, StandardCharsets.UTF_8),
                                new PrintStream(err, true, StandardCharsets.UTF_8), "check", workspace.dir.toString());
                        assertEquals(1, exit);
                        var report = err.toString(StandardCharsets.UTF_8);
                        assertTrue(report.contains(workspace.path("p/PersonWith.java") + ": out of date"), report);
                        assertTrue(report.contains("regenerate with java Build.java codegen"), report);
                        assertFalse(report.contains("\tat "), "no stack trace: " + report);
                    }
                }),
                test("regenerating rewrites only stale files; a second run changes nothing", () -> {
                    try (var workspace = withGeneratedPerson()) {
                        workspace.write("o/Order.java", "package o;\n\nrecord Order(long id) implements OrderWith {\n}\n");
                        GeneratedFiles.write(workspace.path("o/Order.java"), "Order");
                        workspace.write("p/Person.java", PERSON.replace("int age", "int years"));
                        var orderBefore = Files.getLastModifiedTime(workspace.path("o/OrderWith.java"));
                        var first = GeneratedFiles.regenerate(workspace.roots());
                        assertEquals(List.of(), first.problems());
                        assertEquals(List.of(workspace.path("p/PersonWith.java")),
                                first.files().stream().filter(GeneratedFiles.Written::changed).map(GeneratedFiles.Written::file).toList());
                        assertTrue(workspace.read("p/PersonWith.java").contains("withYears(int years)"));
                        assertEquals(List.of(), GeneratedFiles.check(workspace.roots()));
                        var second = GeneratedFiles.regenerate(workspace.roots());
                        assertSize(2, second.files());
                        assertTrue(second.files().stream().noneMatch(GeneratedFiles.Written::changed));
                        assertEquals(orderBefore, Files.getLastModifiedTime(workspace.path("o/OrderWith.java")));
                    }
                }),
                test("writing never overwrites the generated file of another record", () -> {
                    try (var workspace = withGeneratedPerson()) {
                        workspace.write("p/Team.java", "package p;\n\nclass Team {\n    record Person(int x) implements PersonWith {\n    }\n}\n");
                        var refused = assertThrows(CodegenException.class,
                                () -> GeneratedFiles.write(workspace.path("p/Team.java"), "Team.Person"));
                        assertTrue(refused.getMessage().contains("is the generated file of record Person in Person.java"),
                                refused.getMessage());
                    }
                }));
    }
}
