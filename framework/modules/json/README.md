# spola.json

JSON för Spola: en oföränderlig trädmodell som en sealed hierarki, en strikt parser enligt
RFC 8259 med gränser, och en skrivare (kompakt och indenterad). Modulen ersätter den del av
Jackson som Spring Boot använder för att läsa och skriva JSON-träd (`ObjectMapper.readTree`,
`JsonNode`, `ObjectNode`, `writerWithDefaultPrettyPrinter`). Inga annoteringar, ingen
reflektion, inga `requires` på andra Spola-moduler. Paketet `spola.json.codec` mappar mellan
Java-typer och trädet (det Spring Boot använder `ObjectMapper.readValue` och Jacksons
annoteringar till), se [Mappning](#mappning-mellan-java-och-json-spolajsoncodec).

| Jackson | Spola |
|---|---|
| `new ObjectMapper().readTree(text)` | `Json.parse(text)` (också `byte[]`, `InputStream`, `Reader`) |
| `JsonNode` med `isObject()`, `isTextual()` ... | `sealed interface JsonValue` och `switch` med mönster |
| `ObjectNode`, `ArrayNode`, `TextNode`, `NumericNode`, `BooleanNode`, `NullNode` | `JsonObject`, `JsonArray`, `JsonString`, `JsonNumber`, `JsonBoolean`, `JsonNull` |
| `mapper.createObjectNode().put(...)` | `JsonObject.builder().put(...).build()`, `JsonObject.of(...)` |
| `mapper.writeValueAsString(node)` | `Json.write(value)` |
| `mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)` | `Json.writePretty(value)` |
| `StreamReadConstraints` | `JsonLimits` |
| `JsonProcessingException` | `JsonParseException` (okontrollerat) |

## Parsa

### Jackson

```java
ObjectMapper mapper = new ObjectMapper();
JsonNode root = mapper.readTree(body);          // kastar JsonProcessingException (kontrollerat)
String city = root.path("address").path("city").asText();   // "" om något saknas
int qty = root.get("items").get(2).get("qty").asInt();      // NullPointerException om något saknas
```

### Spola

```java
JsonValue root = Json.parse(body);              // kastar JsonParseException (okontrollerat)
JsonObject order = switch (root) {
    case JsonObject o -> o;
    default -> throw new IllegalArgumentException("expected an object");
};
String city = order.object("address").string("city");
int qty = order.array("items").object(2).number("qty").intValueExact();
```

Läsarna (`string`, `number`, `bool`, `object`, `array`, `get`, `optional`) returnerar värdet
som den typ du ber om, eller kastar `JsonAccessException` med sökvägen, förväntad och faktisk
typ:

```text
$.items[2].sku: expected string but found number
$.address.city: expected string but the member is missing
$.items[4]: expected object but the index is out of bounds (the array has 4 elements)
```

`optional("note")` är tom både när medlemmen saknas och när den är `null`. Sökvägen räknas
från värdet du parsade eller byggde (`$`); ett objekt som läses via `object(...)`,
`array(...)`, `get(...)` eller `optional(...)` minns vägen dit. Sökvägen är inte en del av
värdet (`equals`, `hashCode` och skrivaren bryr sig inte om den).

Byte-indata och strömmar läses som strikt UTF-8, och strömmen stängs inte:

```java
try (InputStream in = request.body()) {
    JsonValue value = Json.parse(in, JsonLimits.DEFAULT.withMaxInputSize(64 * 1024));
}
```

## Navigera med `switch`

### Jackson

```java
void print(JsonNode node) {
    if (node.isObject()) {
        node.fields().forEachRemaining(e -> print(e.getValue()));
    } else if (node.isArray()) {
        node.forEach(this::print);
    } else if (node.isTextual()) {
        System.out.println(node.textValue());
    } else if (node.isNumber()) {
        System.out.println(node.decimalValue());
    } // isBoolean, isNull, isMissingNode, isBinary, isPojo ... glöms lätt
}
```

### Spola

Hierarkin är sealed, så kompilatorn kontrollerar att alla sex fallen finns och ingen
`default` behövs:

```java
void print(JsonValue value) {
    switch (value) {
        case JsonObject o -> o.members().values().forEach(this::print);
        case JsonArray a -> a.values().forEach(this::print);
        case JsonString s -> System.out.println(s.value());
        case JsonNumber n -> System.out.println(n.bigDecimalValue());
        case JsonBoolean b -> System.out.println(b.value());
        case JsonNull n -> System.out.println("null");
    }
}
```

## Bygga i kod och skriva

### Jackson

```java
ObjectNode order = mapper.createObjectNode();
order.put("id", "A-17");
order.put("paid", true);
order.putArray("items").addObject().put("sku", "x-1").put("qty", 2);
String compact = mapper.writeValueAsString(order);
String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(order);
```

### Spola

```java
JsonObject order = JsonObject.builder()
        .put("id", "A-17")
        .put("paid", true)
        .put("items", JsonArray.of(JsonObject.of("sku", JsonString.of("x-1"), "qty", JsonNumber.of(2))))
        .build();
String compact = Json.write(order);        // {"id":"A-17","paid":true,"items":[{"sku":"x-1","qty":2}]}
String pretty = Json.writePretty(order);   // två blanksteg per nivå, ": " efter namnet, \n som radbrytning
Json.write(order, writer);                 // till en Appendable/Writer
Json.writeUtf8(order, outputStream);       // UTF-8 till en OutputStream (flushas, stängs inte)
```

Träden är oföränderliga: `members()` och `values()` går inte att ändra, och en builder kan
användas vidare utan att påverka objekt den redan byggt.

## Tal

`JsonNumber` behåller talets text exakt (`1.10` skrivs tillbaka som `1.10`) och omvandlar
först på begäran:

| Metod | Beteende |
|---|---|
| `intValueExact()`, `longValueExact()` | värdet om det är ett heltal inom intervallet (`1.0` och `1e2` är heltal), annars `ArithmeticException` |
| `bigIntegerValueExact()` | som ovan, högst `JsonNumber.MAX_EXACT_DIGITS` (10 000) siffror; `1e1000000000` kastar direkt |
| `bigDecimalValue()` | exakt, billigt även för `1e1000000000`; `ArithmeticException` om exponenten inte ryms i `BigDecimal` |
| `doubleValue()` | närmaste `double`, som `Double.parseDouble` (för stora blir oändliga) |

Likhet är numerisk: `1`, `1.0`, `1e0` och `10e-1` är lika och har samma hashkod.

## Gränser

```java
var limits = JsonLimits.DEFAULT.withMaxDepth(32).withMaxInputSize(64 * 1024);
JsonValue value = Json.parse(body, limits);
```

| Gräns | Standard | Mäter |
|---|---|---|
| `maxDepth` | 512 | nästling av arrayer och objekt (`[]` har djup 1) |
| `maxStringLength` | 1 048 576 | en sträng eller ett namn efter avkodning, i UTF-16-enheter |
| `maxNumberLength` | 1 000 | ett tals text, i tecken |
| `maxEntries` | 100 000 | medlemmar i ett objekt eller element i en array |
| `maxInputSize` | 4 194 304 (4 MiB) | hela indatan, i byte (`byte[]`, `InputStream`) eller UTF-16-enheter (`String`, `Reader`) |

Precis vid gränsen accepteras, en över ger `JsonParseException` som namnger gränsen. Parsern
är inte rekursiv, så djupa dokument ger aldrig `StackOverflowError`, och en ström läses aldrig
mer än en enhet förbi `maxInputSize`.

## Fel

`JsonParseException` har rad, kolumn (i kodpunkter) och offset (byte för byte-indata, tecken
för text) och en kort beskrivning med hänvisning till RFC:n:

```text
line 3, column 7 (char offset 18): expected ':' after the member name but found '2' (RFC 8259 section 4)
line 1, column 2 (byte offset 1): invalid UTF-8: byte 0xC0 (overlong encoding of an ASCII character; RFC 3629 section 4)
```

Meddelandet citerar högst ett tecken eller 40 tecken av ett namn, och skriver allt utanför
utskrivbar ASCII som `U+XXXX` eller `\uXXXX`, så att det kan loggas utan att förfalska
loggrader, oavsett indata.

## Avvikelser från Jacksons standardbeteende

| Område | Jackson (standard, `ObjectMapper`) | Spola |
|---|---|---|
| Dubbla nycklar | Accepteras; sista värdet vinner (`STRICT_DUPLICATE_DETECTION` är av) | Avvisas med namnet och positionen. Olika parsrar som väljer olika värden är en känd källa till säkerhetsfel. |
| Tal: lagring | `readTree` gör om till `int`/`long`/`BigInteger`/`double`; `1.10` blir `1.1` och decimaler kan avrundas (`USE_BIG_DECIMAL_FOR_FLOATS` är av) | Texten behålls exakt; omvandling först på begäran |
| Tal: likhet | `IntNode(1)` och `DoubleNode(1.0)` är olika | Numerisk: `1`, `1.0`, `1e0` är lika |
| Tal: exakthet | `asInt()` trunkerar `1.5` till 1 och tyst spill för för stora tal | `intValueExact()` kastar `ArithmeticException` |
| `NaN`, `Infinity`, `+1`, ledande nollor | Avvisas (kan slås på med `JsonReadFeature`) | Avvisas alltid |
| Kommentarer | Avvisas (kan slås på med `ALLOW_JAVA_COMMENTS` m.fl.) | Avvisas alltid; ingen utvidgning finns |
| Enkelfnuttar, onoterade namn, avslutande komman | Avvisas (kan slås på) | Avvisas alltid |
| Oescapade styrtecken i strängar | Avvisas (kan slås på) | Avvisas alltid |
| Ensamma surrogat (`"\ud800"`) | Accepteras | Avvisas, escapade eller ej |
| Ogiltig UTF-8 | Byte-parsern avvisar ogiltiga byte; via en `Reader` avgör readerns avkodare (en `InputStreamReader` ersätter tyst med U+FFFD) | Byte-indata avkodas strikt enligt RFC 3629 (överlånga kodningar, kodade surrogat och kodpunkter över U+10FFFF avvisas), med byte-offset |
| Innehåll efter toppvärdet | Ignoreras tyst av `readTree` (`FAIL_ON_TRAILING_TOKENS` är av) | Fel |
| Byte order mark | Hoppas över i byte-indata | Hoppas över först i all indata; ett U+FEFF utanför en sträng längre in är fel |
| Gränser: djup | 1 000 (`StreamReadConstraints`, sedan 2.15) | 512 |
| Gränser: strängar | 20 000 000 tecken | 1 048 576 UTF-16-enheter |
| Gränser: tal | 1 000 tecken | 1 000 tecken |
| Gränser: dokumentstorlek, antal element | Ingen gräns som standard | 4 MiB, 100 000 per objekt/array |
| Skrivning av U+2028, U+2029, U+007F | Skrivs rått | Escapas (` `, ` `, `\u007f`) |
| Indenterad utskrift | `"a" : 1` (blanksteg före kolon), plattformens radbrytning | `"a": 1`, alltid `\n` |
| Fel vid fel typ | `asText()` m.fl. returnerar standardvärden; `get` ger `null` | `JsonAccessException` med sökväg, förväntad och faktisk typ |

## Mappning mellan Java och JSON (`spola.json.codec`)

En `JsonCodec<T>` är ett värde som kan avkoda JSON till `T` och koda `T` till JSON. Kodekar
byggs i vanlig Java av method references och lambdas: inga annoteringar, ingen reflektion,
inga namnkonventioner. Allt en kodek vet kommer från argumenten, så "Go to definition" på
`CUSTOMER` visar exakt vilka medlemmar som läses och skrivs. Kodekar är oföränderliga och
trådsäkra; lägg dem i `static final`-konstanter.

| Jackson | Spola |
|---|---|
| `mapper.readValue(body, Customer.class)` | `CUSTOMER.read(body)` (också `byte[]`), `CUSTOMER.decode(jsonValue)` |
| `mapper.writeValueAsString(c)` | `CUSTOMER.write(c)`, `writePretty(c)`, `encode(c)` (ger `JsonValue`) |
| Record-komponenter hittas med reflektion | `JsonCodec.object(Customer::new, field(...), ...)` |
| `@JsonProperty("created_at")` | `field("created_at", INSTANT, Customer::created)`: namnet står i fältet |
| `@JsonIgnoreProperties(ignoreUnknown = true)` | `CUSTOMER.ignoringUnknown()` (standard: okända medlemmar avvisas) |
| `@JsonInclude(NON_ABSENT)` på `Optional` | `optionalField("note", STRING, Customer::note)` |
| `@JsonCreator`/`@JsonValue` på en värdetyp | `STRING.map(Email::new, Email::value)` |
| `@JsonTypeInfo` + `@JsonSubTypes` | `JsonCodec.sealed("type", shape -> switch (shape) {...}, CIRCLE, SQUARE)` |
| `JavaTimeModule` + `WRITE_DATES_AS_TIMESTAMPS=false` | `INSTANT`, `LOCAL_DATE`, ... (alltid ISO-8601) |
| `Jdk8Module` (`Optional`) | `optionalField`, `optional(c)` |
| `JsonMappingException` (första felet) | `JsonMappingException` med alla fel och deras sökvägar |

Inbyggda kodekar (konstanter på `JsonCodecs`): `STRING`, `BOOLEAN`, `INT`, `LONG`, `DOUBLE`,
`BIG_DECIMAL`, `BIG_INTEGER`, `UUID`, `INSTANT`, `LOCAL_DATE`, `LOCAL_TIME`,
`LOCAL_DATE_TIME`, `OFFSET_DATE_TIME`, `DURATION` och `JSON` (vilket `JsonValue` som helst,
oförändrat). Kombinatorer (också på `JsonCodecs`): `list(c)`, `set(c)`, `map(c)`
(`Map<String, V>`), `optional(c)`, `nullable(c)`, `lazy(() -> C)` och `enumOf(E.values())`.
`object`, `field`, `optionalField` och `sealed` finns på `JsonCodec`, liksom `c.map(to, from)`.

### Ett record med nästlat record, lista, `Optional`, enum, `Instant` och en egen värdetyp

#### Jackson

```java
record Email(@JsonValue String value) {
    @JsonCreator
    Email {
        if (value.indexOf('@') < 1) throw new IllegalArgumentException("not an email address");
    }
}
enum Status { ACTIVE, CLOSED }
record Address(String street, String city) {}

@JsonIgnoreProperties(ignoreUnknown = false)      // Spring Boot ignorerar okända medlemmar
@JsonInclude(JsonInclude.Include.NON_ABSENT)
record Customer(String name, Email email, Address address, List<String> tags,
                Optional<String> note, Status status,
                @JsonProperty("created_at") Instant created) {}

ObjectMapper mapper = JsonMapper.builder()
        .addModule(new JavaTimeModule()).addModule(new Jdk8Module())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
Customer c = mapper.readValue(body, Customer.class);
String json = mapper.writeValueAsString(c);
```

#### Spola

```java
import static spola.json.codec.JsonCodec.field;
import static spola.json.codec.JsonCodec.optionalField;
import static spola.json.codec.JsonCodecs.*;

record Email(String value) {
    Email {
        if (value.indexOf('@') < 1) throw new IllegalArgumentException("not an email address");
    }
}
enum Status { ACTIVE, CLOSED }
record Address(String street, String city) {}
record Customer(String name, Email email, Address address, List<String> tags,
                Optional<String> note, Status status, Instant created) {}

static final JsonCodec<Email> EMAIL = STRING.map(Email::new, Email::value);
static final ObjectCodec<Address> ADDRESS = JsonCodec.object(Address::new,
        field("street", STRING, Address::street),
        field("city", STRING, Address::city));
static final ObjectCodec<Customer> CUSTOMER = JsonCodec.object(Customer::new,
        field("name", STRING, Customer::name),
        field("email", EMAIL, Customer::email),
        field("address", ADDRESS, Customer::address),
        field("tags", list(STRING), Customer::tags),
        optionalField("note", STRING, Customer::note),
        field("status", enumOf(Status.values()), Customer::status),
        field("created_at", INSTANT, Customer::created));

Customer c = CUSTOMER.read(body);
String json = CUSTOMER.write(c);
// {"name":"Ada","email":"ada@example.com","address":{"street":"Storgatan 1","city":"Lund"},
//  "tags":["vip"],"status":"ACTIVE","created_at":"2026-10-02T09:18:00Z"}
```

Konstruktorn och fälten kontrolleras av kompilatorn: fälten står i konstruktorns
parameterordning och deras typer måste stämma (`Customer::new` med fel antal eller typ av
fält kompilerar inte). Medlemmarna skrivs i fältens ordning. `object` finns med 1–16 fält;
för fler finns en allmän form där fälten är nycklar:

```java
static final Field<Big, String> A = field("a", STRING, Big::a);
static final Field<Big, Integer> B = field("b", INT, Big::b);
// ... fler fält
static final ObjectCodec<Big> BIG = JsonCodec.object(List.of(A, B /* , ... */),
        values -> new Big(values.get(A), values.get(B) /* , ... */));
```

Varianter av fält:

| Fält | Saknad medlem | `null` | Vid kodning |
|---|---|---|---|
| `field("name", STRING, P::name)` | problem | problem | skrivs alltid |
| `field("qty", INT, P::qty).orElse(1)` | `1` | problem | skrivs alltid |
| `field("nick", nullable(STRING), P::nick)` | problem | `null` | `null` skrivs som `null` |
| `optionalField("note", STRING, P::note)` | `Optional.empty()` | `Optional.empty()` | utelämnas när tom |

### Sealed hierarkier

#### Jackson

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Circle.class, name = "circle"),
        @JsonSubTypes.Type(value = Square.class, name = "square")})
