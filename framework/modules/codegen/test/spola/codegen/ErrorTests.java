package spola.codegen;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import spola.test.Suite;

/** Every way generation can fail gives a message that names the file and the record. */
final class ErrorTests {

    private ErrorTests() {
    }

    /**
     * One failing case.
     *
     * @param name what is wrong
     * @param source the source file's content, or {@code null} for a missing file
     * @param record the record name asked for
     * @param reason a part of the message that says what is wrong
     */
    record Case(String name, String source, String record, String reason) {
    }

    static final List<Case> CASES = List.of(
            new Case("missing file", null, "Person", "the file does not exist"),
            new Case("file that does not parse", "package p;\nrecord Person(String name {\n", "Person",
                    "the file does not parse: line 2"),
            new Case("record not in the file", "package p;\nrecord Other(int x) {\n}\n", "Person",
                    "the file declares no top-level type named Person"),
            new Case("nested record not in the file", "package p;\nclass Outer {\n}\n", "Outer.Person",
                    "Outer has no member type named Person"),
            new Case("a class, not a record", "package p;\nclass Person {\n}\n", "Person", "Person is a class, not a record"),
            new Case("an enum, not a record", "package p;\nenum Person { ADA }\n", "Person", "Person is an enum, not a record"),
            new Case("an interface, not a record", "package p;\ninterface Person {\n}\n", "Person",
                    "Person is an interface, not a record"),
            new Case("private record", "package p;\nclass Outer {\n    private record Person(int x) {\n    }\n}\n",
                    "Outer.Person", "the record is private"),
            new Case("record inside a private type",
                    "package p;\nclass Outer {\n    private static class Hidden {\n        record Person(int x) {\n        }\n    }\n}\n",
                    "Outer.Hidden.Person", "inside the private type Hidden"),
            new Case("record without components", "package p;\nrecord Person() {\n}\n", "Person",
                    "the record has no components"),
            new Case("component named builder", "package p;\nrecord Person(String builder) {\n}\n", "Person",
                    "the component builder clashes with the generated method builder()"),
            new Case("component named toBuilder", "package p;\nrecord Person(String toBuilder) {\n}\n", "Person",
                    "the component toBuilder clashes with the generated method toBuilder()"),
            new Case("component named build", "package p;\nrecord Person(int build) {\n}\n", "Person",
                    "the component build clashes with the generated method build()"),
            new Case("component named equals", "package p;\nrecord Person(Object equals) {\n}\n", "Person",
                    "the component equals clashes with the generated method equals(...) of the builder"),
            new Case("clashing withX", "package p;\nrecord Person(int url, int Url) {\n}\n", "Person",
                    "the components url and Url both give the method withUrl"),
            new Case("record declares a withX itself", "package p;\nrecord Person(int age) {\n"
                    + "    Person withAge(int age) {\n        return new Person(age);\n    }\n}\n", "Person",
                    "the record declares withAge(...) itself"),
            new Case("record declares toBuilder itself", "package p;\nrecord Person(int age) {\n"
                    + "    Object toBuilder() {\n        return null;\n    }\n}\n", "Person",
                    "the record declares toBuilder() itself"),
            new Case("component type named Builder", "package p;\nrecord Person(Builder b) {\n}\n", "Person",
                    "a component type named Builder would mean the generated PersonWith.Builder"),
            new Case("type parameter named Builder", "package p;\nrecord Person<Builder>(Builder b) {\n}\n", "Person",
                    "the type parameter Builder"),
            new Case("default package", "record Person(int x) {\n}\n", "Person", "records in the default package are not supported"),
            new Case("not a record name", "package p;\nrecord Person(int x) {\n}\n", "Person.", "is not a record name"));

    static Suite tests() {
        return suite("errors",
                parameterized("the message names the file, the record and the problem", CASES, Case::name, c -> {
                    try (var workspace = Workspace.create()) {
                        var file = c.source() == null ? workspace.path("p/Person.java") : workspace.write("p/Person.java", c.source());
                        var failure = assertThrows(CodegenException.class, () -> RecordCodegen.generate(file, c.record()));
                        var message = failure.getMessage();
                        assertTrue(message.startsWith(file + ": "), message);
                        assertTrue(message.contains("record " + c.record() + ": "), message);
                        assertTrue(message.contains(c.reason()), message);
                        assertFalse(message.contains("\n"), "one line: " + message);
                    }
                }),
                test("a file that is not valid UTF-8, a directory and a file that is not .java", () -> {
                    try (var workspace = Workspace.create()) {
                        var latin1 = workspace.path("p/Person.java");
                        java.nio.file.Files.createDirectories(latin1.getParent());
                        java.nio.file.Files.write(latin1, "package p;\n// café\nrecord Person(int x) {\n}\n"
                                .getBytes(StandardCharsets.ISO_8859_1));
                        var failure = assertThrows(CodegenException.class, () -> RecordCodegen.generate(latin1, "Person"));
                        assertTrue(failure.getMessage().contains("not valid UTF-8"), failure.getMessage());
                        var directory = workspace.path("p");
                        assertTrue(assertThrows(CodegenException.class, () -> RecordCodegen.generate(directory, "Person"))
                                .getMessage().contains("not a .java file"));
                        var text = workspace.write("p/Person.txt", "package p;\nrecord Person(int x) {\n}\n");
                        assertTrue(assertThrows(CodegenException.class, () -> RecordCodegen.generate(text, "Person"))
                                .getMessage().contains("not a .java file"));
                    }
                }),
                test("the tool prints the message without a stack trace and exits 1", () -> {
                    try (var workspace = Workspace.create()) {
                        var file = workspace.write("p/Person.java", "package p;\nrecord Person() {\n}\n");
                        var out = new ByteArrayOutputStream();
                        var err = new ByteArrayOutputStream();
                        var exit = new CodegenTool().run(new PrintStream(out, true, StandardCharsets.UTF_8),
                                new PrintStream(err, true, StandardCharsets.UTF_8), "generate", file.toString(), "Person");
                        assertEquals(1, exit);
                        assertEquals(file + ": cannot generate PersonWith for record Person: the record has no components;"
                                + " there is nothing to build or replace\n", err.toString(StandardCharsets.UTF_8));
                        assertEquals("", out.toString(StandardCharsets.UTF_8));
                        assertTrue(java.nio.file.Files.notExists(workspace.path("p/PersonWith.java")), "nothing written");
                    }
                }),
                test("the tool reports usage errors with exit code 2", () -> {
                    var err = new ByteArrayOutputStream();
                    var stream = new PrintStream(err, true, StandardCharsets.UTF_8);
                    assertEquals(2, new CodegenTool().run(stream, stream));
                    assertEquals(2, new CodegenTool().run(stream, stream, "generate", "only-a-file.java"));
                    assertEquals(2, new CodegenTool().run(stream, stream, "check"));
                    assertEquals(2, new CodegenTool().run(stream, stream, "frobnicate"));
                    assertTrue(err.toString(StandardCharsets.UTF_8).contains("unknown command frobnicate"));
                }));
    }
}
