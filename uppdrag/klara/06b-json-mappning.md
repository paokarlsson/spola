# JSON del 2: explicit mappning mellan records och JSON

## Mål
Mappning mellan Java-typer (records, enums, listor, mappar, `java.time`, egna värdetyper,
sealed hierarkier) och trädmodellen i `spola.json`, som ersätter det Spring Boot använder
Jackson till: `ObjectMapper.readValue`/`writeValueAsString`, `@JsonProperty`,
`@JsonIgnoreProperties`, `@JsonInclude`, `@JsonTypeInfo`/`@JsonSubTypes` och
`JavaTimeModule`. Mappningen skrivs som vanlig Java-kod: **kodekar är värden** som byggs av
method references och lambdas, utan annoteringar, utan reflektion och utan namnkonventioner.
Avkodning av felaktig indata ger **alla** fel på en gång, var och ett med sin JSON-sökväg,
så att uppdrag 08 (web) och 09 (validation) kan returnera dem till klienten.

## Bakgrund / kontext
Uppdrag 01–05 och 06a är klara. Läs `framework/REGLER.md`, `framework/PROGRESS.md` och
`framework/ARCHITECTURE.md` (avsnittet "JSON del 1") innan du börjar. Alla tester skrivs med
`spola.test`.

Det som finns i `spola.json` efter 06a (se `PROGRESS.md`):
- `sealed interface JsonValue` med `JsonObject` (ordning bevaras, `members()` som
  `SequencedMap`, `JsonObject.builder()`), `JsonArray`, `JsonString`, `JsonNumber` (texten
  behålls, numerisk likhet, `intValueExact`/`longValueExact`/`bigIntegerValueExact` som
  kastar `ArithmeticException`, `bigDecimalValue`, `doubleValue`), `JsonBoolean`, `JsonNull`.
- `Json.parse` (strikt RFC 8259, `JsonLimits` med `maxDepth` 512 m.m.,
  `JsonParseException`) och `Json.write`/`writePretty`/`writeUtf8`.
- Läsare med sökväg (`obj.string("name")` …) som kastar `JsonAccessException` vid **första**
  felet. De är till för handskriven navigering; detta uppdrag lägger till mappning som samlar
  alla fel.
- Sökvägar skrivs som `$.items[2].sku`; tecken utanför utskrivbar ASCII i namn escapas
  (`JsonAccessException`, `Paths`). Samma format och samma escaping ska användas här.

Rad 06 i `PLAN.md` är delad i 06a (klar) och 06b (detta uppdrag), se ARCHITECTURE.md.

Erfarenhet från tidigare iterationer: meddelanden som innehåller extern data (nycklar,
undantagsmeddelanden) har gång på gång kunnat förfalska loggrader eller bli orimligt långa.
Varje meddelande som byggs här ska vara kort och fritt från råa styrtecken.

### Designbeslut (fattade av planeraren, dokumenteras i ARCHITECTURE.md)
- **Plats:** nytt exporterat paket `spola.json.codec` i modulen `spola.json`. Trädmodellen i
  `spola.json` får inte bero på `spola.json.codec` (beroendet går bara åt ena hållet).
- **Kodeken:** `JsonCodec<T>` med `JsonValue encode(T value)` och `T decode(JsonValue json)`,
  plus bekvämligheterna `T read(String json)`, `T read(byte[] utf8)` (parse med
  `JsonLimits.DEFAULT` och sedan decode), `String write(T value)` och
  `String writePretty(T value)`. Kodekar är oföränderliga och trådsäkra och kan delas som
  `static final`-konstanter.
- **Fel:** `decode` kastar `JsonMappingException` (okontrollerat, i `spola.json.codec`) med
  `List<JsonProblem> problems()`, där `record JsonProblem(String path, String message)`.
  Alla fel i dokumentet samlas, inte bara det första: ett objekt med tre felaktiga fält ger
  tre problem, en lista med felaktiga element ger ett problem per element. Antalet problem är
  begränsat till 100; finns fler läggs ett sista problem på `$` som säger hur många som
  utelämnades. `getMessage()` sammanfattar (antal + de första problemen) och är högst 2 000
  tecken. Meddelanden innehåller aldrig råa styrtecken och citerar högst 40 tecken av ett
  värde. Parsefel förblir `JsonParseException` och slås inte in.
