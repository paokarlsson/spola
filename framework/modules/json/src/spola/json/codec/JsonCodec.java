package spola.json.codec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import spola.json.Json;
import spola.json.JsonLimits;
import spola.json.JsonParseException;
import spola.json.JsonValue;

/**
 * A two-way mapping between Java values of type {@code T} and JSON: the replacement for
 * Jackson's {@code ObjectMapper.readValue}/{@code writeValueAsString} and its annotations.
 * Codecs are values, built in plain Java from method references and lambdas; nothing is
 * discovered by reflection or naming conventions, and everything a codec knows comes from its
 * arguments.
 *
 * <pre>{@code
 * import static spola.json.codec.JsonCodec.field;
 * import static spola.json.codec.JsonCodec.optionalField;
 * import static spola.json.codec.JsonCodecs.*;
 *
 * record Email(String value) {
 *     Email {
 *         if (!value.contains("@")) throw new IllegalArgumentException("not an email address");
 *     }
 * }
 * record Person(String name, Email email, Optional<String> note, List<Instant> visits) {}
 *
 * static final JsonCodec<Email> EMAIL = STRING.map(Email::new, Email::value);
 * static final ObjectCodec<Person> PERSON = JsonCodec.object(Person::new,
 *         field("name", STRING, Person::name),
 *         field("email", EMAIL, Person::email),
 *         optionalField("note", STRING, Person::note),
 *         field("visits", list(INSTANT), Person::visits));
 *
 * Person p = PERSON.read(body);            // JsonMappingException lists every problem
 * String json = PERSON.write(p);           // {"name":"Ada","email":"ada@example.com","visits":[]}
 * }</pre>
 *
 * <p>The built-in codecs and the combinators ({@code list}, {@code set}, {@code map},
 * {@code optional}, {@code nullable}, {@code lazy}, {@code enumOf}) are on {@link JsonCodecs};
 * object and sealed-hierarchy codecs are made here. Every codec is immutable and thread-safe,
 * so keep them in {@code static final} constants and share them.
 *
 * <h2>Decoding</h2>
 * {@link #decode(JsonValue)} finds every problem in the document, not just the first: an object
 * with three bad members gives three problems, an array with bad elements one problem per
 * element, each with its JSON path ({@code $.items[3].qty}). It then throws
 * {@link JsonMappingException} with all of them (at most 100, plus one that counts the rest).
 * Nothing is coerced: {@code "42"} is not an {@code int}, {@code 42} is not a {@code String}.
 *
 * <h2>Encoding</h2>
 * {@link #encode(Object)} stops at the first value it cannot write ({@code null} where the codec
 * is not {@code nullable}, a {@code NaN} double, an exception from a getter) and throws
 * {@link JsonMappingException} with that value's path.
 *
 * <h2>Depth</h2>
 * Both directions count nested objects and arrays and stop with a problem past
 * {@link JsonLimits#DEFAULT}'s {@code maxDepth} (512), the parser's limit, so a deep tree built
 * in code or a deep recursive Java value gives {@link JsonMappingException}, never
 * {@link StackOverflowError}. The exception is {@link JsonCodecs#JSON}: it hands a
 * {@code JsonValue} through as it is, without walking it, so it does not count what lies inside;
 * the tree model and its writer handle any depth without recursion.
 *
 * <p>Codecs cannot be implemented outside this package; compose the existing ones (with
 * {@link #map(Function, Function)} for value types).
 *
 * @param <T> the Java type
 */
public abstract class JsonCodec<T> {

    JsonCodec() {
    }

    /**
     * Decodes {@code json} at {@code path}. Reports every problem to {@code decoding} and returns
     * {@code null} after reporting one; the caller detects failure by
     * {@link Decoding#count()} going up, never by the return value.
     */
    abstract T decodeAt(JsonValue json, Path path, Decoding decoding);

    /** Encodes {@code value} (which may be {@code null}) at {@code path}, throwing at the first problem. */
    abstract JsonValue encodeAt(T value, Path path, Encoding encoding);

    /**
     * Decodes a JSON value.
     *
     * @param json the value, such as one from {@link Json#parse(String)} or built in code
     * @return the Java value
     * @throws JsonMappingException if the value does not match the codec; it lists every
     *                              problem with its path
     * @throws NullPointerException if {@code json} is {@code null}
     */
    public final T decode(JsonValue json) {
        Objects.requireNonNull(json, "json");
        var decoding = new Decoding();
        T value = decodeAt(json, Path.ROOT, decoding);
        if (decoding.count() > 0) {
            throw new JsonMappingException(decoding.problems(), decoding.count());
        }
        return value;
    }

    /**
     * Encodes a Java value.
     *
     * @param value the value; {@code null} only for a {@code nullable} codec
     * @return the JSON value
     * @throws JsonMappingException at the first value that cannot be encoded, with its path
     */
    public final JsonValue encode(T value) {
        return encodeAt(value, Path.ROOT, new Encoding());
    }

