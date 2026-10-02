package spola.codegen;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;
import java.util.Map;
import spola.test.Suite;

/**
 * Generic, nested, primitive/array/varargs and imported component types: each compiles with the
 * build's options ({@code -Xlint:all -Werror}, doclint) and works.
 */
final class ShapeTests {

    private ShapeTests() {
    }

    static final String PAIR = """
            package g;

            import java.util.List;
            import java.util.Map;
            import java.util.Objects;

            record Pair<A, B extends Comparable<B>>(A first, B second, Map<String, List<Integer>> index)
                    implements PairWith<A, B> {

                Pair {
                    Objects.requireNonNull(second, "second");
                }
            }
            """;

    static final String OUTER = """
            package n;

            final class Outer {

                private Outer() {
                }

                enum Size { SMALL, LARGE }

                record Item(String name, Size size, Part part, java.util.Optional<Item> parent) implements ItemWith {

                    record Part(int number) {
                    }

                    Item {
                        if (name.isBlank()) {
                            throw new IllegalArgumentException("blank name");
                        }
                    }
                }

                interface Shelf {
                    record Slot(int row, Outer.Size size) implements SlotWith {
                    }
                }
            }
            """;

    static final String SAMPLE = """
            package v;

            record Sample(byte b, short s, char c, long l, float f, double d, boolean z, int[] numbers,
                          String[][] grid, String... names) implements SampleWith {

                Sample {
                    if (l < 0) {
                        throw new IllegalArgumentException("negative l");
                    }
                }
            }
            """;

    static final String EVENT = """
            package i;

            import java.time.LocalDate;
            import java.util.regex.Pattern;
            import module java.sql;
            import static java.util.Map.Entry;

            record Event(LocalDate day, Timestamp at, Entry<String, Integer> count) implements EventWith {

                static final Pattern NAME = Pattern.compile("[a-z]+");

                Event {
                    if (day.getYear() < 2000) {
                        throw new IllegalArgumentException("too early: " + day);
                    }
                }
            }
            """;

