package spola.validation;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertSame;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.validation.Constraints.maxLength;
import static spola.validation.Constraints.notBlank;
import static spola.validation.Constraints.notNull;
import static spola.validation.Constraints.positive;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import spola.test.Suite;
import spola.validation.OrderValidation.CreateOrder;
import spola.validation.OrderValidation.OrderLine;

/** Criteria 6 and 13: the README's {@code CreateOrder} example and the builder's steps. */
final class OrderExampleTests {

    private OrderExampleTests() {
    }

    static final LocalDate TODAY = LocalDate.of(2026, 10, 3);
    static final Validator<CreateOrder> CREATE_ORDER = OrderValidation.createOrder(() -> TODAY);

    static CreateOrder good() {
        return new CreateOrder("Ada Lovelace", "ada@example.org", Optional.of("ring the bell"),
                List.of(new OrderLine("ABC-1234", 2), new OrderLine("XYZ-0001", 99)), TODAY, TODAY.plusDays(3));
    }

    static CreateOrder bad() {
        return new CreateOrder(" ", "ada@", Optional.of("n".repeat(501)),
                List.of(new OrderLine("ABC-1234", 1), new OrderLine("abc-1234", 0), new OrderLine("", 100)),
                TODAY.plusDays(1), TODAY);
    }

    static final List<Violation> BAD = List.of(
            new Violation("$.customer", "notBlank", "must not be blank"),
            new Violation("$.email", "email", "must be a well-formed email address"),
            new Violation("$.note", "length", "length must be at most 500"),
            new Violation("$.lines[1].sku", "pattern", "must match the required format"),
            new Violation("$.lines[1].quantity", "range", "must be between 1 and 99"),
            new Violation("$.lines[2].sku", "notBlank", "must not be blank"),
            new Violation("$.lines[2].sku", "pattern", "must match the required format"),
            new Violation("$.lines[2].quantity", "range", "must be between 1 and 99"),
            new Violation("$.orderDate", "pastOrPresent", "must be in the past or in the present"),
            new Violation("$.deliveryDate", "deliveryAfterOrder", "must not be before the order date"));

    record Address(String city) {
    }

    record Customer(Optional<String> nickname, Address address, List<Address> previous, List<String> tags) {
    }

    static final Validator<Address> ADDRESS = Validator.<Address>builder()
            .field("city", Address::city, notBlank())
            .build();

    record Node(String name, List<Node> children) {
    }

    /** A recursive validator: a tree of nodes, each with a name. */
    static final class Tree {
        static final Validator<Node> NODE = Validator.<Node>builder()
                .field("name", Node::name, notBlank())
                .eachNested("children", Node::children, Validator.lazy(() -> Tree.NODE))
                .build();

        private Tree() {
        }
    }

    record Payment(String method, String iban, Integer amount) {
    }

