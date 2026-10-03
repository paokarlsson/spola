package spola.validation;

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
