package spola.codegen;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;
import java.util.Map;
import spola.test.Suite;

/** A plain record: the generated code compiles under the build's options and does what it says. */
final class GenerateTests {

    private GenerateTests() {
    }

    static final String PERSON = """
            package p;

            import java.util.List;
            import java.util.Objects;

            record Person(String name, int age, boolean active, double score, List<String> tags) implements PersonWith {

                Person {
                    Objects.requireNonNull(name, "name");
                    if (age < 0) {
                        throw new IllegalArgumentException("age must not be negative: " + age);
                    }
                }
            }
            """;

    static Suite tests() {
        return suite("a plain record",
                test("builder, defaults, toBuilder, each withX and the compact constructor, compiled in memory", () -> {
                    try (var workspace = Workspace.create()) {
                        var source = workspace.write("p/Person.java", PERSON);
                        var generated = RecordCodegen.generate(source, "Person");
                        var lines = InMemory.run(Map.of(
                                "p/Person.java", PERSON,
                                "p/PersonWith.java", generated,
                                "p/Check.java", InMemory.check("p", """
                                        var ada = PersonWith.builder().name("Ada").age(36).active(true).score(1.5)
                                                .tags(java.util.List.of("math")).build();
                                        out.add("all setters: " + ada);
                                        out.add("same as constructor: " + ada.equals(new Person("Ada", 36, true, 1.5, java.util.List.of("math"))));
                                        out.add("unset: " + PersonWith.builder().name("Bob").build());
                                        out.add("toBuilder equal: " + ada.toBuilder().build().equals(ada));
                                        out.add("toBuilder new record: " + (ada.toBuilder().build() != ada));
                                        out.add("withName: " + ada.withName("Grace"));
                                        out.add("withAge: " + ada.withAge(37));
                                        out.add("withActive: " + ada.withActive(false));
                                        out.add("withScore: " + ada.withScore(2.5));
                                        out.add("withTags: " + ada.withTags(java.util.List.of()));
                                        out.add("unchanged: " + ada);
                                        var reused = PersonWith.builder().name("Cy");
                                        var first = reused.build();
                                        var second = reused.age(2).build();
                                        out.add("reused: " + first + " " + second);
                                        out.add("build validates: " + attempt(() -> PersonWith.builder().name("Dee").age(-1).build()));
                                        out.add("build validates null: " + attempt(() -> PersonWith.builder().build()));
                                        out.add("withAge validates: " + attempt(() -> ada.withAge(-2)));
                                        out.add("withName validates: " + attempt(() -> ada.withName(null)));
                                        """)),
                                "p.Check");
                        assertEquals(List.of(
                                "all setters: Person[name=Ada, age=36, active=true, score=1.5, tags=[math]]",
                                "same as constructor: true",
                                "unset: Person[name=Bob, age=0, active=false, score=0.0, tags=null]",
                                "toBuilder equal: true",
                                "toBuilder new record: true",
                                "withName: Person[name=Grace, age=36, active=true, score=1.5, tags=[math]]",
                                "withAge: Person[name=Ada, age=37, active=true, score=1.5, tags=[math]]",
                                "withActive: Person[name=Ada, age=36, active=false, score=1.5, tags=[math]]",
                                "withScore: Person[name=Ada, age=36, active=true, score=2.5, tags=[math]]",
                                "withTags: Person[name=Ada, age=36, active=true, score=1.5, tags=[]]",
                                "unchanged: Person[name=Ada, age=36, active=true, score=1.5, tags=[math]]",
                                "reused: Person[name=Cy, age=0, active=false, score=0.0, tags=null]"
                                        + " Person[name=Cy, age=2, active=false, score=0.0, tags=null]",
                                "build validates: threw IllegalArgumentException: age must not be negative: -1",
                                "build validates null: threw NullPointerException: name",
                                "withAge validates: threw IllegalArgumentException: age must not be negative: -2",
                                "withName validates: threw NullPointerException: name"), lines);
                    }
                }),
                test("the interface is sealed, permits only the record, and has the record's access", () -> {
                    try (var workspace = Workspace.create()) {
                        var packagePrivate = RecordCodegen.generate(workspace.write("p/Person.java", PERSON), "Person");
                        assertTrue(packagePrivate.contains("\nsealed interface PersonWith permits Person {\n"), packagePrivate);
                        var publicSource = """
                                package p;

                                /**
                                 * A point.
                                 *
                                 * @param x the x coordinate
                                 * @param y the y coordinate
                                 */
                                public record Point(int x, int y) implements PointWith {
                                }
                                """;
                        var publicGenerated = RecordCodegen.generate(workspace.write("p/Point.java", publicSource), "Point");
                        assertTrue(publicGenerated.contains("\npublic sealed interface PointWith permits Point {\n"),
                                publicGenerated);
                        // Public API with Javadoc that doclint accepts (-Xdoclint:all/protected -Werror).
                        var lines = InMemory.run(Map.of("p/Point.java", publicSource, "p/PointWith.java", publicGenerated,
                                "p/Check.java", InMemory.check("p", "out.add(String.valueOf(PointWith.builder().x(1).build().withY(2)));")),
                                "p.Check");
                        assertEquals(List.of("Point[x=1, y=2]"), lines);
                        var boxSource = """
                                package p;

                                /**
                                 * A box.
                                 *
                                 * @param <T> the type of the value
                                 * @param value the value
                                 */
                                public record Box<T extends Comparable<T>>(T value) implements BoxWith<T> {
                                }
                                """;
                        var boxGenerated = RecordCodegen.generate(workspace.write("p/Box.java", boxSource), "Box");
                        assertTrue(boxGenerated.contains("\npublic sealed interface BoxWith<T extends Comparable<T>> permits Box {\n"),
                                boxGenerated);
                        assertEquals(List.of("Box[value=b]"), InMemory.run(Map.of("p/Box.java", boxSource,
                                "p/BoxWith.java", boxGenerated, "p/Check.java",
                                InMemory.check("p", "out.add(String.valueOf(BoxWith.<String>builder().value(\"a\").build().withValue(\"b\")));")),
                                "p.Check"));
                    }
                }),
                test("one method per component, in component order, and only JDK and the record are referenced", () -> {
                    try (var workspace = Workspace.create()) {
                        var generated = RecordCodegen.generate(workspace.write("p/Person.java", PERSON), "Person");
                        var order = List.of("withName(String name)", "withAge(int age)", "withActive(boolean active)",
                                "withScore(double score)", "withTags(List<String> tags)", "Builder name(String name)",
                                "Builder age(int age)", "Builder active(boolean active)", "Builder score(double score)",
                                "Builder tags(List<String> tags)", "Person build()");
                        var at = -1;
                        for (var method : order) {
                            var next = generated.indexOf(method);
                            assertTrue(next > at, method + " after the previous method in:\n" + generated);
                            assertEquals(next, generated.lastIndexOf(method), method + " once");
                            at = next;
                        }
                        assertFalse(generated.contains("import java.util.Objects;"), "an import no component type uses is left out");
                        assertTrue(generated.contains("\nimport java.util.List;\n"), generated);
                        assertTrue(generated.lines().skip(2).noneMatch(line -> line.contains("spola")),
                                "nothing from spola below the header:\n" + generated);
                        var withoutJavadocTags = generated.replaceAll("@(param|return)|\\{@(code|link) ", "");
                        assertFalse(withoutJavadocTags.contains("@"), "no annotations:\n" + generated);
                    }
                }),
                test("the same source gives the same bytes, wherever the file is, with \\n line ends and the header", () -> {
                    try (var workspace = Workspace.create()) {
                        var first = workspace.write("a/p/Person.java", PERSON);
                        var second = workspace.write("b/deeper/p/Person.java", PERSON.replace("\n", "\r\n"));
                        var once = RecordCodegen.generate(first, "Person");
                        assertEquals(once, RecordCodegen.generate(first, "Person"));
                        assertEquals(once, RecordCodegen.generate(second, "Person"),
                                "another directory and \\r\\n in the source give the same output");
                        assertFalse(once.contains("\r"));
                        assertTrue(once.endsWith("}\n"));
                        assertTrue(once.startsWith("""
                                // Generated by spola.codegen from Person.java, record Person. Do not edit.
                                // Regenerate after changing the record: java Build.java codegen (in framework/)

                                package p;
                                """), once);
                        assertTrue(once.lines().noneMatch(line -> line.endsWith(" ") || line.contains("\t")),
                                "no trailing spaces or tabs");
                        assertEquals(RecordCodegen.generate(first, "Person"),
                                GeneratedFiles.header("Person.java", "Person") + once.substring(once.indexOf("\npackage")));
                    }
                }));
    }
}