    static Suite tests() {
        return suite("CreateOrder (README example) and the builder",
                test("a valid order gives Valid with the same instance", () -> {
                    var order = good();
                    var result = CREATE_ORDER.check(order);
                    var valid = (Validated.Valid<CreateOrder>) result;
                    assertSame(order, valid.value());
                    assertSame(order, CREATE_ORDER.require(order));
                }),
                test("errors in fields, two order lines and the date rule, in declaration order, depth first", () -> {
                    var result = CREATE_ORDER.check(bad());
                    assertEquals(new Validated.Invalid<CreateOrder>(BAD, BAD.size()), result);
                    var text = switch (result) {
                        case Validated.Valid<CreateOrder> valid -> "valid " + valid.value();
                        case Validated.Invalid<CreateOrder>(var violations, var count) -> count + " " + violations.size();
                    };
                    assertEquals("10 10", text);
                }),
                test("require throws ValidationException with the same list", () -> {
                    var e = assertThrows(ValidationException.class, () -> CREATE_ORDER.require(bad()));
                    assertEquals(BAD, e.violations());
                    assertEquals(BAD.size(), e.count());
                    assertEquals("10 violations: $.customer: must not be blank; $.email: must be a well-formed email"
                            + " address; $.note: length must be at most 500; ... (7 more)", e.getMessage());
                }),
                test("lines missing: notNull on the field, and eachNested skips the null list", () -> {
                    var o = good();
                    var noLines = new CreateOrder(o.customer(), o.email(), Optional.empty(), null, o.orderDate(),
                            o.deliveryDate());
                    assertEquals(new Validated.Invalid<CreateOrder>(List.of(
                            new Violation("$.lines", "notNull", "must not be null")), 1), CREATE_ORDER.check(noLines));
                    var empty = new CreateOrder(o.customer(), o.email(), Optional.empty(), List.of(), o.orderDate(),
                            o.deliveryDate());
                    assertEquals(new Validated.Invalid<CreateOrder>(List.of(
                            new Violation("$.lines", "size", "size must be between 1 and 50")), 1), CREATE_ORDER.check(empty));
                }),
                test("optional with an empty Optional, nested and eachNested with null give no violations", () -> {
                    var validator = Validator.<Customer>builder()
                            .optional("nickname", Customer::nickname, notBlank(), maxLength(3))
                            .nested("address", Customer::address, ADDRESS)
                            .eachNested("previous", Customer::previous, ADDRESS)
                            .build();
                    var none = new Customer(Optional.empty(), null, null, null);
                    assertEquals(new Validated.Valid<>(none), validator.check(none));
                    var nullOptional = new Customer(null, null, Arrays.asList(null, new Address("Lund"), null), null);
                    assertEquals(new Validated.Valid<>(nullOptional), validator.check(nullOptional));
                    var some = new Customer(Optional.of("    "), new Address(""), Arrays.asList(null, new Address(" ")), null);
                    assertEquals(List.of(
                            new Violation("$.nickname", "notBlank", "must not be blank"),
                            new Violation("$.nickname", "length", "length must be at most 3"),
                            new Violation("$.address.city", "notBlank", "must not be blank"),
                            new Violation("$.previous[1].city", "notBlank", "must not be blank")),
                            ((Validated.Invalid<Customer>) validator.check(some)).violations());
                    assertEquals(new Validated.Valid<Customer>(null), validator.check(null));
                }),
                test("each(..., notNull()) reports a null element at [i]", () -> {
                    var validator = Validator.<Customer>builder()
                            .each("tags", Customer::tags, notNull(), notBlank())
                            .build();
                    var customer = new Customer(Optional.empty(), null, null, Arrays.asList("a", null, " "));
                    assertEquals(List.of(
                            new Violation("$.tags[1]", "notNull", "must not be null"),
                            new Violation("$.tags[1]", "notBlank", "must not be blank"),
                            new Violation("$.tags[2]", "notBlank", "must not be blank")),
                            ((Validated.Invalid<Customer>) validator.check(customer)).violations());
                    var noTags = new Customer(Optional.empty(), null, null, null);
                    assertEquals(new Validated.Valid<>(noTags), validator.check(noTags));
                }),
                test("include runs another validator here; when runs it only when the condition holds", () -> {
                    var amount = Validator.<Payment>builder().field("amount", Payment::amount, notNull(), positive()).build();
                    var iban = Validator.<Payment>builder().field("iban", Payment::iban, notBlank()).build();
                    var payment = Validator.<Payment>builder()
                            .field("method", Payment::method, notBlank())
                            .include(amount)
                            .when(p -> "transfer".equals(p.method()), iban)
                            .rule(p -> !"card".equals(p.method()) || p.iban() == null, "noIbanForCard",
                                    "a card payment has no IBAN")
                            .build();
                    assertEquals(List.of(
                            new Violation("$.amount", "positive", "must be greater than 0"),
                            new Violation("$.iban", "notBlank", "must not be blank")),
                            ((Validated.Invalid<Payment>) payment.check(new Payment("transfer", "", 0))).violations());
                    assertEquals(List.of(new Violation("$", "noIbanForCard", "a card payment has no IBAN")),
                            ((Validated.Invalid<Payment>) payment.check(new Payment("card", "", 5))).violations());
                    var cash = new Payment("cash", null, 5);
                    assertEquals(new Validated.Valid<>(cash), payment.check(cash));
                }),
                test("Validator.lazy on a recursive tree reports an error at depth 50 with the right path", () -> {
                    var node = new Node("", List.of());
                    for (int i = 0; i < 50; i++) {
                        node = new Node("level" + (49 - i), List.of(new Node("leaf", List.of()), node));
                    }
                    var expected = "$" + ".children[1]".repeat(50) + ".name";
                    assertEquals(List.of(new Violation(expected, "notBlank", "must not be blank")),
                            ((Validated.Invalid<Node>) Tree.NODE.check(node)).violations());
                    var fine = new Node("root", List.of(new Node("a", List.of())));
                    assertEquals(new Validated.Valid<>(fine), Tree.NODE.check(fine));
                }),
                test("a built validator does not change when its builder gets more steps", () -> {
                    var builder = Validator.<Address>builder().field("city", Address::city, notNull());
                    var first = builder.build();
                    builder.field("city", Address::city, notBlank());
                    var second = builder.build();
                    var blank = new Address(" ");
                    assertEquals(new Validated.Valid<>(blank), first.check(blank));
                    assertTrue(second.check(blank) instanceof Validated.Invalid<Address>);
                }));
    }

    /** The README shows OrderValidation.java as it is compiled and tested here. */
    static Suite readme() {
        return suite("README",
                test("the README shows OrderValidation.java as it is compiled and tested here", () -> {
                    var readme = Files.readString(Path.of("modules/validation/README.md"));
                    var source = Files.readString(Path.of("modules/validation/test/spola/validation/OrderValidation.java"));
                    var header = "package spola.validation;\n\n";
                    assertTrue(source.startsWith(header));
                    var block = "```java\n" + source.substring(header.length()) + "```\n";
                    assertTrue(readme.contains(block), "the README's Spola example differs from OrderValidation.java");
                }),
                test("the README has the Spring Boot side, the annotation table and the deviation table", () -> {
                    var readme = Files.readString(Path.of("modules/validation/README.md"));
                    for (var text : List.of("@Valid", "@AssertTrue", "@NotBlank", "@Email", "@PastOrPresent",
                            "## Från Jakarta-annotering till Spola-constraint", "## Avvikelser från Jakarta Bean Validation")) {
                        assertTrue(readme.contains(text), "the README does not mention " + text);
                    }
                    var rows = new ArrayList<String>();
                    for (var line : readme.split("\n")) {
                        if (line.startsWith("| ")) {
                            rows.add(line);
                        }
                    }
                    for (var topic : List.of("Inget avvisat värde", "kodpunkter", "`include`", "Ingen i18n",
                            "uttrycklig klocka", "`email()`", "Högst 100", "släpps igenom oförändrade")) {
                        assertTrue(rows.stream().anyMatch(r -> r.contains(topic)), "no table row about " + topic);
                    }
                }));
    }
}
