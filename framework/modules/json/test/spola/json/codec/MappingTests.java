package spola.json.codec;

import static spola.json.codec.JsonCodec.field;
import static spola.json.codec.JsonCodecs.INT;
import static spola.json.codec.JsonCodecs.STRING;
import static spola.json.codec.JsonCodecs.enumOf;
import static spola.json.codec.JsonCodecs.lazy;
import static spola.json.codec.JsonCodecs.list;
import static spola.json.codec.JsonCodecs.map;
import static spola.json.codec.JsonCodecs.nullable;
import static spola.json.codec.JsonCodecs.optional;
import static spola.json.codec.JsonCodecs.set;
import static spola.json.codec.Problems.problem;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import spola.json.JsonNull;
import spola.test.Suite;

/** Value types via map, null when encoding, and the collection combinators. */
final class MappingTests {

    private MappingTests() {
    }

    record Contact(String name, Model.Email email) {
    }

    static final ObjectCodec<Contact> CONTACT = JsonCodec.object(Contact::new,
            field("name", STRING, Contact::name),
            field("email", Model.EMAIL, Contact::email));

    record Address(String street, String city) {
    }

    record Customer(String name, Address address) {
    }

    static final ObjectCodec<Address> ADDRESS = JsonCodec.object(Address::new,
            field("street", STRING, Address::street),
            field("city", STRING, Address::city));

    static final ObjectCodec<Customer> CUSTOMER = JsonCodec.object(Customer::new,
            field("name", STRING, Customer::name),
            field("address", ADDRESS, Customer::address));

    static final ObjectCodec<Address> LOOSE_ADDRESS = JsonCodec.object(Address::new,
            field("street", STRING, Address::street),
            field("city", nullable(STRING), Address::city));