sealed interface Shape permits Circle, Square {}
record Circle(double radius) implements Shape {}
record Square(double side) implements Shape {}
```

#### Spola

```java
sealed interface Shape permits Circle, Square {}
record Circle(double radius) implements Shape {}
record Square(double side) implements Shape {}

static final Variant<Shape, Circle> CIRCLE = Variant.of("circle",
        JsonCodec.object(Circle::new, field("radius", DOUBLE, Circle::radius)));
static final Variant<Shape, Square> SQUARE = Variant.of("square",
        JsonCodec.object(Square::new, field("side", DOUBLE, Square::side)));
static final JsonCodec<Shape> SHAPE = JsonCodec.sealed("type",
        shape -> switch (shape) {
            case Circle c -> CIRCLE.tag(c);
            case Square s -> SQUARE.tag(s);
        },
        CIRCLE, SQUARE);

SHAPE.write(new Circle(1.5));                        // {"type":"circle","radius":1.5}
SHAPE.read("{\"side\":2,\"type\":\"square\"}");      // new Square(2.0)
```

Kodningen väljer variant med en `switch` utan `default`. Kompilatorn kontrollerar att den
täcker alla tillåtna subtyper, så en ny subtyp som läggs till i `permits` utan en gren ger
**kompileringsfel** (`the switch expression does not cover all possible input values`) i
stället för ett körfel; Jackson märker en saknad `@JsonSubTypes.Type` först när ett sådant
värde skrivs eller läses. Inga `Class`-objekt används. Diskriminatorn skrivs först och får stå
var som helst vid läsning. Saknad diskriminator, en som inte är en sträng och en okänd tagg
ger problem som listar taggarna. Två varianter med samma tagg, eller en variant med ett fält
som heter som diskriminatorn, är fel redan när kodeken skapas.

### Rekursiva typer

```java
record Node(String name, List<Node> children) {}
static final ObjectCodec<Node> NODE = JsonCodec.object(Node::new,
        field("name", STRING, Node::name),
        field("children", list(lazy(() -> Codecs.NODE)), Node::children));
