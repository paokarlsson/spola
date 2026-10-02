package spola.json.codec;

import static spola.json.codec.JsonCodec.field;
import static spola.json.codec.JsonCodecs.STRING;
import static spola.json.codec.Problems.problem;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;
import spola.json.JsonObject;
import spola.json.JsonString;
import spola.test.Suite;

/**
 * Sealed hierarchies with a discriminator, chosen by a {@code switch} the compiler checks.
 *
 * <p>Exhaustiveness: {@link Model#SHAPE} selects the variant with
 * <pre>{@code
 * shape -> switch (shape) {
 *     case Circle c -> CIRCLE.tag(c);
 *     case Square s -> SQUARE.tag(s);
 *     case Group g -> GROUP.tag(g);
 * }
 * }</pre>
 * Adding {@code record Triangle(double side) implements Shape} to the {@code permits} clause
 * without a case makes this a compile error ("the switch expression does not cover all possible
 * input values"), so a new subtype cannot be forgotten in the codec. There is no {@code default}
 * branch and no class token anywhere.
 */
final class SealedTests {

    private SealedTests() {
    }

    static final String TAGS = "\"circle\", \"square\", \"group\"";

    sealed interface Animal permits Cat, Dog {
    }

    record Cat(String name) implements Animal {
    }

    record Dog(String name, String kind) implements Animal {
    }

    static final ObjectCodec<Cat> CAT_CODEC = JsonCodec.object(Cat::new, field("name", STRING, Cat::name));
    static final ObjectCodec<Dog> DOG_CODEC = JsonCodec.object(Dog::new,
            field("name", STRING, Dog::name), field("kind", STRING, Dog::kind));

