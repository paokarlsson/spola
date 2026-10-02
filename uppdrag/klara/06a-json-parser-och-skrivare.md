# JSON del 1: trädmodell, parser och skrivare

## Mål
En fristående JSON-modul `spola.json` som ersätter den del av Jackson som Spring Boot
använder för att läsa och skriva JSON: en strikt parser enligt RFC 8259, en oföränderlig
trädmodell som en sealed hierarki och en skrivare (kompakt och indenterad). Parsern tar emot
extern data och är säkerhetskritisk (REGLER §4): den ska avvisa allt som inte är JSON, ge fel
med position, och ha gränser så att illasinnad indata inte kan ge `StackOverflowError`,
orimlig minnesanvändning eller orimlig CPU-tid. Mappning till och från records kommer i
uppdrag 06b och bygger på denna modell.

## Bakgrund / kontext
Uppdrag 01–05 är klara: bygge och guardrails, testkörare (`spola.test`), DI-container
(`spola.di`), konfiguration (`spola.config`), loggning och hjälpare (`spola.util`),
builders och with-metoder (`spola.codegen`) och integrationsprogrammet `spola.demo`. Läs
`framework/REGLER.md`, `framework/PROGRESS.md` och `framework/ARCHITECTURE.md` innan du
börjar. Alla tester skrivs med `spola.test`. Varje modul och dess testingång läggs till
explicit i `MODULES` i `Build.java`.

Rad 06 i `PLAN.md` delas av planeraren i två uppdrag, eftersom parsning och mappning var för
sig är tillräckligt stora för fem rundor och parsern är säkerhetskritisk:
- **06a (detta uppdrag):** trädmodell, parser, skrivare, gränser.
- **06b:** explicit mappning mellan records och JSON (kodekar som värden, felrapport med
  JSON-sökväg för alla fel samtidigt, sealed hierarkier med diskriminator).

Observera från `PROGRESS.md`: guardrails tillåter bara `.java` och `.md` i `framework/`.
Testdata (JSON-dokument) skrivs därför som strängar eller byte-arrayer i testkoden, inte som
incheckade `.json`-filer. Utöka inte `Allowlist.DOCUMENT_EXTENSIONS`. Ingen tredjeparts
testsvit (t.ex. JSONTestSuite) får kopieras in (REGLER §1); testfallen skrivs själva utifrån
grammatiken i RFC 8259 och reglerna i RFC 3629 (UTF-8), med hänvisning till avsnitt.

Erfarenheten från 05c (`spola.demo`, `spola.config`): extern indata kan börja med ett byte
order mark, och styrtecken i data som skrivs ut har varit en återkommande felkälla. Båda
hanteras uttryckligen nedan.

### Designbeslut (fattade av planeraren, dokumenteras i ARCHITECTURE.md)
- **Trädmodell:** `sealed interface JsonValue permits JsonObject, JsonArray, JsonString,
  JsonNumber, JsonBoolean, JsonNull`, alla oföränderliga och med värdesemantik (`equals`/
  `hashCode`), så att `switch` med mönster täcker alla fall utan `default`.
  - `JsonObject` bevarar medlemmarnas ordning (insättningsordning) och har uppslag på namn.
  - `JsonNumber` lagrar talets text exakt som den stod i dokumentet (efter validering mot
    grammatiken) och omvandlar vid begäran: `intValueExact()`, `longValueExact()`,
    `bigIntegerValueExact()`, `bigDecimalValue()`, `doubleValue()`. De exakta kastar
    `ArithmeticException` (eller ett eget undantag, välj och dokumentera) vid decimaler eller
    överskridet intervall i stället för att trunkera. Likhet mellan två `JsonNumber` avgörs
    numeriskt (`1.0` och `1` och `1e0` är lika, hashkoden konsekvent med det); beslutet
    dokumenteras.
  - Fabriker för att bygga träd i kod (`JsonObject.of(...)`/en builder, `JsonArray.of(...)`,
    `JsonNumber.of(long)`, `of(BigDecimal)`, `of(double)`). `of(double)` avvisar `NaN` och
    oändligheter (de finns inte i JSON).
- **Strikt parser, inga utvidgningar:** inga kommentarer, inga avslutande komman, inga
  enkelfnuttar, inga oescapade styrtecken (U+0000–U+001F) i strängar, inga ledande nollor,
  inget `+` först, inga `NaN`/`Infinity`, inget innehåll efter toppvärdet utom blanktecken.
  Vilket JSON-värde som helst får stå på toppnivå (RFC 8259 §2).