    /**
     * Parses JSON text with {@link JsonLimits#DEFAULT} and decodes it.
     *
     * @param json the JSON text
     * @return the Java value
     * @throws JsonParseException   if the text is not JSON or exceeds the limits (not wrapped)
     * @throws JsonMappingException if the JSON does not match the codec
     * @throws NullPointerException if {@code json} is {@code null}
     */
    public final T read(String json) {
        return decode(Json.parse(json, JsonLimits.DEFAULT));
    }

    /**
     * Parses UTF-8 JSON with {@link JsonLimits#DEFAULT} and decodes it.
     *
     * @param utf8 the JSON text as UTF-8
     * @return the Java value
     * @throws JsonParseException   if the bytes are not JSON in UTF-8 or exceed the limits
     * @throws JsonMappingException if the JSON does not match the codec
     * @throws NullPointerException if {@code utf8} is {@code null}
     */
    public final T read(byte[] utf8) {
        return decode(Json.parse(utf8, JsonLimits.DEFAULT));
    }

    /**
     * Encodes a value and writes it as compact JSON ({@link Json#write(JsonValue)}).
     *
     * @param value the value
     * @return the JSON text
     * @throws JsonMappingException at the first value that cannot be encoded
     */
    public final String write(T value) {
        return Json.write(encode(value));
    }

    /**
     * Encodes a value and writes it as indented JSON ({@link Json#writePretty(JsonValue)}).
     *
     * @param value the value
     * @return the JSON text
     * @throws JsonMappingException at the first value that cannot be encoded
     */
    public final String writePretty(T value) {
        return Json.writePretty(encode(value));
    }

    /**
     * A codec for a type that is represented by this codec's type, such as a value type around
     * a string: {@code STRING.map(Email::new, Email::value)}.
     *
     * <p>Decoding applies {@code to} to the decoded value; an exception it throws (such as an
     * {@code IllegalArgumentException} from a compact constructor) becomes a problem on the
     * value's path with the exception's message (cleaned, at most 200 characters), not an
     * exception from {@code decode}. Encoding applies {@code from} and encodes the result; an
     * exception from {@code from} fails the encoding with the value's path. A {@code null}
     * value is not passed to {@code from}: it fails, unless the mapped codec is wrapped in
     * {@link JsonCodecs#nullable(JsonCodec)}.
     *
     * @param <R>  the new type
     * @param to   from this codec's type to the new type (such as {@code Email::new})
     * @param from from the new type back (such as {@code Email::value})
     * @return the new codec
     */
    public final <R> JsonCodec<R> map(Function<? super T, ? extends R> to, Function<? super R, ? extends T> from) {
        return new MappedCodec<>(this, to, from);
    }

    /**
     * A required member: {@code field("name", STRING, Person::name)}. A missing member is a
     * problem, and so is JSON {@code null} unless {@code codec} is {@code nullable}.
     *
     * @param <R>    the type the field belongs to
     * @param <T>    the type of the field's value
     * @param name   the member's name in JSON, exactly (no naming strategy)
     * @param codec  the codec of the value
     * @param getter reads the value from the Java value (such as {@code Person::name})
     * @return the field; {@link Field#orElse(Object)} gives it a default for a missing member
     * @throws IllegalArgumentException if {@code name} contains an unpaired surrogate
     */
    public static <R, T> Field<R, T> field(String name, JsonCodec<T> codec, Function<? super R, ? extends T> getter) {
        return new Field<>(name, new Field.Required<>(Objects.requireNonNull(codec, "codec")), getter);
    }

    /**
     * An optional member for a component of type {@code Optional<T>}:
     * {@code optionalField("note", STRING, Person::note)}. A missing member and JSON
     * {@code null} both decode to {@code Optional.empty()}; an empty value is left out when
     * encoding (like Jackson's {@code @JsonInclude(NON_ABSENT)}).
     *
     * @param <R>    the type the field belongs to
     * @param <T>    the type inside the {@code Optional}
     * @param name   the member's name in JSON
     * @param codec  the codec of a present value
     * @param getter reads the {@code Optional} from the Java value; returning {@code null}
     *               fails the encoding
     * @return the field
     * @throws IllegalArgumentException if {@code name} contains an unpaired surrogate
     */
    public static <R, T> Field<R, Optional<T>> optionalField(String name, JsonCodec<T> codec,
            Function<? super R, Optional<T>> getter) {
        return new Field<>(name, new Field.Optionally<>(Objects.requireNonNull(codec, "codec")), getter);
    }