    static Suite tests() {
        return suite("sealed hierarchies",
                test("encoding writes the discriminator first", () -> {
                    assertEquals("{\"type\":\"circle\",\"radius\":1.5}", Model.SHAPE.write(new Model.Circle(1.5)));
                    assertEquals("{\"type\":\"square\",\"side\":2.0}", Model.SHAPE.write(new Model.Square(2)));
                }),
                test("decoding finds the discriminator anywhere, also last", () -> {
                    assertEquals(new Model.Circle(1.5), Model.SHAPE.read("{\"radius\":1.5,\"type\":\"circle\"}"));
                    assertEquals(new Model.Group("g", List.of(new Model.Square(1))),
                            Model.SHAPE.read("{\"shapes\":[{\"side\":1,\"type\":\"square\"}],\"type\":\"group\",\"label\":\"g\"}"));
                }),
                test("a missing discriminator is a problem that lists the tags", () -> {
                    assertEquals(List.of(problem("$.type", "missing discriminator; expected one of " + TAGS)),
                            Problems.of(() -> Model.SHAPE.read("{\"radius\":1.5}")));
                }),
                test("an unknown tag is a problem that lists the tags", () -> {
                    assertEquals(List.of(problem("$.type", "unknown tag \"triangle\"; expected one of " + TAGS)),
                            Problems.of(() -> Model.SHAPE.read("{\"type\":\"triangle\",\"side\":1}")));
                }),
                test("a discriminator that is not a string is a problem that lists the tags", () -> {
                    assertEquals(List.of(problem("$.type", "expected a string (one of " + TAGS + ") but found number")),
                            Problems.of(() -> Model.SHAPE.read("{\"type\":1,\"radius\":1.5}")));
                    assertEquals(List.of(problem("$.type", "expected a string (one of " + TAGS + ") but found null")),
                            Problems.of(() -> Model.SHAPE.read("{\"type\":null,\"radius\":1.5}")));
                }),
                test("a value that is not an object is a problem", () -> {
                    assertEquals(List.of(problem("$", "expected object but found string")),
                            Problems.of(() -> Model.SHAPE.read("\"circle\"")));
                }),
                test("problems inside a variant have their paths, also nested", () -> {
                    assertEquals(List.of(
                            problem("$.shapes[0].radius", "expected double but found string"),
                            problem("$.shapes[1].type", "unknown tag \"hexagon\"; expected one of " + TAGS),
                            problem("$.shapes[2].colour", "unknown member; expected one of \"side\""),
                            problem("$.label", "missing member")),
                            Problems.of(() -> Model.SHAPE.read("{\"type\":\"group\",\"shapes\":["
                                    + "{\"type\":\"circle\",\"radius\":\"big\"},{\"type\":\"hexagon\"},"
                                    + "{\"type\":\"square\",\"side\":1,\"colour\":\"red\"}]}")));
                }),
                test("two variants with the same tag are an error when the codec is created", () -> {
                    var e = assertThrows(IllegalArgumentException.class, () -> JsonCodec.<Animal>sealed("type",
                            animal -> switch (animal) {
                                case Cat c -> Variant.<Animal, Cat>of("pet", CAT_CODEC).tag(c);
                                case Dog d -> Variant.<Animal, Dog>of("pet", DOG_CODEC).tag(d);
                            },
                            Variant.of("pet", CAT_CODEC), Variant.of("pet", DOG_CODEC)));
                    assertEquals("two variants have the tag \"pet\"", e.getMessage());
                }),
                test("a discriminator named like a field of a variant is an error when the codec is created", () -> {
                    Variant<Animal, Cat> cat = Variant.of("cat", CAT_CODEC);
                    Variant<Animal, Dog> dog = Variant.of("dog", DOG_CODEC);
                    var e = assertThrows(IllegalArgumentException.class, () -> JsonCodec.<Animal>sealed("kind",
                            animal -> switch (animal) {
                                case Cat c -> cat.tag(c);
                                case Dog d -> dog.tag(d);
                            },
                            cat, dog));
                    assertEquals("the codec of variant \"dog\" has a field named like the discriminator \"kind\"",
                            e.getMessage());
                }),
                test("a variant the selector returns but the list lacks fails the encoding clearly", () -> {
                    Variant<Animal, Cat> cat = Variant.of("cat", CAT_CODEC);
                    Variant<Animal, Dog> dog = Variant.of("dog", DOG_CODEC);
                    JsonCodec<Animal> codec = JsonCodec.sealed("type",
                            animal -> switch (animal) {
                                case Cat c -> cat.tag(c);
                                case Dog d -> dog.tag(d);
                            },
                            cat);
                    assertEquals("{\"type\":\"cat\",\"name\":\"Tom\"}", codec.write(new Cat("Tom")));
                    assertEquals(List.of(problem("$", "the selector returned Variant[\"dog\"], which is not one of the"
                            + " variants of this sealed codec (\"cat\")")), Problems.of(() -> codec.encode(new Dog("Rex", "x"))));
                }),
                test("a selector that returns null or throws fails the encoding with the path", () -> {
                    Variant<Animal, Cat> cat = Variant.of("cat", CAT_CODEC);
                    JsonCodec<Animal> nulls = JsonCodec.sealed("type", animal -> null, cat);
                    assertEquals(List.of(problem("$", "the selector returned null instead of a tagged value")),
                            Problems.of(() -> nulls.encode(new Cat("Tom"))));
                    JsonCodec<Animal> throwing = JsonCodec.sealed("type", animal -> {
                        throw new IllegalStateException("no");
                    }, cat);
                    assertEquals(List.of(problem("$", "the selector failed: no")),
                            Problems.of(() -> throwing.encode(new Cat("Tom"))));
                    assertEquals(List.of(problem("$", "value is null (wrap the codec in nullable(...) to write null)")),
                            Problems.of(() -> throwing.encode(null)));
                }),
                test("decoding a tree built in code", () -> {
                    var tree = JsonObject.of("side", spola.json.JsonNumber.of(3), "type", JsonString.of("square"));
                    assertEquals(new Model.Square(3), Model.SHAPE.decode(tree));
                }));
    }
}