    static Suite tests() {
        return suite("record shapes",
                test("a generic record with a bounded type parameter and a generic component type", () -> {
                    try (var workspace = Workspace.create()) {
                        var generated = RecordCodegen.generate(workspace.write("g/Pair.java", PAIR), "Pair");
                        assertTrue(generated.contains("sealed interface PairWith<A, B extends Comparable<B>> permits Pair {"), generated);
                        assertTrue(generated.contains("static <A, B extends Comparable<B>> Builder<A, B> builder() {"), generated);
                        assertTrue(generated.contains("final class Builder<A, B extends Comparable<B>> {"), generated);
                        assertTrue(generated.contains("default Pair<A, B> withIndex(Map<String, List<Integer>> index) {"), generated);
                        var lines = InMemory.run(Map.of("g/Pair.java", PAIR, "g/PairWith.java", generated,
                                "g/Check.java", InMemory.check("g", """
                                        PairWith.Builder<String, Integer> builder = PairWith.builder();
                                        Pair<String, Integer> pair = builder.first("x").second(2)
                                                .index(java.util.Map.of("k", java.util.List.of(1))).build();
                                        out.add(pair.toString());
                                        Pair<String, Integer> second = pair.withSecond(3);
                                        out.add(second.toString());
                                        out.add(pair.withFirst("y").toString());
                                        out.add(pair.withIndex(java.util.Map.of()).toString());
                                        out.add(String.valueOf(pair.toBuilder().build().equals(pair)));
                                        out.add(attempt(() -> PairWith.<String, Integer>builder().first("z").build()));
                                        out.add(attempt(() -> pair.withSecond(null)));
                                        """)), "g.Check");
                        assertEquals(List.of(
                                "Pair[first=x, second=2, index={k=[1]}]",
                                "Pair[first=x, second=3, index={k=[1]}]",
                                "Pair[first=y, second=2, index={k=[1]}]",
                                "Pair[first=x, second=2, index={}]",
                                "true",
                                "threw NullPointerException: second",
                                "threw NullPointerException: second"), lines);
                    }
                }),
                test("nested records: in a class and in an interface, with member types qualified", () -> {
                    try (var workspace = Workspace.create()) {
                        var source = workspace.write("n/Outer.java", OUTER);
                        var item = RecordCodegen.generate(source, "Outer.Item");
                        var slot = RecordCodegen.generate(source, "Outer.Shelf.Slot");
                        assertEquals(workspace.path("n/ItemWith.java"), RecordCodegen.targetFile(source, "Outer.Item"));
                        assertTrue(item.contains("\nsealed interface ItemWith permits Outer.Item {\n"), item);
                        assertTrue(item.contains("default Outer.Item withSize(Outer.Size size) {"), item);
                        assertTrue(item.contains("default Outer.Item withPart(Outer.Item.Part part) {"), item);
                        assertTrue(item.contains("default Outer.Item withParent(java.util.Optional<Outer.Item> parent) {"), item);
                        assertTrue(slot.contains("\nsealed interface SlotWith permits Outer.Shelf.Slot {\n"), slot);
                        var lines = InMemory.run(Map.of("n/Outer.java", OUTER, "n/ItemWith.java", item, "n/SlotWith.java", slot,
                                "n/Check.java", InMemory.check("n", """
                                        var item = ItemWith.builder().name("bolt").size(Outer.Size.SMALL)
                                                .part(new Outer.Item.Part(7)).parent(java.util.Optional.empty()).build();
                                        out.add(item.toString());
                                        out.add(item.withSize(Outer.Size.LARGE).toString());
                                        out.add(item.withParent(java.util.Optional.of(item)).parent().get().name());
                                        out.add(String.valueOf(item.toBuilder().build().equals(item)));
                                        out.add(attempt(() -> item.withName(" ")));
                                        out.add(attempt(() -> ItemWith.builder().name("").build()));
                                        out.add(SlotWith.builder().row(3).build().withSize(Outer.Size.LARGE).toString());
                                        """)), "n.Check");
                        assertEquals(List.of(
                                "Item[name=bolt, size=SMALL, part=Part[number=7], parent=Optional.empty]",
                                "Item[name=bolt, size=LARGE, part=Part[number=7], parent=Optional.empty]",
                                "bolt",
                                "true",
                                "threw IllegalArgumentException: blank name",
                                "threw IllegalArgumentException: blank name",
                                "Slot[row=3, size=LARGE]"), lines);
                    }
                }),
                test("primitive, array and varargs components; varargs is an array in the setter and the with-method", () -> {
                    try (var workspace = Workspace.create()) {
                        var generated = RecordCodegen.generate(workspace.write("v/Sample.java", SAMPLE), "Sample");
                        assertTrue(generated.contains("default Sample withNames(String[] names) {"), generated);
                        assertTrue(generated.contains("public Builder names(String[] names) {"), generated);
                        assertTrue(generated.contains("default Sample withGrid(String[][] grid) {"), generated);
                        assertFalse(generated.contains("..."), generated);
                        var lines = InMemory.run(Map.of("v/Sample.java", SAMPLE, "v/SampleWith.java", generated,
                                "v/Check.java", InMemory.check("v", """
                                        var unset = SampleWith.builder().build();
                                        out.add(unset.b() + " " + unset.s() + " " + (int) unset.c() + " " + unset.l() + " "
                                                + unset.f() + " " + unset.d() + " " + unset.z() + " " + unset.numbers() + " "
                                                + unset.grid() + " " + unset.names());
                                        var names = new String[] {"a", "b"};
                                        var set = SampleWith.builder().b((byte) 1).s((short) 2).c('x').l(4L).f(5.5f).d(6.5)
                                                .z(true).numbers(new int[] {7, 8}).grid(new String[][] {{"g"}}).names(names).build();
                                        out.add(set.b() + " " + set.s() + " " + set.c() + " " + set.l() + " " + set.f() + " "
                                                + set.d() + " " + set.z() + " " + java.util.Arrays.toString(set.numbers()) + " "
                                                + java.util.Arrays.deepToString(set.grid()) + " " + java.util.Arrays.toString(set.names()));
                                        out.add("same array: " + (set.names() == names));
                                        out.add(java.util.Arrays.toString(set.withNames(new String[] {"c"}).names()));
                                        out.add(set.withC('y').c() + " " + set.withL(9L).l() + " " + set.withZ(false).z());
                                        out.add(String.valueOf(set.toBuilder().build().equals(set)));
                                        out.add(attempt(() -> set.withL(-1L)));
                                        out.add(attempt(() -> SampleWith.builder().l(-1L).build()));
                                        """)), "v.Check");
                        assertEquals(List.of(
                                "0 0 0 0 0.0 0.0 false null null null",
                                "1 2 x 4 5.5 6.5 true [7, 8] [[g]] [a, b]",
                                "same array: true",
                                "[c]",
                                "y 9 false",
                                "true",
                                "threw IllegalArgumentException: negative l",
                                "threw IllegalArgumentException: negative l"), lines);
                    }
                }),
                test("component types that need the source file's imports, including import module", () -> {
                    try (var workspace = Workspace.create()) {
                        var generated = RecordCodegen.generate(workspace.write("i/Event.java", EVENT), "Event");
                        assertTrue(generated.contains("""

                                package i;

                                import java.time.LocalDate;
                                import module java.sql;
                                import static java.util.Map.Entry;

                                /**
                                """), generated);
                        assertFalse(generated.contains("Pattern"), "an import no component type uses is left out");
                        var lines = InMemory.run(Map.of("i/Event.java", EVENT, "i/EventWith.java", generated,
                                "i/Check.java", InMemory.check("i", """
                                        var event = EventWith.builder().day(java.time.LocalDate.of(2026, 10, 2))
                                                .at(new java.sql.Timestamp(0)).count(java.util.Map.entry("x", 1)).build();
                                        out.add(event.day() + " " + event.at().getTime() + " " + event.count());
                                        out.add(event.withDay(java.time.LocalDate.of(2027, 1, 1)).day().toString());
                                        out.add(attempt(() -> event.withDay(java.time.LocalDate.of(1999, 12, 31))));
                                        out.add(String.valueOf(event.toBuilder().build().equals(event)));
                                        """)), "i.Check");
                        assertEquals(List.of(
                                "2026-10-02 0 x=1",
                                "2027-01-01",
                                "threw IllegalArgumentException: too early: 1999-12-31",
                                "true"), lines);
                    }
                }));
    }
}