    /**
     * An object codec with any number of fields (the general form, for more than 16). The
     * fields are also the keys of their values:
     *
     * <pre>{@code
     * static final Field<Big, String> A = field("a", STRING, Big::a);
     * static final Field<Big, Integer> B = field("b", INT, Big::b);
     * // ... C to T
     * static final ObjectCodec<Big> BIG = JsonCodec.object(List.of(A, B, ...),
     *         values -> new Big(values.get(A), values.get(B), ...));
     * }</pre>
     *
     * <p>Members are written in the order of {@code fields}. The constructor is called only when
     * every field decoded; an exception from it becomes a problem on the object's path.
     *
     * @param <R>         the type
     * @param fields      the fields, in the order they are written
     * @param constructor creates the value from the decoded values
     * @return the codec
     * @throws IllegalArgumentException if two fields have the same name
     */
    public static <R> ObjectCodec<R> object(List<? extends Field<R, ?>> fields,
            Function<? super FieldValues<R>, ? extends R> constructor) {
        return new ObjectCodec<>(fields, constructor, false);
    }

    /**
     * A codec for a value without fields, written as {@code {}}.
     *
     * @param <R>         the type
     * @param constructor creates the value (such as {@code Empty::new})
     * @return the codec
     */
    public static <R> ObjectCodec<R> object(Supplier<? extends R> constructor) {
        Objects.requireNonNull(constructor, "constructor");
        return object(List.of(), values -> constructor.get());
    }

    /**
     * A codec for a record (or any class) with 1 field; see
     * {@link #object(Constructors.Of2, Field, Field)}.
     *
     * @param <R>         the type
     * @param <A> the type of field 1
     * @param constructor creates the value from the decoded fields, in order (such as
     *                    {@code Person::new})
     * @param a field 1, the constructor's parameter 1
     * @return the codec
     * @throws IllegalArgumentException if two fields have the same name
     */
    public static <R, A> ObjectCodec<R> object(Constructors.Of1<A, R> constructor, Field<R, A> a) {
        Objects.requireNonNull(constructor, "constructor");
        return object(List.of(a), v -> constructor.create(v.get(a)));
    }

    /**
     * A codec for a record (or any class) with 2 fields. The constructor is usually the record's
     * canonical constructor, and the fields are given in the order of its parameters; the
     * compiler checks their types against it:
     *
     * <pre>{@code
     * record Person(String name, int age) {}
     * static final ObjectCodec<Person> PERSON = JsonCodec.object(Person::new,
     *         field("name", STRING, Person::name),
     *         field("age", INT, Person::age));
     * }</pre>
     *
     * <p>Members are written in the order of the fields. See {@link ObjectCodec} for how
     * decoding treats missing, unknown and invalid members. There is one such method for each
     * number of fields from 1 to 16; beyond that, use {@link #object(List, Function)}.
     *
     * @param <R>         the type
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param constructor creates the value from the decoded fields, in order (such as
     *                    {@code Person::new})
     * @param a field 1, the constructor's parameter 1
     * @param b field 2, the constructor's parameter 2
     * @return the codec
     * @throws IllegalArgumentException if two fields have the same name
     */
    public static <R, A, B> ObjectCodec<R> object(Constructors.Of2<A, B, R> constructor, Field<R, A> a, Field<R, B> b) {
        Objects.requireNonNull(constructor, "constructor");
        return object(List.of(a, b), v -> constructor.create(v.get(a), v.get(b)));
    }

    /**
     * A codec for a record (or any class) with 3 fields; see
     * {@link #object(Constructors.Of2, Field, Field)}.
     *
     * @param <R>         the type
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param constructor creates the value from the decoded fields, in order (such as
     *                    {@code Person::new})
     * @param a field 1, the constructor's parameter 1
     * @param b field 2, the constructor's parameter 2
     * @param c field 3, the constructor's parameter 3
     * @return the codec
     * @throws IllegalArgumentException if two fields have the same name
     */
    public static <R, A, B, C> ObjectCodec<R> object(Constructors.Of3<A, B, C, R> constructor, Field<R, A> a, Field<R, B> b, Field<R, C> c) {
        Objects.requireNonNull(constructor, "constructor");
        return object(List.of(a, b, c), v -> constructor.create(v.get(a), v.get(b), v.get(c)));
    }

    /**
     * A codec for a record (or any class) with 4 fields; see
     * {@link #object(Constructors.Of2, Field, Field)}.
     *
     * @param <R>         the type
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param constructor creates the value from the decoded fields, in order (such as
     *                    {@code Person::new})
     * @param a field 1, the constructor's parameter 1
     * @param b field 2, the constructor's parameter 2
     * @param c field 3, the constructor's parameter 3
     * @param d field 4, the constructor's parameter 4
     * @return the codec
     * @throws IllegalArgumentException if two fields have the same name
     */
    public static <R, A, B, C, D> ObjectCodec<R> object(Constructors.Of4<A, B, C, D, R> constructor, Field<R, A> a, Field<R, B> b, Field<R, C> c, Field<R, D> d) {
        Objects.requireNonNull(constructor, "constructor");
        return object(List.of(a, b, c, d), v -> constructor.create(v.get(a), v.get(b), v.get(c), v.get(d)));
    }