- **Dubbla nycklar i ett objekt avvisas** med fel som namnger nyckeln och positionen.
  Motivering: RFC 8259 §4 lämnar beteendet öppet, och olika parsrar som väljer olika värden är
  en känd källa till säkerhetsfel (t.ex. en kontroll som läser första och en hanterare som
  läser sista värdet).
- **Unicode:** `\uXXXX`-par av surrogat sätts ihop; ett ensamt surrogat (escapat eller ej)
  avvisas. Byte-indata ska vara UTF-8 och läses strikt (felaktiga sekvenser, överlånga
  kodningar och kodade surrogat avvisas enligt RFC 3629). Ett inledande byte order mark
  (EF BB BF / U+FEFF) hoppas över i både byte- och teckenindata (RFC 8259 §8.1 tillåter det),
  på samma sätt som `spola.config` gör.
- **Gränser som värden:** ett record `JsonLimits` med minst maximalt djup (standard 512),
  maximal längd på en sträng, maximal längd på ett tals text, maximalt antal medlemmar/
  element per objekt/array och maximal storlek på hela indatan. Standardvärdena väljs och
  motiveras i ARCHITECTURE.md; `JsonLimits.DEFAULT` och ett sätt att ange egna finns.
  Parsern får inte vara rekursiv på ett sätt som ger `StackOverflowError` före djupgränsen
  på en vanlig trådstack (testas också på en virtuell tråd).
- **Fel:** ett `JsonParseException` (okontrollerat) med rad, kolumn och byte-/teckenoffset
  och en kort beskrivning av vad som förväntades. Meddelandet citerar inte långa delar av
  indatan och skriver aldrig ut styrtecken rått (escapa eller utelämna), så att ett fel kan
  loggas utan att förfalska loggrader.
