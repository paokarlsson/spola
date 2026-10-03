# spola.validation

Validering i Spola: validatorer skrivs som vanlig Java-kod av getters (method references) och
`Constraint`-värden, utan annoteringar och utan reflektion. Modulen ersätter det Spring
Boot-applikationer använder Jakarta Bean Validation (Hibernate Validator) till: `@NotNull`,
`@NotBlank`, `@Size`, `@Min`, `@Email`, `@Pattern`, `@Past`, `@Valid` på nästlade objekt och
listor, klassnivåregler (`@AssertTrue`), Springs `org.springframework.validation.Validator` och
`ConstraintViolationException`. Modulen är fristående och kräver ingen annan Spola-modul.

- `Validator<T>` är kärnan, ett funktionellt gränssnitt: `validate(value, errors)`. `check(value)`
  ger hela rapporten (`Validated.Valid` eller `Validated.Invalid`), `require(value)` ger värdet
  eller kastar `ValidationException`.
- `Validator.<T>builder()` bygger en oföränderlig validator av fält, nästlade validatorer,
  listor och regler. `Validator.lazy(...)` gör rekursiva strukturer möjliga och
  `Validator.of(...)` validerar ett enkelt värde.
- `Errors` samlar överträdelserna under en validering, med sökvägar i samma notation som
  `spola.json.codec` (`$.lines[3].qty`).
- `Violation(path, code, message)` bär aldrig det avvisade värdet.
- Typfel ger kompileringsfel: `field("age", Person::age, notBlank())` för en `int` kompilerar
  inte.

## Ett komplett exempel

En order med kund, e-post, en valfri anteckning, orderrader (nästlad validator) och en
korsfältsregel för datum.

### Spring Boot

```java
public record OrderLine(
        @NotBlank @Pattern(regexp = "[A-Z]{3}-[0-9]{4}") String sku,
        @Min(1) @Max(99) int quantity) {
}

public record CreateOrder(
        @NotBlank @Size(max = 100) String customer,
        @NotBlank @Email String email,
        Optional<@Size(max = 500) String> note,
        @NotNull @Size(min = 1, max = 50) List<@Valid OrderLine> lines,
        @NotNull @PastOrPresent LocalDate orderDate,
        @NotNull LocalDate deliveryDate) {

    @AssertTrue(message = "must not be before the order date")
    public boolean isDeliveryAfterOrder() {
        return orderDate == null || deliveryDate == null || !deliveryDate.isBefore(orderDate);
    }
}

@Service
@Validated
class OrderService {
    Order create(@Valid CreateOrder order) { // ConstraintViolationException om ordern är ogiltig
        ...
    }
}
```

### Spola

```java
import static spola.validation.Constraints.email;
import static spola.validation.Constraints.maxLength;
import static spola.validation.Constraints.matches;
import static spola.validation.Constraints.notBlank;
import static spola.validation.Constraints.notNull;
import static spola.validation.Constraints.pastOrPresent;
import static spola.validation.Constraints.range;
import static spola.validation.Constraints.size;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** The README example: a new order, validated as plain Java code. */
public final class OrderValidation {

    /**
     * One line of an order.
     *
     * @param sku      the article, such as {@code ABC-1234}
     * @param quantity how many, 1 to 99
     */
    public record OrderLine(String sku, int quantity) {
    }

    /**
     * A new order, as it arrives from a client.
     *
     * @param customer     the customer's name
     * @param email        where the confirmation goes
     * @param note         an optional note
     * @param lines        what is ordered, 1 to 50 lines
     * @param orderDate    when it was ordered: today or earlier
     * @param deliveryDate when it should be delivered: not before the order date
     */
    public record CreateOrder(String customer, String email, Optional<String> note, List<OrderLine> lines,
                              LocalDate orderDate, LocalDate deliveryDate) {
    }

    private static final Pattern SKU = Pattern.compile("[A-Z]{3}-[0-9]{4}");

    /** An order line: a SKU and a quantity. Immutable, so one is shared by every order. */
    public static final Validator<OrderLine> ORDER_LINE = Validator.<OrderLine>builder()
            .field("sku", OrderLine::sku, notBlank(), matches(SKU))
            .field("quantity", OrderLine::quantity, range(1, 99))
            .build();

    /**
     * The order validator. Time comes from an explicit supplier, so tests use a fixed clock.
     *
     * @param today the current date, such as {@code () -> LocalDate.now(clock)}
     * @return the validator
     */
    public static Validator<CreateOrder> createOrder(Supplier<LocalDate> today) {
        return Validator.<CreateOrder>builder()
                .field("customer", CreateOrder::customer, notBlank(), maxLength(100))
                .field("email", CreateOrder::email, notBlank(), email())
                .optional("note", CreateOrder::note, maxLength(500))
                .field("lines", CreateOrder::lines, notNull(), size(1, 50))
                .eachNested("lines", CreateOrder::lines, ORDER_LINE)
                .field("orderDate", CreateOrder::orderDate, notNull(), pastOrPresent(today))
                .field("deliveryDate", CreateOrder::deliveryDate, notNull())
                .rule("deliveryDate", OrderValidation::deliveredAfterOrdered,
                        "deliveryAfterOrder", "must not be before the order date")
                .build();
    }

    private static boolean deliveredAfterOrdered(CreateOrder order) {
        return order.orderDate() == null || order.deliveryDate() == null
                || !order.deliveryDate().isBefore(order.orderDate());
    }

    private OrderValidation() {
    }
}
```