- **Inbyggda kodekar** (konstanter eller fabriker på `JsonCodecs` eller `JsonCodec`, välj och
  dokumentera): `STRING`, `BOOLEAN`, `INT`, `LONG`, `DOUBLE`, `BIG_DECIMAL`, `BIG_INTEGER`,
  `UUID`, `INSTANT`, `LOCAL_DATE`, `LOCAL_TIME`, `LOCAL_DATE_TIME`, `OFFSET_DATE_TIME`,
  `DURATION`, och `JSON` (identitet för `JsonValue`).
  - **Ingen tyst tvångsomvandling:** `"42"` är inte ett `int`, `42` är inte en `String`,
    `1.5` är inte ett `int` (`1.0` och `1e2` är det, eftersom värdet är exakt ett heltal),
    tal utanför typens intervall är fel (inte trunkering, inte `Infinity` för `DOUBLE`).
    `BIG_INTEGER` och `BIG_DECIMAL` skyddas mot exponent-DoS på samma sätt som `JsonNumber`.
  - `java.time` skrivs och läses som ISO-8601-strängar (som Spring Boots standard
    `WRITE_DATES_AS_TIMESTAMPS=false`). Ogiltig text ger ett problem, inte ett
    `DateTimeParseException`.
  - `DOUBLE.encode(NaN)` och oändligheter är fel.
- **Kombinatorer:** `list(c)` (oföränderlig `List`), `set(c)` (oföränderlig, ordning bevaras,
  dubbletter är fel), `map(c)` (`Map<String, V>`, ordning bevaras), `optional(c)`
  (`Optional<T>`), `nullable(c)` (JSON `null` ↔ Java `null`), `lazy(Supplier<JsonCodec<T>>)`
  för rekursiva typer, och `c.map(Function<T, R> to, Function<R, T> from)` för egna
  värdetyper (`STRING.map(Email::new, Email::value)`). Ett undantag från `to` (t.ex. ett
  `IllegalArgumentException` från en kompakt konstruktor) blir ett problem på värdets sökväg
  med undantagets meddelande (saniterat och avkortat), inte ett undantag som läcker ut.
- **Enums:** `enumOf(E[] values)` mappar på `Enum.name()` och `enumOf(E[] values,
  Function<E, String> name)` på ett eget namn. Ingen `Class.getEnumConstants`. Dubbla namn är
  ett fel när kodeken skapas. Ett okänt namn ger ett problem som listar de tillåtna (avkortat).
- **Records/objekt:** `JsonCodec.object(konstruktor, fält...)` med typade ariteter 1–16, där
  konstruktorn är en method reference (`Person::new`) och varje fält beskrivs av
  `field("name", STRING, Person::name)`. Varianter:
  - `field` är obligatoriskt: saknad medlem och JSON `null` är fel.
  - `optionalField("note", STRING, Person::note)` för en komponent av typen `Optional<T>`:
    saknad eller `null` ger `Optional.empty()`; vid kodning **utelämnas** medlemmen när värdet
    är tomt (som `@JsonInclude(NON_ABSENT)`).
  - `field("qty", INT, Line::qty).orElse(1)` (eller motsvarande, välj namn) ger ett
    standardvärde när medlemmen saknas.
  - Medlemmarna skrivs i fältordningen. Konstruktorn anropas bara om alla fält avkodades;
    annars rapporteras alla fältens problem. Ett undantag från konstruktorn blir ett problem
    på objektets sökväg (som för `map`).
  - **Okända medlemmar avvisas som standard** (ett problem per okänd medlem). Det avviker
    från Spring Boot (som sätter `FAIL_ON_UNKNOWN_PROPERTIES=false`) och motiveras med
    mass assignment och stavfel som annars passerar tyst. `ignoringUnknown()` (eller
    motsvarande) slår av det explicit för en kodek.
  - Två fält med samma JSON-namn är ett fel när kodeken skapas.
  - För fler än 16 komponenter finns en allmän form där fälten är typade nycklar och
    konstruktorn får en läsare: `JsonCodec.object(List.of(A, B, ...), f -> new Big(f.get(A),
    f.get(B), ...))`. Ariteterna 1–16 får gärna bygga på den.