- **Skrivaren** ger alltid giltig JSON: kompakt (`write`) och indenterad (`writePretty`, två
  blanksteg, deterministisk). Strängar escapas minimalt men säkert: `"`, `\`, U+0000–U+001F
  alltid; dessutom U+2028, U+2029 och U+007F (motivera i ARCHITECTURE.md). Tal skrivs med sin
  lagrade text. Utdata till `String`, `Appendable`/`Writer` och `OutputStream` (UTF-8).
  `parse(write(v)).equals(v)` gäller för alla träd.

## Krav
- Ny modul `spola.json` under `framework/modules/json/`, utan `requires` på andra
  ramverksmoduler (`spola.test` bara för tester). Exporterar paketet `spola.json`.
- `Json.parse(String)`, `Json.parse(byte[])`, `Json.parse(InputStream)` och
  `Json.parse(Reader)`, var och en också med `JsonLimits`. Strömmar läses inte längre än
  storleksgränsen (en ström som aldrig tar slut ger fel, inte `OutOfMemoryError`).
- Trädmodell, parser, gränser och skrivare enligt besluten ovan.
- Bekväma men explicita läsare på trädet som kastar ett tydligt fel med sökväg vid fel typ,
  t.ex. `obj.string("name")`, `obj.number("qty")`, `obj.object("address")`,
  `obj.optional("note")`. Felet nämner sökvägen (`$.address.city`) och förväntad och faktisk
  typ. (Fullständig mappning med insamling av alla fel är 06b.)
- Trådsäkerhet: ett träd kan läsas från många trådar samtidigt; parsern och skrivaren har
  inget delat föränderligt tillstånd.
- Ingen `java.lang.reflect` eller `java.lang.invoke` i huvudkoden, inga preview-funktioner.
- `README.md` i modulen: exempel på parse, navigering med `switch` över `JsonValue`, byggande
  i kod och skrivning, bredvid motsvarande Jackson-kod (`ObjectMapper.readTree`, `JsonNode`,
  `ObjectNode`, `writerWithDefaultPrettyPrinter`), och en tabell över avvikelser från
  Jacksons standardbeteende (dubbla nycklar, tal, kommentarer, gränser).

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0, och `spola.json` med sin
   testingång står i `MODULES` i `Build.java`.
2. Modulens huvudkod importerar inget från `java.lang.reflect` eller `java.lang.invoke` och
   `module-info.java` har inga `requires` på andra ramverksmoduler.
3. Ett test parsar exemplen i RFC 8259 §13 (objektet och arrayen) och de tre
   toppnivåexemplen i samma avsnitt (`"Hello world!"`, `42`, `true`), och kontrollerar trädet.
4. Ett tabelldrivet test med minst 40 dokument som ska accepteras och minst 60 som ska
   avvisas, vart och ett med en kommentar som hänvisar till regeln i RFC 8259 eller RFC 3629.
   De avvisade täcker minst: avslutande komma i objekt och array, kommentarer, enkelfnuttar,
   ledande nolla, `+1`, `.5`, `1.`, `1e`, `-`, `NaN`, `Infinity`, oescapat styrtecken (U+0000,
   tab, radbrytning) i sträng, ogiltig escape (`\x`, `\u12`), ensamt surrogat (hög och låg),
   oavslutad sträng/objekt/array, två toppvärden, tom indata, bara blanktecken, dubbel nyckel,
   och för byte-indata: ogiltig UTF-8-startbyte, avbruten sekvens, överlång kodning (`C0 AF`),
   kodat surrogat (`ED A0 80`), kodpunkt över U+10FFFF. Varje avvisat fall ger
   `JsonParseException` (aldrig något annat undantag eller `Error`).
5. Ett test visar att felmeddelandet anger rätt rad och kolumn för ett fel på rad 3, att ett
   styrtecken i indatan inte förekommer rått i meddelandet och att meddelandet för en indata
   på 1 MB är kortare än 500 tecken.
6. Ett test visar varje gräns i `JsonLimits`: precis vid gränsen accepteras, en över avvisas
   med `JsonParseException` som namnger gränsen. Särskilt: 100 000 nästlade `[` ger
   `JsonParseException` (inte `StackOverflowError`), både på en plattformstråd och en virtuell
   tråd, och en `InputStream` som aldrig tar slut ger fel vid storleksgränsen.
7. Ett test visar tal: `JsonNumber` behåller texten (`1.10` skrivs tillbaka som `1.10`),
   `intValueExact` på `1.5`, `2147483648` och `1e400` kastar, `bigDecimalValue` på
   `1e1000000000` returnerar utan att räkna ut alla siffror, `bigIntegerValueExact` på
   `1e1000000000` kastar inom 1 sekund (ingen DoS via exponent), och likheten `1`, `1.0`,
   `1e0`, `10e-1` med lika hashkoder.
8. Ett test visar dubbla nycklar avvisas (även när de skrivs olika men avkodas lika, t.ex.
   `"a"` och `"a"`), att medlemmarnas ordning bevaras vid parse och skrivning, och att
   ett BOM först hoppas över i `String`-, `byte[]`- och `InputStream`-indata men ett U+FEFF
   inne i dokumentet (utanför en sträng) är ett fel.
9. Ett test visar skrivaren: kompakt och indenterad utdata för ett givet träd jämförs med
   förväntad text tecken för tecken; strängar med `"`, `\`, U+0000–U+001F, U+007F, U+2028,
   U+2029 och ett surrogatpar escapas enligt besluten; `JsonNumber.of(Double.NaN)` kastar.
10. Ett test visar rundresa `parse(write(v)).equals(v)` och `parse(writePretty(v)).equals(v)`
    för alla accepterade dokument i kriterium 4.
11. Ett test visar de bekväma läsarna: fel typ och saknad nyckel ger ett fel med sökvägen
    (t.ex. `$.items[2].sku`) och förväntad och faktisk typ.
12. Ett test parsar samma dokument från 1 000 virtuella trådar och läser ett delat träd
    samtidigt, med samma resultat i alla.
13. `README.md` i modulen finns med Jackson-jämförelsen och avvikelsetabellen enligt kraven.
14. Inga `.json`-filer eller andra nya filtyper är incheckade under `framework/`, och
    `Allowlist.DOCUMENT_EXTENSIONS` är oförändrad.
15. Inga annoteringar utöver tillåtelselistan, inga beroenden utöver JDK:n (guardrails grön),
    ingen regel i `framework/REGLER.md` bruten.
16. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade, inklusive besluten om dubbla nycklar,
    talens lagring och likhet, BOM, escaping i skrivaren, standardgränserna och uppdelningen
    av rad 06 i 06a/06b.

## Utanför scope
- Mappning mellan records och JSON (kodekar, felinsamling med sökväg, polymorfism): 06b.
- Strömmande API (händelse-/tokenbaserad läsning och skrivning av dokument större än minnet).
- JSON Pointer (RFC 6901), JSON Patch, JSON Schema, JSON5, NDJSON.
- JSON som källa i `spola.config`.
- Integration med web (uppdrag 08).

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Ingen reflektion i modulens huvudkod. Inga nya filtyper i
`framework/`. Ingen inkopierad tredjeparts testdata.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
5
