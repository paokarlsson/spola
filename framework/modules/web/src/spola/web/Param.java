package spola.web;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * A typed conversion of a path variable or a query parameter, the counterpart of the type
 * conversion Spring applies to {@code @PathVariable} and {@code @RequestParam}:
 *
 * <pre>{@code
 * long id = request.pathParam("id", Param.LONG);
 * int limit = request.query("limit", Param.INT).orElse(20);
 * Status status = request.requiredQuery("status", Param.enumOf(Status.class));
 * }</pre>
 *
 * <p>The built-in conversions are strict: no surrounding whitespace, no {@code +} sign, no
 * other digits than ASCII {@code 0}–{@code 9}, no rounding ({@code "1.0"} is not an
 * {@link #INT}), no values out of range. A value that does not convert gives a
 * {@link WebException} 400 whose detail names the parameter, the expected type and the value
 * (quoted, at most 40 characters, escaped).
 *
 * @param <T> the converted type
 */
public final class Param<T> {

    /** The text as it is. */
    public static final Param<String> STRING = new Param<>("string", text -> text);

    /** {@code -?[0-9]+} within {@code int}. */
    public static final Param<Integer> INT = new Param<>("int", text -> Integer.parseInt(integer(text)));

    /** {@code -?[0-9]+} within {@code long}. */
    public static final Param<Long> LONG = new Param<>("long", text -> Long.parseLong(integer(text)));

    /** Exactly {@code true} or {@code false}. */
    public static final Param<Boolean> BOOLEAN = new Param<>("boolean", text -> switch (text) {
        case "true" -> Boolean.TRUE;
        case "false" -> Boolean.FALSE;
        default -> throw new IllegalArgumentException("not true or false");
    });

    /** The 36-character form {@code 8-4-4-4-12} with hex digits in either case. */
    public static final Param<java.util.UUID> UUID = new Param<>("UUID", Param::uuid);

    /** ISO 8601 {@code yyyy-MM-dd} with a real date ({@code 2024-02-30} is not one). */
    public static final Param<LocalDate> LOCAL_DATE = new Param<>("date (yyyy-MM-dd)", Param::date);

    /** {@code -?[0-9]+(\.[0-9]+)?}, at most 100 characters; no exponent. */
    public static final Param<BigDecimal> BIG_DECIMAL = new Param<>("decimal", Param::decimal);

    private final String typeName;
    private final Function<String, ? extends T> parser;

    private Param(String typeName, Function<String, ? extends T> parser) {
        this.typeName = typeName;
        this.parser = parser;
    }

    /**
     * A conversion for an own type. The parser fails by throwing any {@link RuntimeException}
     * (its message is not shown to the client); the router then answers 400.
     *
     * <pre>{@code
     * Param<OrderId> ORDER_ID = Param.of("order id", OrderId::parse);
     * }</pre>
     *
     * @param typeName the type's name in problem details ("expected order id")
     * @param parser   the conversion; must not return {@code null}
     * @param <T>      the converted type
     * @return the conversion
     */
    public static <T> Param<T> of(String typeName, Function<String, ? extends T> parser) {
        return new Param<>(Objects.requireNonNull(typeName, "typeName"), Objects.requireNonNull(parser, "parser"));
    }

    /**
     * An enum constant by its exact name (case-sensitive). The constants are read once, here.
     *
     * @param type the enum class
     * @param <E>  the enum type
     * @return the conversion
     */
    public static <E extends Enum<E>> Param<E> enumOf(Class<E> type) {
        var byName = new HashMap<String, E>();
        for (var constant : type.getEnumConstants()) {
            byName.put(constant.name(), constant);
        }
        var constants = Map.copyOf(byName);
        return new Param<>(type.getSimpleName(), text -> {
            var constant = constants.get(text);
            if (constant == null) {
                throw new IllegalArgumentException("no such constant");
            }
            return constant;
        });
    }

    /**
     * The type's name, as shown in problem details.
     *
     * @return the name
     */
    public String typeName() {
        return typeName;
    }

    /**
     * Converts {@code text}.
     *
     * @param kind  {@code "path parameter"} or {@code "query parameter"}, for the detail
     * @param name  the parameter's name, for the detail
     * @param text  the decoded value
     * @return the value
     * @throws WebException 400 if the value does not convert
     */
    T convert(String kind, String name, String text) {
        T value;
        try {
            value = parser.apply(text);
        } catch (WebException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new WebException(400, "Bad Request", "Invalid " + kind + " " + Text.quote(name) + ": expected "
                    + Text.escape(typeName, Text.MAX_QUOTED) + ", got " + Text.quote(text) + ".");
        }
        if (value == null) {
            throw new IllegalStateException("the conversion to " + Text.escape(typeName, Text.MAX_QUOTED)
                    + " returned null for " + kind + " " + Text.quote(name));
        }
        return value;
    }

    /** {@code text} if it is {@code -?[0-9]+}; otherwise an exception. */
    private static String integer(String text) {
        int start = text.startsWith("-") ? 1 : 0;
        if (text.length() == start) {
            throw new IllegalArgumentException("no digits");
        }
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("not a digit");
            }
        }
        return text;
    }

    private static java.util.UUID uuid(String text) {
        if (text.length() != 36) {
            throw new IllegalArgumentException("not 36 characters");
        }
        for (int i = 0; i < 36; i++) {
            char c = text.charAt(i);
            boolean dash = i == 8 || i == 13 || i == 18 || i == 23;
            boolean ok = dash ? c == '-' : (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) {
                throw new IllegalArgumentException("not a UUID");
            }
        }
        return java.util.UUID.fromString(text);
    }

    private static LocalDate date(String text) {
        if (text.length() != 10 || text.charAt(4) != '-' || text.charAt(7) != '-') {
            throw new IllegalArgumentException("not yyyy-MM-dd");
        }
        for (int i = 0; i < 10; i++) {
            char c = text.charAt(i);
            if (i != 4 && i != 7 && (c < '0' || c > '9')) {
                throw new IllegalArgumentException("not yyyy-MM-dd");
            }
        }
        try {
            return LocalDate.of(Integer.parseInt(text.substring(0, 4)), Integer.parseInt(text.substring(5, 7)),
                    Integer.parseInt(text.substring(8, 10)));
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("not a date");
        }
    }

    private static BigDecimal decimal(String text) {
        if (text.length() > 100) {
            throw new IllegalArgumentException("too long");
        }
        int dot = text.indexOf('.');
        integer(dot < 0 ? text : text.substring(0, dot));
        if (dot >= 0) {
            var fraction = text.substring(dot + 1);
            if (fraction.isEmpty() || fraction.startsWith("-")) {
                throw new IllegalArgumentException("no fraction digits");
            }
            integer(fraction);
        }
        return new BigDecimal(text);
    }

    /** {@code Param[typeName]}. */
    @Override
    public String toString() {
        return "Param[" + typeName + "]";
    }
}