    /**
     * A codec for a record (or any class) with 5 fields; see
     * {@link #object(Constructors.Of2, Field, Field)}.
     *
     * @param <R>         the type
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param constructor creates the value from the decoded fields, in order (such as
     *                    {@code Person::new})
     * @param a field 1, the constructor's parameter 1
     * @param b field 2, the constructor's parameter 2
     * @param c field 3, the constructor's parameter 3
     * @param d field 4, the constructor's parameter 4
     * @param e field 5, the constructor's parameter 5
     * @return the codec
     * @throws IllegalArgumentException if two fields have the same name
     */
    public static <R, A, B, C, D, E> ObjectCodec<R> object(Constructors.Of5<A, B, C, D, E, R> constructor, 
            Field<R, A> a,
            Field<R, B> b,
            Field<R, C> c,
            Field<R, D> d,
            Field<R, E> e) {
        Objects.requireNonNull(constructor, "constructor");
        return object(List.of(a, b, c, d, e), v -> constructor.create(v.get(a), v.get(b), v.get(c), v.get(d), v.get(e)));
    }

    /**
     * A codec for a record (or any class) with 6 fields; see
     * {@link #object(Constructors.Of2, Field, Field)}.
     *
     * @param <R>         the type
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param <F> the type of field 6
     * @param constructor creates the value from the decoded fields, in order (such as
     *                    {@code Person::new})
     * @param a field 1, the constructor's parameter 1
     * @param b field 2, the constructor's parameter 2
     * @param c field 3, the constructor's parameter 3
     * @param d field 4, the constructor's parameter 4
     * @param e field 5, the constructor's parameter 5
     * @param f field 6, the constructor's parameter 6
     * @return the codec
     * @throws IllegalArgumentException if two fields have the same name
     */
    public static <R, A, B, C, D, E, F> ObjectCodec<R> object(Constructors.Of6<A, B, C, D, E, F, R> constructor, 
            Field<R, A> a,
            Field<R, B> b,
            Field<R, C> c,
            Field<R, D> d,
            Field<R, E> e,
            Field<R, F> f) {
        Objects.requireNonNull(constructor, "constructor");
        return object(List.of(a, b, c, d, e, f), v -> constructor.create(v.get(a), v.get(b), v.get(c), v.get(d), v.get(e), v.get(f)));
    }

    /**
     * A codec for a record (or any class) with 7 fields; see
     * {@link #object(Constructors.Of2, Field, Field)}.
     *
     * @param <R>         the type
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param <F> the type of field 6
     * @param <G> the type of field 7
     * @param constructor creates the value from the decoded fields, in order (such as
     *                    {@code Person::new})
     * @param a field 1, the constructor's parameter 1
     * @param b field 2, the constructor's parameter 2
     * @param c field 3, the constructor's parameter 3
     * @param d field 4, the constructor's parameter 4
     * @param e field 5, the constructor's parameter 5
     * @param f field 6, the constructor's parameter 6
     * @param g field 7, the constructor's parameter 7
     * @return the codec
     * @throws IllegalArgumentException if two fields have the same name
     */
    public static <R, A, B, C, D, E, F, G> ObjectCodec<R> object(Constructors.Of7<A, B, C, D, E, F, G, R> constructor, 
            Field<R, A> a,
            Field<R, B> b,
            Field<R, C> c,
            Field<R, D> d,
            Field<R, E> e,
            Field<R, F> f,
            Field<R, G> g) {
        Objects.requireNonNull(constructor, "constructor");
        return object(List.of(a, b, c, d, e, f, g), v -> constructor.create(v.get(a), v.get(b), v.get(c), v.get(d), v.get(e), v.get(f), v.get(g)));
    }

    /**
     * A codec for a record (or any class) with 8 fields; see
     * {@link #object(Constructors.Of2, Field, Field)}.
     *
     * @param <R>         the type
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param <F> the type of field 6
     * @param <G> the type of field 7
     * @param <H> the type of field 8
     * @param constructor creates the value from the decoded fields, in order (such as
     *                    {@code Person::new})
     * @param a field 1, the constructor's parameter 1
     * @param b field 2, the constructor's parameter 2
     * @param c field 3, the constructor's parameter 3
     * @param d field 4, the constructor's parameter 4
     * @param e field 5, the constructor's parameter 5
     * @param f field 6, the constructor's parameter 6
     * @param g field 7, the constructor's parameter 7
     * @param h field 8, the constructor's parameter 8
     * @return the codec
     * @throws IllegalArgumentException if two fields have the same name
     */
    public static <R, A, B, C, D, E, F, G, H> ObjectCodec<R> object(Constructors.Of8<A, B, C, D, E, F, G, H, R> constructor, 
            Field<R, A> a,
            Field<R, B> b,
            Field<R, C> c,
            Field<R, D> d,
            Field<R, E> e,
            Field<R, F> f,
            Field<R, G> g,
            Field<R, H> h) {
        Objects.requireNonNull(constructor, "constructor");
        return object(List.of(a, b, c, d, e, f, g, h), v -> constructor.create(v.get(a), v.get(b), v.get(c), v.get(d), v.get(e), v.get(f), v.get(g), v.get(h)));
    }

