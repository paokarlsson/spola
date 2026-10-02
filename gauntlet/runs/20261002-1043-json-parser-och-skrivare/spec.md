# Spec: JSON part 1: tree model, strict parser and writer (spola.json)

## Task
Build a new standalone module `spola.json` (`framework/modules/json/`) with an immutable sealed
tree model, a strict RFC 8259 parser with configurable limits (`JsonLimits`), and a compact and
pretty writer. The full mission, including every design decision the planner made, is in
`mission.md` in this run directory. Those design decisions and requirements are part of this contract.

## Ground rules
Everything in `framework/REGLER.md` applies. A violation is a BLOCKER.

## Work product
`framework/`

## Verification command
`java Build.java` in `framework/`.

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


Note on criterion 8: the example is two keys that are spelled differently in the source but decode
to the same string, e.g. `"a"` and `"a"`.

## Out of scope
- Mapping between records and JSON (codecs, error collection with paths, polymorphism): mission 06b.
- Streaming (event/token-based) API for documents larger than memory.
- JSON Pointer, JSON Patch, JSON Schema, JSON5, NDJSON.
- JSON as a source in `spola.config`; integration with web (mission 08).

## Assumptions
- Test data lives as strings or byte arrays in test code (no `.json` files; `Allowlist.DOCUMENT_EXTENSIONS` unchanged).
- No third-party test suite is copied in; the cases are written from scratch from RFC 8259 / RFC 3629.
- The builder chooses the exception type for the exact number conversions (`ArithmeticException` or its own) and documents it.
