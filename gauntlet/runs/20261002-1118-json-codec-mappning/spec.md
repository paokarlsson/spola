# Spec: JSON part 2 — explicit codec mapping between records and JSON

## Task
Add the package `spola.json.codec` to the module `spola.json`: codecs as values (built from
method references and lambdas, no annotations, no reflection) mapping records, enums, lists,
sets, maps, Optional, java.time, custom value types and sealed hierarchies to and from the
existing JSON tree model. Decoding collects all problems with JSON paths (capped at 100).
The full mission, including every design decision that is binding, is in `mission.md`
(same directory). The design decisions in `mission.md` are part of this contract.

## Ground rules
Everything in `framework/REGLER.md` applies. A violation is a BLOCKER.

## Work product
`framework/`

## Verification command
`java Build.java` in `framework/` (exit code 0).

## Acceptance criteria
(Taken over verbatim from `mission.md`.)

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


## Out of scope
- Generering av kodekar från records med `spola.codegen` (kan bli ett senare uppdrag).
- Strömmande mappning av dokument större än minnet.
- Andra namnstrategier (snake_case automatiskt), mixins, vyer, `@JsonUnwrapped`-motsvarighet,
  polymorfism med diskriminator utanför objektet (wrapper-objekt eller arrayer).
- Mappar med andra nycklar än `String`.
- Integration med web (uppdrag 08) och validering (uppdrag 09).
- JSON som källa i `spola.config`.


## Assumptions
- Exact names and signatures (where `mission.md` says "välj och dokumentera" / "eller
  motsvarande") are the builder's choice, as long as the principle holds and the choice is
  documented in Javadoc/README/ARCHITECTURE.md.
- Criterion 11's compile-error demonstration is done by the builder with `javac` outside
  the build and reported in `build.md`; the example file must not be left under `framework/`
  if it would break the build.
- Criterion 3's grep applies to main code only (not tests).