    /**
     * A codec for a record (or any class) with 9 fields; see
     * {@link #object(Constructors.Of2, Field, Field)}.
     *
     * @param <R>         the type
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param <F> the type of field 6
     * @param <G> the type of field 7
     * @param <H> the type of field 8
     * @param <I> the type of field 9
     * @param constructor creates the value from the decoded fields, in order (such as
     *                    {@code Person::new})
     * @param a field 1, the constructor's parameter 1
     * @param b field 2, the constructor's parameter 2
     * @param c field 3, the constructor's parameter 3
     * @param d field 4, the constructor's parameter 4
     * @param e field 5, the constructor's parameter 5
     * @param f field 6, the constructor's parameter 6
     * @param g field 7, the constructor's parameter 7
     * @param h field 8, the constructor's parameter 8
     * @param i field 9, the constructor's parameter 9
     * @return the codec
     * @throws IllegalArgumentException if two fields have the same name
     */
    public static <R, A, B, C, D, E, F, G, H, I> ObjectCodec<R> object(Constructors.Of9<A, B, C, D, E, F, G, H, I, R> constructor, 
            Field<R, A> a,
            Field<R, B> b,
            Field<R, C> c,
            Field<R, D> d,
            Field<R, E> e,
            Field<R, F> f,
            Field<R, G> g,
            Field<R, H> h,
            Field<R, I> i) {
        Objects.requireNonNull(constructor, "constructor");
        return object(List.of(a, b, c, d, e, f, g, h, i), v -> constructor.create(v.get(a), v.get(b), v.get(c), v.get(d), v.get(e), v.get(f), v.get(g), v.get(h), v.get(i)));
    }

    /**
     * A codec for a record (or any class) with 10 fields; see
     * {@link #object(Constructors.Of2, Field, Field)}.
     *
     * @param <R>         the type
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param <F> the type of field 6
     * @param <G> the type of field 7
     * @param <H> the type of field 8
     * @param <I> the type of field 9
     * @param <J> the type of field 10
     * @param constructor creates the value from the decoded fields, in order (such as
     *                    {@code Person::new})
     * @param a field 1, the constructor's parameter 1
     * @param b field 2, the constructor's parameter 2
     * @param c field 3, the constructor's parameter 3
     * @param d field 4, the constructor's parameter 4
     * @param e field 5, the constructor's parameter 5
     * @param f field 6, the constructor's parameter 6
     * @param g field 7, the constructor's parameter 7
     * @param h field 8, the constructor's parameter 8
     * @param i field 9, the constructor's parameter 9
     * @param j field 10, the constructor's parameter 10
     * @return the codec
     * @throws IllegalArgumentException if two fields have the same name
     */
    public static <R, A, B, C, D, E, F, G, H, I, J> ObjectCodec<R> object(Constructors.Of10<A, B, C, D, E, F, G, H, I, J, R> constructor, 
            Field<R, A> a,
            Field<R, B> b,
            Field<R, C> c,
            Field<R, D> d,
            Field<R, E> e,
            Field<R, F> f,
            Field<R, G> g,
            Field<R, H> h,
            Field<R, I> i,
            Field<R, J> j) {
        Objects.requireNonNull(constructor, "constructor");
        return object(List.of(a, b, c, d, e, f, g, h, i, j), v -> constructor.create(v.get(a), v.get(b), v.get(c), v.get(d), v.get(e), v.get(f), v.get(g), v.get(h), v.get(i), v.get(j)));
    }

    /**
     * A codec for a record (or any class) with 11 fields; see
     * {@link #object(Constructors.Of2, Field, Field)}.
     *
     * @param <R>         the type
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param <F> the type of field 6
     * @param <G> the type of field 7
     * @param <H> the type of field 8
     * @param <I> the type of field 9
     * @param <J> the type of field 10
     * @param <K> the type of field 11
     * @param constructor creates the value from the decoded fields, in order (such as
     *                    {@code Person::new})
     * @param a field 1, the constructor's parameter 1
     * @param b field 2, the constructor's parameter 2
     * @param c field 3, the constructor's parameter 3
     * @param d field 4, the constructor's parameter 4
     * @param e field 5, the constructor's parameter 5
     * @param f field 6, the constructor's parameter 6
     * @param g field 7, the constructor's parameter 7
     * @param h field 8, the constructor's parameter 8
     * @param i field 9, the constructor's parameter 9
     * @param j field 10, the constructor's parameter 10
     * @param k field 11, the constructor's parameter 11
     * @return the codec
     * @throws IllegalArgumentException if two fields have the same name
     */
    public static <R, A, B, C, D, E, F, G, H, I, J, K> ObjectCodec<R> object(Constructors.Of11<A, B, C, D, E, F, G, H, I, J, K, R> constructor, 
            Field<R, A> a,
            Field<R, B> b,
            Field<R, C> c,
            Field<R, D> d,
            Field<R, E> e,
            Field<R, F> f,
            Field<R, G> g,
            Field<R, H> h,
            Field<R, I> i,
            Field<R, J> j,
            Field<R, K> k) {
        Objects.requireNonNull(constructor, "constructor");
        return object(List.of(a, b, c, d, e, f, g, h, i, j, k), v -> constructor.create(v.get(a), v.get(b), v.get(c), v.get(d), v.get(e), v.get(f), v.get(g), v.get(h), v.get(i), v.get(j), v.get(k)));
    }