Användning, med en uttrycklig klocka:

```java
Validator<CreateOrder> createOrder = OrderValidation.createOrder(() -> LocalDate.now(clock));

switch (createOrder.check(order)) {
    case Validated.Valid<CreateOrder>(var valid) -> save(valid);
    case Validated.Invalid<CreateOrder>(var violations, var count) -> report(violations);
}

CreateOrder valid = createOrder.require(order); // eller ValidationException
```

En order med fel i flera fält, i två orderrader och i datumregeln ger överträdelserna i
deklarationsordning, djupet först (exemplet är ett test, `OrderExampleTests`):

```
$.customer            notBlank            must not be blank
$.email               email               must be a well-formed email address
$.note                length              length must be at most 500
$.lines[1].sku        pattern             must match the required format
$.lines[1].quantity   range               must be between 1 and 99
$.lines[2].sku        notBlank            must not be blank
$.lines[2].sku        pattern             must match the required format
$.lines[2].quantity   range               must be between 1 and 99
$.orderDate           pastOrPresent       must be in the past or in the present
$.deliveryDate        deliveryAfterOrder  must not be before the order date
```

`ValidationException.getMessage()` blir en kort rad:

```
10 violations: $.customer: must not be blank; $.email: must be a well-formed email address; $.note: length must be at most 500; ... (7 more)
```

## Byggarens steg

| Steg | Motsvarar | Sökväg |
|---|---|---|
| `field(name, getter, constraints...)` | annoteringar på ett fält | `$.name` |
| `optional(name, getter, constraints...)` | `Optional<@Size(max = 500) String>` | `$.name` (tomt `Optional` är giltigt) |
| `nested(name, getter, validator)` | `@Valid` på ett fält | `$.name.…` (`null` hoppas över) |
| `each(name, getter, constraints...)` | `List<@NotBlank String>` | `$.name[i]` (ett `null`-element ges till constraints) |
| `eachNested(name, getter, validator)` | `List<@Valid X>` | `$.name[i].…` (`null`-element hoppas över) |
| `rule(test, code, message)` | `@AssertTrue` på klassen, klassnivå-constraint | `$` |
| `rule(name, test, code, message)` | `@AssertTrue` för en korsfältsregel | `$.name` |
| `include(validator)` | en återanvänd uppsättning regler | samma sökväg |
| `when(condition, validator)` | valideringsgrupper | samma sökväg |

En egen validator är en lambda som använder `Errors`:

```java
Validator<Map<String, Integer>> stock = (map, errors) -> map.forEach((sku, qty) -> {
    if (qty < 0) {
        errors.atKey(sku).reject("negative", "must not be negative");
    }
});
```

En egen constraint är `Constraint.of(code, predikat, meddelande)`. Predikatet anropas aldrig
med `null`.

## Från Jakarta-annotering till Spola-constraint

Alla finns i `Constraints` (importera statiskt). `code` är en stabil nyckel och meddelandet en
engelsk standardtext. Båda kan bytas med `withCode`/`withMessage`.