- **Sealed hierarkier med diskriminator, utan `Class`-tokens:** kodningen väljer variant med
  en `switch` som kompilatorn kontrollerar är uttömmande, inte via `Class.isInstance` eller
  `getPermittedSubclasses`. Ungefär:
  ```java
  static final Variant<Shape, Circle> CIRCLE = Variant.of("circle", CIRCLE_CODEC);
  static final Variant<Shape, Square> SQUARE = Variant.of("square", SQUARE_CODEC);
  static final JsonCodec<Shape> SHAPE = JsonCodec.sealed("type",
          shape -> switch (shape) {
              case Circle c -> CIRCLE.tag(c);
              case Square s -> SQUARE.tag(s);
          },
          CIRCLE, SQUARE);
  ```
  Exakta namn och signaturer väljer byggaren, men principen gäller: en ny permitted subtyp
  utan gren ger **kompileringsfel**. Diskriminatorn skrivs som första medlem; vid läsning får
  den stå var som helst i objektet. Saknad diskriminator, fel typ på den och okänd tagg ger
  problem som listar de tillåtna taggarna. Dubbla taggar, och en tagg-medlem som krockar med
  ett fältnamn i en variants objektkodek, är fel när kodeken skapas. En variant som returneras
  av switchen men inte finns i listan ger ett tydligt fel vid kodning (dokumentera).
- **Kodning av `null`:** ett `null`-värde där kodeken inte är `nullable` ger
  `JsonMappingException` med sökvägen (t.ex. `$.address.city`), inte ett
  `NullPointerException` djupt inne i koden.
- **Djup:** avkodning och kodning håller ett djup och ger ett problem (inte
  `StackOverflowError`) över `JsonLimits.DEFAULT.maxDepth()`, även för träd byggda i kod och
  för rekursiva Java-värden kodade med `lazy`.
- **Ingen reflektion:** varken `java.lang.reflect`, `java.lang.invoke`, `Class.isInstance`,
  `Class.cast`, `getRecordComponents` eller `getEnumConstants` i huvudkoden. Allt som
  kodeken vet kommer från argumenten.

## Krav
- Paketet `spola.json.codec` i `spola.json` enligt besluten ovan, exporterat i
  `module-info.java`. Fortfarande inga `requires` på andra ramverksmoduler.
- Publikt API med Javadoc (doclint är på i bygget).
- `README.md` i `modules/json/` utökas med ett avsnitt om mappning: en record med nästlat
  record, lista, `Optional`, enum, `Instant` och en egen värdetyp, en sealed hierarki, och
  felrapporten för ett felaktigt dokument, bredvid motsvarande Jackson-kod
  (`readValue`, `@JsonProperty`, `@JsonIgnoreProperties`, `@JsonInclude`,
  `@JsonTypeInfo`/`@JsonSubTypes`, `JavaTimeModule`). En tabell över avvikelser från
  Jackson/Spring Boots standard (okända medlemmar, tvångsomvandling, `null`, utelämnade
  `Optional`, datumformat, felinsamling).

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0.
2. `spola.json.codec` exporteras; `module-info.java` har inga `requires` på andra
   ramverksmoduler; inga klasser i paketet `spola.json` importerar `spola.json.codec`.
3. Huvudkoden i `spola.json.codec` innehåller inte `java.lang.reflect`, `java.lang.invoke`,
   `isInstance`, `.cast(`, `getRecordComponents`, `getEnumConstants` eller
   `getPermittedSubclasses` (kontrolleras av ett test som läser källfilerna, eller med `grep`
   som granskarna kan köra).
4. Ett test gör rundresa (`decode(encode(v)).equals(v)` och `read(write(v)).equals(v)`) för
   en record-graf som använder alla inbyggda kodekar och alla kombinatorer, samt en rekursiv
   typ via `lazy`.
5. Ett test visar strikthet för skalärer, tabelldrivet med minst 30 fall: `"42"` som `INT`,
   `42` som `STRING`, `1.5` som `INT`, `2147483648` som `INT`, `1e400` som `DOUBLE`,
   `1e1000000000` som `BIG_INTEGER` (fel inom 1 sekund), ogiltigt `UUID`, ogiltigt datum
   (`2026-02-30`) för `LOCAL_DATE`, `Instant` utan zon, ogiltig `Duration`, okänt enumnamn,
   `true` som `STRING`, och de accepterade `1.0` och `1e2` som `INT`. Varje avvisat fall ger
   `JsonMappingException` med rätt sökväg, aldrig något annat undantag.
