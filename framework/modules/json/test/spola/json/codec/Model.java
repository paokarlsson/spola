package spola.json.codec;

import static spola.json.codec.JsonCodec.field;
import static spola.json.codec.JsonCodec.optionalField;
import static spola.json.codec.JsonCodecs.BIG_DECIMAL;
import static spola.json.codec.JsonCodecs.BIG_INTEGER;
import static spola.json.codec.JsonCodecs.BOOLEAN;
import static spola.json.codec.JsonCodecs.DOUBLE;
import static spola.json.codec.JsonCodecs.DURATION;
import static spola.json.codec.JsonCodecs.INSTANT;
import static spola.json.codec.JsonCodecs.INT;
import static spola.json.codec.JsonCodecs.JSON;
import static spola.json.codec.JsonCodecs.LOCAL_DATE;
import static spola.json.codec.JsonCodecs.LOCAL_DATE_TIME;
import static spola.json.codec.JsonCodecs.LOCAL_TIME;
import static spola.json.codec.JsonCodecs.LONG;
import static spola.json.codec.JsonCodecs.OFFSET_DATE_TIME;
import static spola.json.codec.JsonCodecs.STRING;
import static spola.json.codec.JsonCodecs.UUID;
import static spola.json.codec.JsonCodecs.enumOf;
import static spola.json.codec.JsonCodecs.lazy;
import static spola.json.codec.JsonCodecs.list;
import static spola.json.codec.JsonCodecs.map;
import static spola.json.codec.JsonCodecs.nullable;
import static spola.json.codec.JsonCodecs.optional;
import static spola.json.codec.JsonCodecs.set;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import spola.json.JsonArray;
import spola.json.JsonNumber;
import spola.json.JsonObject;
import spola.json.JsonString;
import spola.json.JsonValue;

/** The types and codecs the codec tests share: a graph that uses every built-in codec and combinator. */
final class Model {

    private Model() {
    }

    enum Status { ACTIVE, ON_HOLD }

    enum Color { RED, DARK_BLUE }

    /** A value type with a compact constructor that rejects bad input. */
    record Email(String value) {
        Email {
            if (value.indexOf('@') < 1) {
                throw new IllegalArgumentException("not an email address");
            }
        }
    }

    record Scalars(String text, boolean flag, int count, long big, double ratio, BigDecimal price,
            BigInteger huge, UUID id) {
    }

    record Times(Instant at, LocalDate day, LocalTime time, LocalDateTime local, OffsetDateTime offset,
            Duration duration) {
    }

    record Node(String name, List<Node> children) {
    }

    sealed interface Shape permits Circle, Square, Group {
    }

    record Circle(double radius) implements Shape {
    }

    record Square(double side) implements Shape {
    }

    record Group(String label, List<Shape> shapes) implements Shape {
    }

    record Everything(Scalars scalars, Times times, JsonValue extra, List<Email> emails, Set<Status> statuses,
            Map<String, Color> colors, Optional<String> note, Optional<Integer> maybe, String nickname, int retries,
            Node tree, Shape shape) {
    }

    static final JsonCodec<Email> EMAIL = STRING.map(Email::new, Email::value);

    static final JsonCodec<Status> STATUS = enumOf(Status.values());

    static final JsonCodec<Color> COLOR = enumOf(Color.values(),
            c -> c.name().toLowerCase(Locale.ROOT).replace('_', '-'));

    static final ObjectCodec<Scalars> SCALARS = JsonCodec.object(Scalars::new,
            field("text", STRING, Scalars::text),
            field("flag", BOOLEAN, Scalars::flag),
            field("count", INT, Scalars::count),
            field("big", LONG, Scalars::big),
            field("ratio", DOUBLE, Scalars::ratio),
            field("price", BIG_DECIMAL, Scalars::price),
            field("huge", BIG_INTEGER, Scalars::huge),
            field("id", UUID, Scalars::id));

    // the general form (any number of fields): each field is the key of its value
    static final Field<Times, Instant> AT = field("at", INSTANT, Times::at);
    static final Field<Times, LocalDate> DAY = field("day", LOCAL_DATE, Times::day);
    static final Field<Times, LocalTime> TIME = field("time", LOCAL_TIME, Times::time);
    static final Field<Times, LocalDateTime> LOCAL = field("local", LOCAL_DATE_TIME, Times::local);
    static final Field<Times, OffsetDateTime> OFFSET = field("offset", OFFSET_DATE_TIME, Times::offset);
    static final Field<Times, Duration> DURATION_FIELD = field("duration", DURATION, Times::duration);

