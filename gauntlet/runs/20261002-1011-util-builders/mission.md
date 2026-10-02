Source: uppdrag/05b-util-builders.md

# Util del 2: builders och with-metoder för records (kodgenerering)

## Mål
Ersättning för Lomboks `@Builder`, `@Builder(toBuilder = true)` och `@With` för records,
utan annoteringar och utan runtime-reflektion. En fristående modul `spola.codegen` läser
källkoden för ett record och skriver en incheckad, läsbar Java-fil med builder och
with-metoder. Bygget kontrollerar vid varje körning att varje genererad fil stämmer med
sitt record, så att genererad kod aldrig tyst blir inaktuell. Programmeraren väljer
explicit vilka records som får stödet, och allt går att följa med "Go to definition".

## Bakgrund / kontext
Uppdrag 01–04, 05a och 05c är klara: bygge och guardrails, testkörare (`spola.test`),
DI-container (`spola.di`), konfiguration (`spola.config`), loggning och hjälpare
(`spola.util`) och integrationsprogrammet `spola.demo`. Läs `framework/REGLER.md`,
`framework/PROGRESS.md` och `framework/ARCHITECTURE.md` (avsnittet "Util del 1", där
uppdelningen av rad 05 i 05a/05b står) innan du börjar. Alla tester skrivs med
`spola.test`. Varje modul och dess testingång läggs till explicit i `MODULES` i
`Build.java`.

Records täcker redan `@Value`, `@Data`, `@Getter`, `@EqualsAndHashCode` och
`@AllArgsConstructor` (se `modules/util/README.md`). Det som saknas är att bygga ett record
med många komponenter läsbart och att kopiera det med en ändrad komponent. Java 25 har
inga "derived record creation"-uttryck (`with`) utanför preview, så stödet måste vara
vanlig kod. REGLER §2 förbjuder annoteringar som markering och REGLER §4 förbjuder
reflektion där den går att undvika och namnkonventioner för wiring. Kvar blir genererad,
incheckad källkod.

`spola.demo` har flera records (`OrderLine`, `Summary.Rejection`, `*Settings`) som kan
använda stödet.

Observera från `PROGRESS.md`: guardrails tillåter bara `.java` och `.md` i `framework/`,
och ett `.md`-dokument som parsar som Java ger rött bygge. Genererade filer är `.java` och
kontrolleras av guardrails som all annan källkod (inga annoteringar, alltså inget
`@Generated`).

### Designbeslut (fattade av planeraren, dokumenteras i ARCHITECTURE.md)
- **Opt-in genom `implements`:** för ett record `Person` genereras filen `PersonWith.java` i
  samma paket och katalog. Den innehåller ett `sealed interface PersonWith permits Person`
  (för ett nästlat record: `permits Yttre.Person`) med samma åtkomst som recordet
  (publikt record ger publikt gränssnitt, paketprivat ger paketprivat). Recordet väljer
  stödet explicit med `record Person(String name, int age) implements PersonWith`. Namnet
  `<Record>With` är en fast regel i generatorn, inte en konvention som något upptäcks
  genom.
- **Innehåll i gränssnittet:**
  - en `default`-metod `withX(T x)` per komponent som returnerar ett nytt record via den
    kanoniska konstruktorn (så att recordets kompakta konstruktor och validering alltid
    körs). Omvandlingen `(Person) this` är säker eftersom gränssnittet är sealed med
    recordet som enda tillåtna typ,
  - `default Builder toBuilder()` som förifylls med alla komponenter,
  - `static Builder builder()` och en nästlad `final class Builder` med en
    sättare per komponent (samma namn som komponenten, returnerar `Builder`) och
    `build()` som anropar den kanoniska konstruktorn. Komponenter som inte satts har
    Javas standardvärde (`null`, `0`, `false`), som i Lombok. En builder kan återanvändas:
    varje `build()` ger ett nytt record.
  - Användning: `PersonWith.builder().name("Ada").age(36).build()`,
    `person.withAge(37)`, `person.toBuilder().name("Grace").build()`.