6. Ett test visar felinsamling: ett dokument med minst fem fel på olika nivåer (saknat fält,
   fel typ, okänd medlem, felaktigt listelement på index 3, ogiltigt enumvärde i ett nästlat
   objekt) ger exakt de förväntade `JsonProblem` (sökväg och meddelande jämförs), i
   dokumentordning.
7. Ett test visar gränsen på 100 problem: en lista med 10 000 felaktiga element ger 101
   problem (100 + sammanfattningen), och `getMessage()` är högst 2 000 tecken.
8. Ett test visar att meddelanden är säkra: en okänd medlem vars namn innehåller `\n`, `\r`,
   U+0000 och U+2028, och ett undantag från en kompakt konstruktor vars meddelande innehåller
   radbrytningar och är 10 000 tecken långt, ger problem och `getMessage()` utan råa
   styrtecken och med värden avkortade.
9. Ett test visar objekt: `optionalField` (saknad, `null`, närvarande; utelämnas vid kodning
   när tom), standardvärde via `orElse` (eller valt namn), JSON `null` för obligatoriskt fält
   är fel, okända medlemmar avvisas som standard och accepteras med `ignoringUnknown()`,
   medlemmar skrivs i fältordning (utdata jämförs tecken för tecken), konstruktorn anropas
   inte när ett fält har fel, och dubbla fältnamn ger fel när kodeken skapas.
10. Ett test visar en kodek med 16 fält och en med minst 20 fält via den allmänna formen,
    båda med rundresa och felinsamling.
11. Ett test visar sealed hierarkier: kodning med diskriminatorn först, läsning med
    diskriminatorn sist, saknad/okänd/icke-sträng diskriminator ger problem som listar
    taggarna, dubbla taggar och tagg som krockar med ett fältnamn ger fel när kodeken skapas.
    Dessutom finns en kommentar i testet (eller i README) som visar att en ny permitted
    subtyp utan gren i `switch` ger kompileringsfel; byggaren visar det för granskarna genom
    att kompilera ett exempel med `javac` och redovisa felet i sin rapport.
12. Ett test visar `map` på egna värdetyper: ett `IllegalArgumentException` från
    `Email::new` blir ett problem på rätt sökväg, och kodningen går via `Email::value`.
13. Ett test visar `null` vid kodning: ett record med `null` i ett obligatoriskt fält ger
    `JsonMappingException` med sökvägen; med `nullable` skrivs `null`.
14. Ett test visar djupet: ett rekursivt värde (via `lazy`) med 100 000 nivåer, byggt i kod,
    ger `JsonMappingException` (inte `StackOverflowError`) både vid kodning och vid avkodning
    av ett lika djupt `JsonValue` byggt i kod, på en plattformstråd och en virtuell tråd.
15. Ett test delar samma kodekar mellan 1 000 virtuella trådar som läser och skriver
    samtidigt, med samma resultat i alla.
16. `README.md` i `modules/json/` har mappningsavsnittet med Jackson-jämförelsen och
    avvikelsetabellen enligt kraven.
17. Inga annoteringar utöver tillåtelselistan, inga beroenden utöver JDK:n (guardrails
    grön), inga nya filtyper under `framework/`, ingen regel i `framework/REGLER.md` bruten.
18. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade, inklusive besluten om okända
    medlemmar, ingen tvångsomvandling, utelämnade `Optional`, felinsamling och gränsen på
    100 problem, sealed hierarkier via `switch` i stället för `Class`-tokens, och varför
    ingen reflektion behövs.

## Utanför scope
- Generering av kodekar från records med `spola.codegen` (kan bli ett senare uppdrag).
- Strömmande mappning av dokument större än minnet.
- Andra namnstrategier (snake_case automatiskt), mixins, vyer, `@JsonUnwrapped`-motsvarighet,
  polymorfism med diskriminator utanför objektet (wrapper-objekt eller arrayer).
- Mappar med andra nycklar än `String`.
- Integration med web (uppdrag 08) och validering (uppdrag 09).
- JSON som källa i `spola.config`.

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Ingen reflektion i modulens huvudkod (se kriterium 3). Inga nya
filtyper i `framework/`. Ingen inkopierad tredjeparts testdata.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
5