    /**
     * A codec for a record (or any class) with 12 fields; see
     * {@link #object(Constructors.Of2, Field, Field)}.
     *
     * @param <R>         the type
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param <F> the type of field 6
     * @param <G> the type of field 7
     * @param <H> the type of field 8
     * @param <I> the type of field 9
     * @param <J> the type of field 10
     * @param <K> the type of field 11
     * @param <L> the type of field 12
     * @param constructor creates the value from the decoded fields, in order (such as
     *                    {@code Person::new})
     * @param a field 1, the constructor's parameter 1
     * @param b field 2, the constructor's parameter 2
     * @param c field 3, the constructor's parameter 3
     * @param d field 4, the constructor's parameter 4
     * @param e field 5, the constructor's parameter 5
     * @param f field 6, the constructor's parameter 6
     * @param g field 7, the constructor's parameter 7
     * @param h field 8, the constructor's parameter 8
     * @param i field 9, the constructor's parameter 9
     * @param j field 10, the constructor's parameter 10
     * @param k field 11, the constructor's parameter 11
     * @param l field 12, the constructor's parameter 12
     * @return the codec
     * @throws IllegalArgumentException if two fields have the same name
     */
    public static <R, A, B, C, D, E, F, G, H, I, J, K, L> ObjectCodec<R> object(Constructors.Of12<A, B, C, D, E, F, G, H, I, J, K, L, R> constructor, 
            Field<R, A> a,
            Field<R, B> b,
            Field<R, C> c,
            Field<R, D> d,
            Field<R, E> e,
            Field<R, F> f,
            Field<R, G> g,
            Field<R, H> h,
            Field<R, I> i,
            Field<R, J> j,
            Field<R, K> k,
            Field<R, L> l) {
        Objects.requireNonNull(constructor, "constructor");
        return object(List.of(a, b, c, d, e, f, g, h, i, j, k, l), v -> constructor.create(v.get(a), v.get(b), v.get(c), v.get(d), v.get(e), v.get(f), v.get(g), v.get(h), v.get(i), v.get(j), v.get(k), v.get(l)));
    }

    /**
     * A codec for a record (or any class) with 13 fields; see
     * {@link #object(Constructors.Of2, Field, Field)}.
     *
     * @param <R>         the type
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param <F> the type of field 6
     * @param <G> the type of field 7
     * @param <H> the type of field 8
     * @param <I> the type of field 9
     * @param <J> the type of field 10
     * @param <K> the type of field 11
     * @param <L> the type of field 12
     * @param <M> the type of field 13
     * @param constructor creates the value from the decoded fields, in order (such as
     *                    {@code Person::new})
     * @param a field 1, the constructor's parameter 1
     * @param b field 2, the constructor's parameter 2
     * @param c field 3, the constructor's parameter 3
     * @param d field 4, the constructor's parameter 4
     * @param e field 5, the constructor's parameter 5
     * @param f field 6, the constructor's parameter 6
     * @param g field 7, the constructor's parameter 7
     * @param h field 8, the constructor's parameter 8
     * @param i field 9, the constructor's parameter 9
     * @param j field 10, the constructor's parameter 10
     * @param k field 11, the constructor's parameter 11
     * @param l field 12, the constructor's parameter 12
     * @param m field 13, the constructor's parameter 13
     * @return the codec
     * @throws IllegalArgumentException if two fields have the same name
     */
    public static <R, A, B, C, D, E, F, G, H, I, J, K, L, M> ObjectCodec<R> object(Constructors.Of13<A, B, C, D, E, F, G, H, I, J, K, L, M, R> constructor, 
            Field<R, A> a,
            Field<R, B> b,
            Field<R, C> c,
            Field<R, D> d,
            Field<R, E> e,
            Field<R, F> f,
            Field<R, G> g,
            Field<R, H> h,
            Field<R, I> i,
            Field<R, J> j,
            Field<R, K> k,
            Field<R, L> l,
            Field<R, M> m) {
        Objects.requireNonNull(constructor, "constructor");
        return object(List.of(a, b, c, d, e, f, g, h, i, j, k, l, m), v -> constructor.create(v.get(a), v.get(b), v.get(c), v.get(d), v.get(e), v.get(f), v.get(g), v.get(h), v.get(i), v.get(j), v.get(k), v.get(l), v.get(m)));
    }