| Jakarta | Spola | code | meddelande |
|---|---|---|---|
| `@NotNull` | `notNull()` | `notNull` | `must not be null` |
| `@NotBlank` | `notBlank()` | `notBlank` | `must not be blank` |
| `@NotEmpty` på text, samling, map | `notEmpty()`, `notEmptyCollection()`, `notEmptyMap()` | `notEmpty` | `must not be empty` |
| `@Size(min, max)` på text | `length(min, max)`, `minLength(n)`, `maxLength(n)` | `length` | `length must be between 1 and 100`, `length must be at least 1`, `length must be at most 100` |
| `@Size(min, max)` på samling, map | `size(min, max)`, `mapSize(min, max)` | `size` | `size must be between 1 and 50` |
| `@Min`, `@Max` | `min(long)`, `max(long)`, `range(long, long)` | `min`, `max`, `range` | `must be at least 1`, `must be at most 10`, `must be between 1 and 10` |
| `@DecimalMin(value, inclusive)` | `decimalMin(BigDecimal, boolean)` | `decimalMin` | `must be at least 0.5` / `must be greater than 0.5` |
| `@DecimalMax(value, inclusive)` | `decimalMax(BigDecimal, boolean)` | `decimalMax` | `must be at most 9.99` / `must be less than 9.99` |
| `@Positive`, `@PositiveOrZero` | `positive()`, `positiveOrZero()` | samma | `must be greater than 0`, `must be greater than or equal to 0` |
| `@Negative`, `@NegativeOrZero` | `negative()`, `negativeOrZero()` | samma | `must be less than 0`, `must be less than or equal to 0` |
| `@Digits(integer, fraction)` | `digits(int, int)` | `digits` | `must have at most 3 integer digits and 2 fraction digits` |
| `@Pattern(regexp)` | `matches(Pattern)` (hela värdet) | `pattern` | `must match the required format` |
| `@Email` | `email()` | `email` | `must be a well-formed email address` |
| enum eller egen | `oneOf(Collection)` | `oneOf` | `must be one of the allowed values` |
| `@Past`, `@PastOrPresent` | `past(now)`, `pastOrPresent(now)` | samma | `must be in the past`, `must be in the past or in the present` |
| `@Future`, `@FutureOrPresent` | `future(now)`, `futureOrPresent(now)` | samma | `must be in the future`, `must be in the future or in the present` |
| `@Valid` | `nested`, `eachNested` | | |
| `@AssertTrue` | `rule` | egen | egen |
| `ConstraintViolationException` | `ValidationException` | | |

## Avvikelser från Jakarta Bean Validation

| Område | Jakarta / Hibernate Validator | Spola |
|---|---|---|
| Avvisat värde | `ConstraintViolation.getInvalidValue()` bär värdet | Inget avvisat värde i överträdelsen: `Violation` har bara `path`, `code` och `message`, så att lösenord och personuppgifter inte hamnar i loggar eller svar av misstag. |
| Längd på text | `@Size` räknar `char` (UTF-16-enheter) | `length`/`minLength`/`maxLength` räknar kodpunkter: en emoji räknas som 1. |
| Grupper | `groups = ...`, `@GroupSequence`, `@ConvertGroup` | Inga grupper. En grupp är en egen validator, som tas med med `include` eller villkorligt med `when`. |
| Meddelanden | i18n med `ValidationMessages.properties`, interpolering (`{min}`) | Ingen i18n och ingen interpolering: en stabil `code` och en färdig engelsk text. Översättning kan bygga på `code`. |
| Tid | `@Past` m.fl. mot systemklockan (`ClockProvider`) | Tid jämförs mot en uttrycklig klocka: en `Supplier` (`() -> LocalDate.now(clock)`) som anges när constrainten skapas. |
| `@Email` | Hibernates regex: tillåter bl.a. citerade lokala delar, IP-literaler och icke-ASCII | `email()` är enkel och bestämd: exakt ett `@`, `dot-atom` på 1–64 tecken, domän på 1–253 tecken med LDH-etiketter på 1–63 tecken, bara ASCII. `a@b` godtas som i Hibernate. |
| Antal överträdelser | alla rapporteras | Högst 100 behålls (samma gräns som `spola.json.codec` och `Problem.MAX_ERRORS`), alla räknas (`count`). |
| Undantag från användarkod | slås in i `ValidationException` | Undantag från getters, predikat och egna validatorer släpps igenom oförändrade. Ingen halv rapport returneras. |
| Sökvägar | `lines[3].qty`, `isDeliveryAfterOrder` blir `deliveryAfterOrder` | Kodekens notation: `$.lines[3].qty`, `$["first name"]`. En korsfältsregel rapporteras på det fält du anger. |
| `null` i en lista | `List<@NotNull String>` | `each(name, getter, notNull())`. `eachNested` hoppar över `null`-element, som `@Valid`. |
| Tal | `@Min` på `double` jämförs med avrundning; NaN är giltigt i vissa versioner | Exakt jämförelse: `Double`/`Float` via sin kortaste decimaltext. NaN och oändligt är alltid ogiltiga. |
| Cykler | Hibernate följer inte samma objekt två gånger | Ingen cykeldetektering: en graf som innehåller sig själv valideras tills stacken tar slut. |
| Typfel | `@NotBlank` på `int` upptäcks vid körning (`UnexpectedTypeException`) | Kompileringsfel. |