```

`lazy` hämtar kodeken första gången den används (konstanten är inte tilldelad medan dess
egen initierare körs). Djupet räknas i objekt och arrayer i båda riktningarna; över 512
nivåer (parserns gräns, `JsonLimits.DEFAULT`) blir det ett problem, aldrig
`StackOverflowError`, också för ett träd byggt i kod eller ett rekursivt Java-värde.

### Felrapporten

Avkodningen samlar **alla** problem i dokumentet, i dokumentordning, och kastar sedan
`JsonMappingException`. Varje `JsonProblem` har en sökväg och ett meddelande:

```java
String body = """
        {
          "name": "Ada",
          "email": "ada.example.com",
          "address": {"street": "Storgatan 1"},
          "tags": ["vip", 7],
          "status": "active",
          "created_at": "2026-10-02 09:18",
          "admin": true
        }
        """;
try {
    CUSTOMER.read(body);
} catch (JsonMappingException e) {
    e.problems().forEach(System.out::println);
}
```

```text
$.email: invalid value: not an email address
$.address.city: missing member
$.tags[1]: expected string but found number
$.status: unknown value "active"; expected one of "ACTIVE", "CLOSED"
$.created_at: expected an ISO-8601 instant such as 2026-10-02T09:18:00Z but found "2026-10-02 09:18"
$.admin: unknown member; expected one of "name", "email", "address", "tags", "note", "status", "created_at"
```

Jackson stannar vid första felet (här `email`, eller `admin` om okända medlemmar inte
ignoreras), och med Spring Boots inställningar passerar `admin` tyst.

- Saknade medlemmar rapporteras efter objektets övriga medlemmar (de har ingen plats i
  dokumentet).
- Ett undantag från en konstruktor eller från `to` i `map` (t.ex. `IllegalArgumentException`
  från en kompakt konstruktor) blir ett problem på värdets sökväg med undantagets meddelande.
  Konstruktorn anropas bara när alla fält avkodades.
- Högst 100 problem listas; finns fler läggs ett sista problem på `$` som säger hur många som
  utelämnades. `getMessage()` är en rad på högst 2 000 tecken.
- Sökvägar och meddelanden är alltid utskrivbar ASCII: tecken utanför (radbrytningar, U+2028,
  styrtecken) skrivs som `\uXXXX`. Ett värde citeras med högst 40 tecken och ett
  undantagsmeddelande med högst 200 (gränsen på 40 gäller inte undantagstext som
  applikationen skrivit). Fältnamn som kodeken deklarerar skrivs alltid helt i sökvägen, som
  i `JsonAccessException`; namn som kommer från dokumentet (okända medlemmar, nycklar i
  `map`) kortas till 40 tecken. Rapporten kan
  därför loggas och skickas till en klient som den är.
- Ett dokument som inte är JSON ger `JsonParseException` från parsern, oinslaget.
- Kodning stannar vid första värdet som inte går att skriva (`null` utan `nullable`, `NaN`,
  ett undantag från en getter) och kastar `JsonMappingException` med dess sökväg, t.ex.
  `$.address.city: value is null (wrap the codec in nullable(...) to write null)`.

### Avvikelser från Jackson och Spring Boots standard vid mappning

| Område | Jackson/Spring Boot (standard) | Spola |
|---|---|---|
| Okända medlemmar | Spring Boot ignorerar dem (`FAIL_ON_UNKNOWN_PROPERTIES=false`) | Avvisas, ett problem per medlem; `ignoringUnknown()` slår av det per kodek. Skäl: mass assignment (en klient sätter `admin`) och stavfel (`"emial"`) passerar annars tyst. |
| Tvångsomvandling | `"42"` blir `42`, `42` blir `"42"`, `1.5` blir `1` för `int` (`ACCEPT_FLOAT_AS_INT`), `true` blir `"true"`, `1e400` blir `Infinity` för `double` | Ingen: varje kodek tar exakt en JSON-typ; `1.0` och `1e2` är `int` (exakt heltal), `1.5` och `2147483648` är det inte; `1e400` är inte en `double` (ingen `Infinity`) |
| `null` | `null` blir `null` i fältet (eller `0`/`false` för primitiver) | `null` för ett obligatoriskt fält är ett problem; `nullable(c)` eller `optionalField` säger uttryckligen att `null` är tillåtet. Vid kodning ger `null` utan `nullable` ett fel med sökvägen. |
| Saknade medlemmar | Blir `null`/`0` (`FAIL_ON_MISSING_CREATOR_PROPERTIES=false`) | Problem, utom för `optionalField` och `orElse(...)` |
| Tomma `Optional` | Skrivs som `null` (med `Jdk8Module`) | `optionalField` utelämnar medlemmen (som `@JsonInclude(NON_ABSENT)`) |
| Datum och tid | `JavaTimeModule` krävs; Spring Boot skriver ISO-8601 | Alltid ISO-8601-strängar; ogiltig text (`2026-02-30`, `Instant` utan zon) är ett problem |
| Felinsamling | Första felet kastas | Alla fel (högst 100) med sökväg i en `JsonMappingException` |
| Polymorfism | `@JsonTypeInfo`/`@JsonSubTypes` med `Class`-objekt; saknad subtyp märks vid körning | `switch` över den sealed typen; saknad subtyp är kompileringsfel |
| Mängder | Dubbletter i en `Set` tas bort tyst | Dubbletter är ett problem |
| Stora tal | `BigDecimal` utan gräns för exponenten | `BIG_DECIMAL` kräver skala inom ±10 000 och `BIG_INTEGER` högst 10 000 siffror (som `JsonNumber`), så `1e1000000000` inte kan låsa en tråd |
| Namn | Komponentnamnet, eller en namnstrategi | Namnet står i `field(...)`; ingen namnstrategi |