- **Generatorn läser källkod, inte klasser:** `spola.codegen` parsar recordets källfil med
  Compiler Tree API (`jdk.compiler`, bara parsning). Typerna skrivs som i källan; den
  genererade filen får samma `import`-deklarationer som källfilen (inklusive
  `import module`) så att typnamnen löses likadant.
- **Explicit generering, kontroll i varje bygge:**
  - `java Build.java codegen <sökväg till .java-fil> <RecordNamn>` skapar eller skriver
    över `<RecordNamn>With.java`. `<RecordNamn>` kan vara ett nästlat namn (`Summary.Rejection`).
  - `java Build.java codegen` utan fler argument genererar om alla befintliga genererade
    filer under `modules/*/src`.
  - En genererad fil börjar med en fast rubrikkommentar som anger att den är genererad,
    från vilken fil och vilket record, och kommandot för att generera om. Rubriken är det
    enda sättet bygget känner igen genererade filer på.
  - Varje vanligt bygge genererar varje sådan fil på nytt i minnet och jämför tecken för
    tecken. Skiljer de sig (recordet har ändrats, eller filen har redigerats för hand) blir
    bygget rött med ett meddelande som namnger filen och kommandot för att generera om.
    Kontrollen ska ge det meddelandet även när den inaktuella filen inte längre kompilerar
    (t.ex. för att en komponent har bytt namn), alltså innan, eller i stället för, ett
    obegripligt javac-fel.

## Krav
- Ny modul `spola.codegen` under `framework/modules/codegen/` med testingång i `MODULES`.
  Den får bero på `jdk.compiler` och andra JDK-moduler men inte på andra Spola-moduler
  (`spola.test` bara för tester). Ingen modul behöver `requires spola.codegen` för att
  använda den genererade koden: genererad kod beror bara på JDK:n och det egna recordet.
- Ett publikt API i `spola.codegen` (t.ex. `RecordCodegen.generate(Path källfil, String
  recordNamn) -> String`) som ger den genererade källkoden som text, så att testerna kan
  anropa det utan filsystem och `Build.java` kan använda det för både generering och
  kontroll.
- **Stöd som krävs:** toppnivårecords och nästlade records (statiska, med paket- eller
  publik åtkomst), generiska records (`record Pair<A, B extends Comparable<B>>(A first, B second)`
  ger `PairWith<A, B extends Comparable<B>>` och `PairWith.Builder<A, B>` med rätt typer, utan
  varningar under `-Xlint:all -Werror`), primitiva komponenter, array- och
  varargs-komponenter (varargs-komponenten tas som array i sättaren och with-metoden) och
  komponenter med generiska typer (`Map<String, List<Integer>>`). Records i
  standardpaketet behöver inte stödjas (Spola-moduler har alltid paket).
- **Tydliga fel från generatorn** (undantag med meddelande som namnger fil och record, inget
  stackspår från `Build.java`): filen finns inte eller parsar inte, recordet finns inte i
  filen, typen med namnet är inte ett record, recordet är privat, recordet saknar
  komponenter, eller en komponent heter något som krockar med de genererade metoderna
  (`builder`, `toBuilder`, `build`) eller ger en `withX` som krockar.
- **Recordet implementerar inte gränssnittet än:** att generera fungerar ändå (filen
  skrivs), men bygget kompilerar då inte förrän recordet har `implements <Record>With`.
  Generatorn skriver i så fall en rad om det till konsolen. Dokumentera arbetsflödet.
- Den genererade koden ska vara läsbar (indenterad, en metod per komponent i komponentordning),
  ha Javadoc där `-Xdoclint:all/protected` kräver det, och kompilera utan varningar.
  Genereringen är deterministisk: samma källa ger byte för byte samma fil (radslut `\n`).
- **Integration:** minst två records i `spola.demo` använder stödet på riktigt (t.ex.
  builder i ett test och en with-metod i produktionskoden), och de genererade filerna är
  incheckade.
- `modules/codegen/README.md` med arbetsflödet (generera, implementera, ändra recordet,
  generera om) och genererad kod bredvid motsvarande Lombok-kod (`@Builder`,
  `@Builder(toBuilder = true)`, `@With`). Länka från `modules/util/README.md`.