    static final ObjectCodec<Times> TIMES = JsonCodec.object(List.of(AT, DAY, TIME, LOCAL, OFFSET, DURATION_FIELD),
            v -> new Times(v.get(AT), v.get(DAY), v.get(TIME), v.get(LOCAL), v.get(OFFSET), v.get(DURATION_FIELD)))
            .ignoringUnknown();

    static final ObjectCodec<Node> NODE = JsonCodec.object(Node::new,
            field("name", STRING, Node::name),
            field("children", list(lazy(() -> Model.NODE)), Node::children));

    static final ObjectCodec<Circle> CIRCLE_CODEC = JsonCodec.object(Circle::new, field("radius", DOUBLE, Circle::radius));
    static final ObjectCodec<Square> SQUARE_CODEC = JsonCodec.object(Square::new, field("side", DOUBLE, Square::side));
    static final ObjectCodec<Group> GROUP_CODEC = JsonCodec.object(Group::new,
            field("label", STRING, Group::label),
            field("shapes", list(lazy(() -> Model.SHAPE)), Group::shapes));

    static final Variant<Shape, Circle> CIRCLE = Variant.of("circle", CIRCLE_CODEC);
    static final Variant<Shape, Square> SQUARE = Variant.of("square", SQUARE_CODEC);
    static final Variant<Shape, Group> GROUP = Variant.of("group", GROUP_CODEC);

    // A new permitted subtype of Shape without a case here does not compile:
    //   "the switch expression does not cover all possible input values"
    static final JsonCodec<Shape> SHAPE = JsonCodec.sealed("type",
            shape -> switch (shape) {
                case Circle c -> CIRCLE.tag(c);
                case Square s -> SQUARE.tag(s);
                case Group g -> GROUP.tag(g);
            },
            CIRCLE, SQUARE, GROUP);

    static final ObjectCodec<Everything> EVERYTHING = JsonCodec.object(Everything::new,
            field("scalars", SCALARS, Everything::scalars),
            field("times", TIMES, Everything::times),
            field("extra", JSON, Everything::extra),
            field("emails", list(EMAIL), Everything::emails),
            field("statuses", set(STATUS), Everything::statuses),
            field("colors", map(COLOR), Everything::colors),
            optionalField("note", STRING, Everything::note),
            field("maybe", optional(INT), Everything::maybe),
            field("nickname", nullable(STRING), Everything::nickname),
            field("retries", INT, Everything::retries).orElse(3),
            field("tree", NODE, Everything::tree),
            field("shape", SHAPE, Everything::shape));

    static Scalars scalars() {
        return new Scalars("héj \"quoted\" \n  😀", true, Integer.MIN_VALUE, Long.MAX_VALUE,
                -0.1, new BigDecimal("1.10"), new BigInteger("-123456789012345678901234567890"),
                java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
    }

    static Times times() {
        return new Times(Instant.parse("2026-10-02T09:18:00.123456789Z"), LocalDate.of(2024, 2, 29),
                LocalTime.of(23, 59, 59, 1), LocalDateTime.of(2026, 10, 2, 11, 18),
                OffsetDateTime.of(2026, 10, 2, 11, 18, 30, 0, ZoneOffset.ofHoursMinutes(5, 30)),
                Duration.ofHours(-1).plusMillis(1));
    }

    static Node tree(int depth, int width) {
        if (depth == 0) {
            return new Node("leaf", List.of());
        }
        var children = new ArrayList<Node>();
        for (int i = 0; i < width; i++) {
            children.add(tree(depth - 1, width));
        }
        return new Node("n" + depth, List.copyOf(children));
    }

    static Everything everything() {
        var statuses = new LinkedHashSet<Status>(List.of(Status.ON_HOLD, Status.ACTIVE));
        var colors = new LinkedHashMap<String, Color>();
        colors.put("sky", Color.DARK_BLUE);
        colors.put("the \"rose\"", Color.RED);
        var extra = JsonObject.of("any", JsonArray.of(JsonNumber.of(1), JsonString.of("x")), "n", spola.json.JsonNull.INSTANCE);
        Shape shape = new Group("g", List.of(new Circle(1.5), new Square(2), new Group("inner", List.of())));
        return new Everything(scalars(), times(), extra, List.of(new Email("a@b.se"), new Email("c@d.se")),
                statuses, colors, Optional.of("note"), Optional.of(7), null, 5, tree(3, 2), shape);
    }
}