    /**
     * A codec for a record (or any class) with 14 fields; see
     * {@link #object(Constructors.Of2, Field, Field)}.
     *
     * @param <R>         the type
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param <F> the type of field 6
     * @param <G> the type of field 7
     * @param <H> the type of field 8
     * @param <I> the type of field 9
     * @param <J> the type of field 10
     * @param <K> the type of field 11
     * @param <L> the type of field 12
     * @param <M> the type of field 13
     * @param <N> the type of field 14
     * @param constructor creates the value from the decoded fields, in order (such as
     *                    {@code Person::new})
     * @param a field 1, the constructor's parameter 1
     * @param b field 2, the constructor's parameter 2
     * @param c field 3, the constructor's parameter 3
     * @param d field 4, the constructor's parameter 4
     * @param e field 5, the constructor's parameter 5
     * @param f field 6, the constructor's parameter 6
     * @param g field 7, the constructor's parameter 7
     * @param h field 8, the constructor's parameter 8
     * @param i field 9, the constructor's parameter 9
     * @param j field 10, the constructor's parameter 10
     * @param k field 11, the constructor's parameter 11
     * @param l field 12, the constructor's parameter 12
     * @param m field 13, the constructor's parameter 13
     * @param n field 14, the constructor's parameter 14
     * @return the codec
     * @throws IllegalArgumentException if two fields have the same name
     */
    public static <R, A, B, C, D, E, F, G, H, I, J, K, L, M, N> ObjectCodec<R> object(Constructors.Of14<A, B, C, D, E, F, G, H, I, J, K, L, M, N, R> constructor, 
            Field<R, A> a,
            Field<R, B> b,
            Field<R, C> c,
            Field<R, D> d,
            Field<R, E> e,
            Field<R, F> f,
            Field<R, G> g,
            Field<R, H> h,
            Field<R, I> i,
            Field<R, J> j,
            Field<R, K> k,
            Field<R, L> l,
            Field<R, M> m,
            Field<R, N> n) {
        Objects.requireNonNull(constructor, "constructor");
        return object(List.of(a, b, c, d, e, f, g, h, i, j, k, l, m, n), v -> constructor.create(v.get(a), v.get(b), v.get(c), v.get(d), v.get(e), v.get(f), v.get(g), v.get(h), v.get(i), v.get(j), v.get(k), v.get(l), v.get(m), v.get(n)));
    }

    /**
     * A codec for a record (or any class) with 15 fields; see
     * {@link #object(Constructors.Of2, Field, Field)}.
     *
     * @param <R>         the type
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param <F> the type of field 6
     * @param <G> the type of field 7
     * @param <H> the type of field 8
     * @param <I> the type of field 9
     * @param <J> the type of field 10
     * @param <K> the type of field 11
     * @param <L> the type of field 12
     * @param <M> the type of field 13
     * @param <N> the type of field 14
     * @param <O> the type of field 15
     * @param constructor creates the value from the decoded fields, in order (such as
     *                    {@code Person::new})
     * @param a field 1, the constructor's parameter 1
     * @param b field 2, the constructor's parameter 2
     * @param c field 3, the constructor's parameter 3
     * @param d field 4, the constructor's parameter 4
     * @param e field 5, the constructor's parameter 5
     * @param f field 6, the constructor's parameter 6
     * @param g field 7, the constructor's parameter 7
     * @param h field 8, the constructor's parameter 8
     * @param i field 9, the constructor's parameter 9
     * @param j field 10, the constructor's parameter 10
     * @param k field 11, the constructor's parameter 11
     * @param l field 12, the constructor's parameter 12
     * @param m field 13, the constructor's parameter 13
     * @param n field 14, the constructor's parameter 14
     * @param o field 15, the constructor's parameter 15
     * @return the codec
     * @throws IllegalArgumentException if two fields have the same name
     */
    public static <R, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O> ObjectCodec<R> object(Constructors.Of15<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, R> constructor, 
            Field<R, A> a,
            Field<R, B> b,
            Field<R, C> c,
            Field<R, D> d,
            Field<R, E> e,
            Field<R, F> f,
            Field<R, G> g,
            Field<R, H> h,
            Field<R, I> i,
            Field<R, J> j,
            Field<R, K> k,
            Field<R, L> l,
            Field<R, M> m,
            Field<R, N> n,
            Field<R, O> o) {
        Objects.requireNonNull(constructor, "constructor");
        return object(List.of(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o), v -> constructor.create(v.get(a), v.get(b), v.get(c), v.get(d), v.get(e), v.get(f), v.get(g), v.get(h), v.get(i), v.get(j), v.get(k), v.get(l), v.get(m), v.get(n), v.get(o)));
    }