    static Suite tests() {
        return suite("mapping and null",
                test("map: an IllegalArgumentException from Email::new is a problem on the value's path", () -> {
                    assertEquals(List.of(problem("$.email", "invalid value: not an email address")),
                            Problems.of(() -> CONTACT.read("{\"name\":\"Ada\",\"email\":\"ada.example.com\"}")));
                    assertEquals(List.of(problem("$[1].email", "invalid value: not an email address"),
                            problem("$[2].email", "expected string but found number")),
                            Problems.of(() -> list(CONTACT).read("[{\"name\":\"a\",\"email\":\"a@b\"},"
                                    + "{\"name\":\"b\",\"email\":\"@\"},{\"name\":\"c\",\"email\":7}]")));
                }),
                test("map: encoding goes through Email::value", () -> {
                    var calls = new AtomicInteger();
                    var codec = STRING.map(Model.Email::new, (Model.Email e) -> {
                        calls.incrementAndGet();
                        return e.value();
                    });
                    assertEquals("\"ada@example.com\"", codec.write(new Model.Email("ada@example.com")));
                    assertEquals(1, calls.get());
                    assertEquals(new Contact("Ada", new Model.Email("ada@example.com")),
                            CONTACT.read("{\"name\":\"Ada\",\"email\":\"ada@example.com\"}"));
                }),
                test("map: an exception from the inverse fails the encoding with the path", () -> {
                    var codec = map(STRING.map(Model.Email::new, (Model.Email e) -> {
                        throw new IllegalStateException("cannot write");
                    }));
                    assertEquals(List.of(problem("$.a", "the mapping failed: cannot\\u2028write")),
                            Problems.of(() -> codec.encode(Map.of("a", new Model.Email("a@b")))));
                }),
                test("null in a required field when encoding fails with the path", () -> {
                    var customer = new Customer("Ada", new Address("Main St", null));
                    assertEquals(List.of(problem("$.address.city", "value is null (wrap the codec in nullable(...) to write null)")),
                            Problems.of(() -> CUSTOMER.encode(customer)));
                    assertEquals(List.of(problem("$.address", "value is null (wrap the codec in nullable(...) to write null)")),
                            Problems.of(() -> CUSTOMER.write(new Customer("Ada", null))));
                    assertEquals(List.of(problem("$", "value is null (wrap the codec in nullable(...) to write null)")),
                            Problems.of(() -> CUSTOMER.encode(null)));
                    assertEquals(List.of(problem("$[1]", "value is null (wrap the codec in nullable(...) to write null)")),
                            Problems.of(() -> list(INT).encode(Arrays.asList(1, null))));
                }),
                test("with nullable, null is written", () -> {
                    assertEquals("{\"street\":\"Main St\",\"city\":null}", LOOSE_ADDRESS.write(new Address("Main St", null)));
                    assertEquals(new Address("Main St", null), LOOSE_ADDRESS.read("{\"street\":\"Main St\",\"city\":null}"));
                    assertEquals("[1,null]", list(nullable(INT)).write(Arrays.asList(1, null)));
                    assertEquals(Arrays.asList(1, null), list(nullable(INT)).read("[1,null]"));
                    assertEquals("null", nullable(INT).write(null));
                }),
                test("optional: null is empty, and empty is written as null", () -> {
                    var codec = list(optional(INT));
                    assertEquals(List.of(Optional.of(1), Optional.empty()), codec.read("[1,null]"));
                    assertEquals("[1,null]", codec.write(List.of(Optional.of(1), Optional.empty())));
                    assertEquals(List.of(problem("$", "value is null; expected an Optional, empty or not")),
                            Problems.of(() -> optional(INT).encode(null)));
                }),
                test("set: keeps the order, rejects duplicates and is unmodifiable", () -> {
                    var codec = set(INT);
                    var value = codec.read("[3,1,2]");
                    assertEquals(List.of(3, 1, 2), new ArrayList<>(value));
                    assertEquals(List.of(problem("$[2]", "duplicate element; equal to the element at index 0"),
                            problem("$[3]", "duplicate element; equal to the element at index 0")),
                            Problems.of(() -> codec.read("[3,1,3,3.0]")));
                    assertThrows(UnsupportedOperationException.class, () -> value.add(4));
                    assertEquals("[3,1,2]", codec.write(value));
                    assertEquals(Set.of(), codec.read("[]"));
                }),
                test("map: keeps the member order, each bad value is a problem, and is unmodifiable", () -> {
                    var codec = map(INT);
                    var value = codec.read("{\"z\":1,\"a\":2}");
                    assertEquals(List.of("z", "a"), new ArrayList<>(value.keySet()));
                    assertEquals(List.of(problem("$.z", "expected int but found string"), problem("$[\"a b\"]",
                            "expected int but found boolean")), Problems.of(() -> codec.read("{\"z\":\"1\",\"a b\":true}")));
                    assertThrows(UnsupportedOperationException.class, () -> value.put("b", 3));
                    var ordered = new LinkedHashMap<String, Integer>();
                    ordered.put("b", 2);
                    ordered.put("a", 1);
                    assertEquals("{\"b\":2,\"a\":1}", codec.write(ordered));
                    var withNullKey = new java.util.HashMap<String, Integer>();
                    withNullKey.put(null, 1);
                    assertEquals(List.of(problem("$", "the map has a null key")), Problems.of(() -> codec.encode(withNullKey)));
                }),
                test("list: is unmodifiable and each bad element is a problem", () -> {
                    var value = list(INT).read("[1,2]");
                    assertThrows(UnsupportedOperationException.class, () -> value.add(3));
                    assertEquals(List.of(problem("$", "expected array but found object")), Problems.of(() -> list(INT).read("{}")));
                }),
                test("enumOf: two constants with the same name are an error when the codec is created", () -> {
                    var e = assertThrows(IllegalArgumentException.class, () -> enumOf(Model.Color.values(), c -> "same"));
                    assertEquals("\"RED\" and \"DARK_BLUE\" have the same name \"same\"", e.getMessage());
                }),
                test("enumOf: a constant not among the given values cannot be encoded", () -> {
                    var codec = enumOf(new Model.Status[] {Model.Status.ACTIVE});
                    assertEquals("\"ACTIVE\"", codec.write(Model.Status.ACTIVE));
                    assertEquals(List.of(problem("$", "\"ON_HOLD\" is not one of the values given to this enum codec")),
                            Problems.of(() -> codec.encode(Model.Status.ON_HOLD)));
                    assertEquals(List.of(problem("$", "unknown value \"ON_HOLD\"; expected one of \"ACTIVE\"")),
                            Problems.of(() -> codec.read("\"ON_HOLD\"")));
                }),
                test("JSON keeps any value, also null, and rejects Java null when encoding", () -> {
                    assertEquals(JsonNull.INSTANCE, JsonCodecs.JSON.read("null"));
                    assertEquals("[1,{\"a\":null}]", JsonCodecs.JSON.write(JsonCodecs.JSON.read("[1,{\"a\":null}]")));
                    assertEquals(1, Problems.of(() -> JsonCodecs.JSON.encode(null)).size());
                }),
                test("a lazy codec that resolves to itself fails clearly instead of overflowing the stack", () -> {
                    var holder = new ArrayList<JsonCodec<Integer>>();
                    holder.add(lazy(() -> holder.getFirst()));
                    var codec = holder.getFirst();
                    var e = assertThrows(IllegalStateException.class, () -> codec.read("1"));
                    assertTrue(e.getMessage().contains("lazy codecs in a row"), e.getMessage());
                    assertThrows(IllegalStateException.class, () -> codec.encode(1));
                    var nulls = JsonCodecs.<Integer>lazy(() -> null);
                    assertThrows(IllegalStateException.class, () -> nulls.read("1"));
                }),
                test("parse errors are not wrapped", () -> {
                    assertThrows(spola.json.JsonParseException.class, () -> CONTACT.read("{\"name\":"));
                    assertThrows(spola.json.JsonParseException.class, () -> CONTACT.read(new byte[] {(byte) 0xC0}));
                }));
    }
}
