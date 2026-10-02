# spola.json

JSON för Spola: en oföränderlig trädmodell som en sealed hierarki, en strikt parser enligt
RFC 8259 med gränser, och en skrivare (kompakt och indenterad). Modulen ersätter den del av
Jackson som Spring Boot använder för att läsa och skriva JSON-träd (`ObjectMapper.readTree`,
`JsonNode`, `ObjectNode`, `writerWithDefaultPrettyPrinter`). Inga annoteringar, ingen
reflektion, inga `requires` på andra Spola-moduler. Mappning mellan records och JSON kommer i
uppdrag 06b och bygger på den här modellen.

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