    /**
     * A codec for a record (or any class) with 16 fields; see
     * {@link #object(Constructors.Of2, Field, Field)}.
     *
     * @param <R>         the type
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param <F> the type of field 6
     * @param <G> the type of field 7
     * @param <H> the type of field 8
     * @param <I> the type of field 9
     * @param <J> the type of field 10
     * @param <K> the type of field 11
     * @param <L> the type of field 12
     * @param <M> the type of field 13
     * @param <N> the type of field 14
     * @param <O> the type of field 15
     * @param <P> the type of field 16
     * @param constructor creates the value from the decoded fields, in order (such as
     *                    {@code Person::new})
     * @param a field 1, the constructor's parameter 1
     * @param b field 2, the constructor's parameter 2
     * @param c field 3, the constructor's parameter 3
     * @param d field 4, the constructor's parameter 4
     * @param e field 5, the constructor's parameter 5
     * @param f field 6, the constructor's parameter 6
     * @param g field 7, the constructor's parameter 7
     * @param h field 8, the constructor's parameter 8
     * @param i field 9, the constructor's parameter 9
     * @param j field 10, the constructor's parameter 10
     * @param k field 11, the constructor's parameter 11
     * @param l field 12, the constructor's parameter 12
     * @param m field 13, the constructor's parameter 13
     * @param n field 14, the constructor's parameter 14
     * @param o field 15, the constructor's parameter 15
     * @param p field 16, the constructor's parameter 16
     * @return the codec
     * @throws IllegalArgumentException if two fields have the same name
     */
    public static <R, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P> ObjectCodec<R> object(Constructors.Of16<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, R> constructor, 
            Field<R, A> a,
            Field<R, B> b,
            Field<R, C> c,
            Field<R, D> d,
            Field<R, E> e,
            Field<R, F> f,
            Field<R, G> g,
            Field<R, H> h,
            Field<R, I> i,
            Field<R, J> j,
            Field<R, K> k,
            Field<R, L> l,
            Field<R, M> m,
            Field<R, N> n,
            Field<R, O> o,
            Field<R, P> p) {
        Objects.requireNonNull(constructor, "constructor");
        return object(List.of(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p), v -> constructor.create(v.get(a), v.get(b), v.get(c), v.get(d), v.get(e), v.get(f), v.get(g), v.get(h), v.get(i), v.get(j), v.get(k), v.get(l), v.get(m), v.get(n), v.get(o), v.get(p)));
    }

    /**
     * A codec for a sealed hierarchy, written as an object with a discriminator member first
     * ({@code {"type":"circle","radius":1.5}}), like Jackson's {@code @JsonTypeInfo(use = NAME)}
     * with {@code @JsonSubTypes}, but without class tokens:
     *
     * <pre>{@code
     * sealed interface Shape permits Circle, Square {}
     * static final Variant<Shape, Circle> CIRCLE = Variant.of("circle", CIRCLE_CODEC);
     * static final Variant<Shape, Square> SQUARE = Variant.of("square", SQUARE_CODEC);
     * static final JsonCodec<Shape> SHAPE = JsonCodec.sealed("type",
     *         shape -> switch (shape) {
     *             case Circle c -> CIRCLE.tag(c);
     *             case Square s -> SQUARE.tag(s);
     *         },
     *         CIRCLE, SQUARE);
     * }</pre>
     *
     * <p>Encoding picks the variant with {@code selector}. Written as a {@code switch} over the
     * sealed type without {@code default}, the compiler checks that it covers every permitted
     * subtype: a new subtype without a case is a compile error, not a runtime surprise.
     *
     * <p>Decoding reads the discriminator wherever it is in the object and decodes the rest of
     * the object with that variant's codec (the discriminator is not an unknown member). A
     * missing discriminator, one that is not a string, and an unknown tag are problems that list
     * the allowed tags.
     *
     * <p>If {@code selector} returns {@code null}, throws, or returns a variant that is not in
     * {@code variants}, encoding fails with {@link JsonMappingException} on the value's path
     * (the last is a programming error: add the variant to the list).
     *
     * @param <T>           the sealed type
     * @param discriminator the name of the member that holds the tag
     * @param selector      the variant of a value, as {@code variant.tag(value)}
     * @param variants      every variant, each with a distinct tag
     * @return the codec
     * @throws IllegalArgumentException if two variants have the same tag, a variant's codec has
     *                                  a field named like the discriminator, there are no
     *                                  variants, or the name contains an unpaired surrogate
     */
    @SafeVarargs
    public static <T> JsonCodec<T> sealed(String discriminator, Function<? super T, Variant.Tagged<T>> selector,
            Variant<T, ? extends T>... variants) {
        var list = new ArrayList<Variant<T, ? extends T>>(variants.length);
        for (var variant : variants) {
            list.add(Objects.requireNonNull(variant, "variant"));
        }
        return new SealedCodec<>(discriminator, selector, list);
    }
}
