package spola.json.codec;

import static spola.json.codec.JsonCodec.field;
import static spola.json.codec.JsonCodec.optionalField;
import static spola.json.codec.JsonCodecs.BOOLEAN;
import static spola.json.codec.JsonCodecs.INT;
import static spola.json.codec.JsonCodecs.LONG;
import static spola.json.codec.JsonCodecs.STRING;
import static spola.json.codec.JsonCodecs.nullable;
import static spola.json.codec.Problems.problem;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import spola.test.Suite;

/** Object codecs: optional and default members, null, unknown members, order, 16 and more fields. */
final class ObjectCodecTests {

    private ObjectCodecTests() {
    }

    record Person(String name, Optional<String> note, int qty, String nick) {
    }

    static final ObjectCodec<Person> PERSON = JsonCodec.object(Person::new,
            field("name", STRING, Person::name),
            optionalField("note", STRING, Person::note),
            field("qty", INT, Person::qty).orElse(1),
            field("nick", nullable(STRING), Person::nick).orElse(null));

    record Sixteen(int a, String b, int c, String d, int e, String f, int g, String h, int i, String j, int k, String l, int m, String n, int o, String p) {
    }

    static final ObjectCodec<Sixteen> SIXTEEN = JsonCodec.object(Sixteen::new,
            field("a", INT, Sixteen::a),
            field("b", STRING, Sixteen::b),
            field("c", INT, Sixteen::c),
            field("d", STRING, Sixteen::d),
            field("e", INT, Sixteen::e),
            field("f", STRING, Sixteen::f),
            field("g", INT, Sixteen::g),
            field("h", STRING, Sixteen::h),
            field("i", INT, Sixteen::i),
            field("j", STRING, Sixteen::j),
            field("k", INT, Sixteen::k),
            field("l", STRING, Sixteen::l),
            field("m", INT, Sixteen::m),
            field("n", STRING, Sixteen::n),
            field("o", INT, Sixteen::o),
            field("p", STRING, Sixteen::p));

    record Big(long f0, String f1, boolean f2, long f3, String f4, boolean f5, long f6, String f7, boolean f8, long f9, String f10, boolean f11, long f12, String f13, boolean f14, long f15, String f16, boolean f17, long f18, String f19, boolean f20, long f21) {
    }

    static final Field<Big, Long> F0 = field("f0", LONG, Big::f0);
    static final Field<Big, String> F1 = field("f1", STRING, Big::f1);
    static final Field<Big, Boolean> F2 = field("f2", BOOLEAN, Big::f2);
    static final Field<Big, Long> F3 = field("f3", LONG, Big::f3);
    static final Field<Big, String> F4 = field("f4", STRING, Big::f4);
    static final Field<Big, Boolean> F5 = field("f5", BOOLEAN, Big::f5);
    static final Field<Big, Long> F6 = field("f6", LONG, Big::f6);
    static final Field<Big, String> F7 = field("f7", STRING, Big::f7);
    static final Field<Big, Boolean> F8 = field("f8", BOOLEAN, Big::f8);
    static final Field<Big, Long> F9 = field("f9", LONG, Big::f9);
    static final Field<Big, String> F10 = field("f10", STRING, Big::f10);
    static final Field<Big, Boolean> F11 = field("f11", BOOLEAN, Big::f11);
    static final Field<Big, Long> F12 = field("f12", LONG, Big::f12);
    static final Field<Big, String> F13 = field("f13", STRING, Big::f13);
    static final Field<Big, Boolean> F14 = field("f14", BOOLEAN, Big::f14);
    static final Field<Big, Long> F15 = field("f15", LONG, Big::f15);
    static final Field<Big, String> F16 = field("f16", STRING, Big::f16);
    static final Field<Big, Boolean> F17 = field("f17", BOOLEAN, Big::f17);
    static final Field<Big, Long> F18 = field("f18", LONG, Big::f18);
    static final Field<Big, String> F19 = field("f19", STRING, Big::f19);
    static final Field<Big, Boolean> F20 = field("f20", BOOLEAN, Big::f20);
    static final Field<Big, Long> F21 = field("f21", LONG, Big::f21);