- Ingen `java.lang.reflect` eller `java.lang.invoke` i modulens huvudkod eller i genererad
  kod. Inga preview-funktioner.

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0, och `spola.codegen` med sin
   testingång står i `MODULES` i `Build.java`.
2. `module-info.java` för `spola.codegen` har inga `requires` på andra Spola-moduler, och
   ingen genererad fil importerar något från `spola.*` utom sitt eget paket (kontrollerbart
   med sökning).
3. Ett test genererar källkod för ett vanligt record, kompilerar den tillsammans med
   recordet i minnet (`javax.tools`) och kör den: `builder()` med alla sättare, osatta
   komponenter ger standardvärden, `toBuilder()` ger ett lika record, varje `withX` ändrar
   bara sin komponent, och en kompakt konstruktor som kastar körs både via `build()` och via
   `withX`.
4. Ett test gör samma sak för ett generiskt record med en begränsad typparameter, ett
   nästlat record, ett record med primitiva, array- och varargs-komponenter och ett record
   vars komponenttyp kräver en import (t.ex. `java.time.LocalDate`, och en `import module`),
   och visar att allt kompilerar med `-Xlint:all -Werror` och fungerar.
5. Ett test visar att samma källa ger byte för byte samma resultat två gånger och att
   resultatet börjar med rubriken som anger källfil, record och kommandot för att generera
   om.
6. Ett test för varje felfall i kraven (saknad fil, fil som inte parsar, saknat record,
   inte ett record, privat record, record utan komponenter, namnkrock) visar att
   meddelandet namnger filen och recordet.
7. Kontroll i bygget: när en komponent läggs till i eller döps om i ett record med
   genererad fil utan att filen genereras om, eller när den genererade filen redigeras för
   hand, avslutas `java Build.java` med exitkod skild från 0 och skriver ut den genererade
   filens sökväg och kommandot `java Build.java codegen` (verifieras för hand eller med
   ett test mot kontrollens API; byggaren beskriver i `build.md` hur kritikerna kan prova).
8. `java Build.java codegen` utan argument på ett rent träd ändrar inga filer
   (`git status` är tom efteråt).
9. Minst två records i `spola.demo` implementerar sina genererade `<Record>With`-gränssnitt,
   och koden i `spola.demo` använder minst en builder och minst en with-metod.
10. Guardrails är grön för de genererade filerna (inga annoteringar), och ingen fil i
    modulens huvudkod eller genererad kod använder `java.lang.reflect`, `java.lang.invoke`
    eller preview-API:er.
11. `modules/codegen/README.md` finns med arbetsflödet och Lombok-jämförelsen enligt
    kraven, och `modules/util/README.md` länkar dit.
12. Inga annoteringar utöver tillåtelselistan, inga beroenden utöver JDK:n; ingen regel i
    `framework/REGLER.md` är bruten.
13. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade. ARCHITECTURE.md har besluten ovan
    (opt-in via `implements` och sealed gränssnitt, källparsning, rubriken som igenkänning,
    kontrollen i varje bygge och dess ordning mot kompileringen, varför inte reflektion
    eller en annotation processor) och rad 05 i `PROGRESS.md` står som klar.

## Utanför scope
- Builders och with-metoder för vanliga klasser (bara records).
- Lomboks `@Singular`, `@Builder.Default`, obligatoriska fält i buildern och
  stegvisa ("staged") builders. Standardvärden fås med en mall:
  `DEFAULTS.toBuilder()...build()`.
- Annan kodgenerering (JSON-mappning, DSL för frågor osv.). De kommer i egna uppdrag och kan
  återanvända `spola.codegen` om det passar.
- Records i standardpaketet, lokala records och records i `test/`-kataloger utan paket.
- Integration med IDE:er eller filbevakning.

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Ingen annotation processor (den skulle kräva en markering, och
annoteringar är förbjudna). Ingen reflektion i modulens huvudkod eller i genererad kod. Inga
nya filtyper i `framework/`; utöka inte `Allowlist.DOCUMENT_EXTENSIONS`.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
4