    static final ObjectCodec<Big> BIG = JsonCodec.object(List.of(F0, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13, F14, F15, F16, F17, F18, F19, F20, F21),
            v -> new Big(v.get(F0), v.get(F1), v.get(F2), v.get(F3), v.get(F4), v.get(F5), v.get(F6), v.get(F7), v.get(F8), v.get(F9), v.get(F10), v.get(F11), v.get(F12), v.get(F13), v.get(F14), v.get(F15), v.get(F16), v.get(F17), v.get(F18), v.get(F19), v.get(F20), v.get(F21)));

    static Suite tests() {
        return suite("object codecs",
                test("optionalField: missing and null give empty, present gives the value", () -> {
                    assertEquals(new Person("a", Optional.empty(), 1, null), PERSON.read("{\"name\":\"a\"}"));
                    assertEquals(new Person("a", Optional.empty(), 1, null), PERSON.read("{\"name\":\"a\",\"note\":null}"));
                    assertEquals(new Person("a", Optional.of("n"), 1, null), PERSON.read("{\"name\":\"a\",\"note\":\"n\"}"));
                    assertEquals(List.of(problem("$.note", "expected string but found number")),
                            Problems.of(() -> PERSON.read("{\"name\":\"a\",\"note\":5}")));
                }),
                test("optionalField: an empty value is left out when encoding, a present one is written", () -> {
                    assertEquals("{\"name\":\"a\",\"qty\":1,\"nick\":null}",
                            PERSON.write(new Person("a", Optional.empty(), 1, null)));
                    assertEquals("{\"name\":\"a\",\"note\":\"n\",\"qty\":1,\"nick\":\"x\"}",
                            PERSON.write(new Person("a", Optional.of("n"), 1, "x")));
                    assertEquals(List.of(problem("$.note", "value is null; an optional field needs an Optional, empty or not")),
                            Problems.of(() -> PERSON.encode(new Person("a", null, 1, null))));
                }),
                test("orElse gives the default for a missing member, but null is still a problem", () -> {
                    assertEquals(1, PERSON.read("{\"name\":\"a\"}").qty());
                    assertEquals(7, PERSON.read("{\"name\":\"a\",\"qty\":7}").qty());
                    assertEquals(List.of(problem("$.qty", "expected int but found null")),
                            Problems.of(() -> PERSON.read("{\"name\":\"a\",\"qty\":null}")));
                    assertThrows(IllegalStateException.class,
                            () -> optionalField("x", STRING, Person::note).orElse(Optional.empty()));
                }),
                test("JSON null for a required field is a problem", () -> {
                    assertEquals(List.of(problem("$.name", "expected string but found null")),
                            Problems.of(() -> PERSON.read("{\"name\":null}")));
                }),
                test("unknown members are rejected by default, one problem each, and accepted with ignoringUnknown()", () -> {
                    var text = "{\"name\":\"a\",\"admin\":true,\"nmae\":\"b\"}";
                    assertEquals(List.of(
                            problem("$.admin", "unknown member; expected one of \"name\", \"note\", \"qty\", \"nick\""),
                            problem("$.nmae", "unknown member; expected one of \"name\", \"note\", \"qty\", \"nick\"")),
                            Problems.of(() -> PERSON.read(text)));
                    assertEquals(new Person("a", Optional.empty(), 1, null), PERSON.ignoringUnknown().read(text));
                }),
                test("members are written in field order, not in the order of the document or the alphabet", () -> {
                    var person = PERSON.read("{\"nick\":\"z\",\"qty\":2,\"note\":\"y\",\"name\":\"x\"}");
                    assertEquals("{\"name\":\"x\",\"note\":\"y\",\"qty\":2,\"nick\":\"z\"}", PERSON.write(person));
                    assertEquals("{\n  \"name\": \"x\",\n  \"note\": \"y\",\n  \"qty\": 2,\n  \"nick\": \"z\"\n}",
                            PERSON.writePretty(person));
                }),
                test("the constructor is not called when a field has a problem", () -> {
                    var calls = new AtomicInteger();
                    var codec = JsonCodec.object((String name, Integer qty) -> {
                        calls.incrementAndGet();
                        return name + qty;
                    }, field("name", STRING, (String s) -> s), field("qty", INT, (String s) -> s.length()));
                    Problems.of(() -> codec.read("{\"name\":\"a\",\"qty\":\"2\"}"));
                    Problems.of(() -> codec.read("{\"name\":\"a\"}"));
                    Problems.of(() -> codec.read("{\"name\":\"a\",\"qty\":2,\"x\":0}"));
                    assertEquals(0, calls.get());
                    assertEquals("a2", codec.read("{\"name\":\"a\",\"qty\":2}"));
                    assertEquals(1, calls.get());
                }),
                test("two fields with the same name are an error when the codec is created", () -> {
                    var e = assertThrows(IllegalArgumentException.class, () -> JsonCodec.object(Person::new,
                            field("name", STRING, Person::name),
                            optionalField("note", STRING, Person::note),
                            field("name", INT, Person::qty),
                            field("nick", STRING, Person::nick)));
                    assertEquals("two fields have the member name \"name\"", e.getMessage());
                }),
                test("an object codec rejects a value that is not an object", () -> {
                    assertEquals(List.of(problem("$", "expected object but found array")),
                            Problems.of(() -> PERSON.read("[]")));
                    assertEquals(List.of(problem("$", "expected object but found null")),
                            Problems.of(() -> PERSON.read("null")));
                }),
                test("an object without fields", () -> {
                    record Empty() {
                    }
                    var codec = JsonCodec.object(Empty::new);
                    assertEquals(new Empty(), codec.read("{}"));
                    assertEquals("{}", codec.write(new Empty()));
                    assertEquals(List.of(problem("$.x", "unknown member; the object has no members")),
                            Problems.of(() -> codec.read("{\"x\":1}")));
                }),
                test("16 fields: round trip and every problem", () -> {
                    var value = new Sixteen(0, "s1", 2, "s3", 4, "s5", 6, "s7", 8, "s9", 10, "s11", 12, "s13", 14, "s15");
                    assertEquals(value, SIXTEEN.read(SIXTEEN.write(value)));
                    assertEquals(value, SIXTEEN.decode(SIXTEEN.encode(value)));
                    var problems = Problems.of(() -> SIXTEEN.read("{\"a\":\"x\",\"p\":1,\"zz\":0}"));
                    assertEquals(17, problems.size());
                    assertEquals(problem("$.a", "expected int but found string"), problems.get(0));
                    assertEquals(problem("$.p", "expected string but found number"), problems.get(1));
                    assertEquals("$.zz", problems.get(2).path());
                    assertEquals(problem("$.b", "missing member"), problems.get(3));
                    assertEquals(problem("$.o", "missing member"), problems.get(16));
                }),
                test("22 fields with the general form: round trip and every problem", () -> {
                    var value = new Big(0L, "t1", true, 3L, "t4", false, 6L, "t7", true, 9L, "t10", false, 12L, "t13", true, 15L, "t16", false, 18L, "t19", true, 21L);
                    assertEquals(value, BIG.read(BIG.write(value)));
                    assertEquals(value, BIG.decode(BIG.encode(value)));
                    var problems = Problems.of(() -> BIG.read("{\"f0\":true,\"f21\":\"x\"}"));
                    assertEquals(22, problems.size());
                    assertEquals(problem("$.f0", "expected long but found boolean"), problems.get(0));
                    assertEquals(problem("$.f21", "expected long but found string"), problems.get(1));
                    assertEquals(problem("$.f1", "missing member"), problems.get(2));
                    assertEquals(problem("$.f20", "missing member"), problems.get(21));
                    assertTrue(BIG.write(value).startsWith("{\"f0\":0,\"f1\":\"t1\",\"f2\":true,"), BIG.write(value));
                }),
                test("the general form: a field that is not one of the codec's is a programming error, not a problem", () -> {
                    var other = field("other", STRING, (Big b) -> b.f1());
                    var codec = JsonCodec.<Big>object(List.of(F0), v -> {
                        v.get(other);
                        return null;
                    });
                    var e = assertThrows(IllegalArgumentException.class, () -> codec.read("{\"f0\":1}"));
                    assertEquals("Field[\"other\"] is not one of the fields of this object codec", e.getMessage());
                }),
                test("a getter that throws fails the encoding with the path", () -> {
                    var codec = JsonCodec.object((String s) -> s, field("s", STRING, (String s) -> {
                        throw new IllegalStateException("broken\ngetter");
                    }));
                    assertEquals(List.of(problem("$.s", "the getter failed: broken\\u000agetter")),
                            Problems.of(() -> codec.encode("x")));
                }));
    }
}
