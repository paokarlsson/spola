# Spec: Util del 2 — builders och with-metoder för records (kodgenerering)

## Uppgift
Bygg modulen `spola.codegen` som läser ett records källkod (Compiler Tree API, bara
parsning) och genererar en incheckad `<Record>With.java` med ett sealed gränssnitt
(`withX`, `toBuilder()`, `builder()` + nästlad `Builder`). Bygget genererar om varje
genererad fil i minnet vid varje körning och blir rött om den är inaktuell eller
handredigerad. `java Build.java codegen [...]` genererar. Minst två records i
`spola.demo` använder stödet. Fullständig beskrivning, designbeslut och krav:
`mission.md` i denna katalog (källa: `uppdrag/05b-util-builders.md`). Designbesluten
och kraven i `mission.md` är bindande.

## Grundregler
Allt i `framework/REGLER.md` gäller. Ett brott är en BLOCKER.

## Arbetsprodukt
`framework/`

## Verifieringskommando
`java Build.java` i `framework/` (exitkod 0).

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0, och `spola.codegen` med sin
   testingång står i `MODULES` i `Build.java`.
2. `module-info.java` för `spola.codegen` har inga `requires` på andra Spola-moduler (bara
   JDK-moduler; `spola.test` endast för tester), och ingen genererad fil importerar något
   från `spola.*` utom sitt eget paket (kontrollerbart med sökning).
3. Ett test genererar källkod för ett vanligt record, kompilerar den tillsammans med
   recordet i minnet (`javax.tools`) och kör den: `builder()` med alla sättare, osatta
   komponenter ger Javas standardvärden, `toBuilder()` ger ett lika record, varje `withX`
   ändrar bara sin komponent, och en kompakt konstruktor som kastar körs både via
   `build()` och via `withX`.
4. Ett test gör samma sak för ett generiskt record med en begränsad typparameter, ett
   nästlat record, ett record med primitiva, array- och varargs-komponenter och ett record
   vars komponenttyp kräver en import (t.ex. `java.time.LocalDate`, och en `import module`),
   och visar att allt kompilerar med `-Xlint:all -Werror` och fungerar.
5. Ett test visar att samma källa ger byte för byte samma resultat två gånger (radslut
   `\n`) och att resultatet börjar med rubriken som anger källfil, record och kommandot för
   att generera om.
6. Ett test för varje felfall i kraven (saknad fil, fil som inte parsar, saknat record,
   inte ett record, privat record, record utan komponenter, namnkrock med
   `builder`/`toBuilder`/`build` eller krockande `withX`) visar att meddelandet namnger
   filen och recordet. `Build.java` skriver dessa fel utan stackspår.
7. Kontroll i bygget: när en komponent läggs till i eller döps om i ett record med
   genererad fil utan att filen genereras om, eller när den genererade filen redigeras för
   hand, avslutas `java Build.java` med exitkod skild från 0 och skriver ut den genererade
   filens sökväg och kommandot `java Build.java codegen`. Detta gäller även när den
   inaktuella filen inte längre kompilerar (kontrollen körs före, eller i stället för,
   javac-felet). Byggaren beskriver i `build.md` hur kritikerna kan prova.
8. `java Build.java codegen` utan argument på ett rent träd ändrar inga filer
   (`git status` är tom efteråt).
9. Minst två records i `spola.demo` implementerar sina genererade `<Record>With`-gränssnitt,
   och koden i `spola.demo` använder minst en builder och minst en with-metod.
10. Guardrails är grön för de genererade filerna (inga annoteringar), och ingen fil i
    modulens huvudkod eller genererad kod använder `java.lang.reflect`, `java.lang.invoke`
    eller preview-API:er.
11. `modules/codegen/README.md` finns med arbetsflödet (generera, implementera, ändra
    recordet, generera om) och genererad kod bredvid motsvarande Lombok-kod (`@Builder`,
    `@Builder(toBuilder = true)`, `@With`), och `modules/util/README.md` länkar dit.
12. Inga annoteringar utöver tillåtelselistan, inga beroenden utöver JDK:n; ingen regel i
    `framework/REGLER.md` är bruten.
13. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade. ARCHITECTURE.md har besluten
    (opt-in via `implements` och sealed gränssnitt, källparsning, rubriken som igenkänning,
    kontrollen i varje bygge och dess ordning mot kompileringen, varför inte reflektion
    eller en annotation processor) och rad 05 i `PROGRESS.md` står som klar.

Övriga krav i `mission.md` (åtkomst på gränssnittet följer recordet, generiska records
utan varningar, varargs som array, samma imports som källfilen, Javadoc enligt
`-Xdoclint:all/protected`, deterministisk utdata, konsolrad när recordet ännu inte
implementerar gränssnittet) gäller också fullt ut.

## Utanför scope
- Builders/with-metoder för vanliga klasser.
- `@Singular`, `@Builder.Default`, obligatoriska fält, stegvisa builders.
- Annan kodgenerering; IDE-integration och filbevakning.
- Records i standardpaketet, lokala records, records i `test/`-kataloger utan paket.

## Antaganden
- Generatorn körs explicit; inget genereras automatiskt i ett vanligt bygge (bara kontroll).
- Rubrikkommentaren är enda igenkänningen av genererade filer.
- Inga nya filtyper i `framework/`; `Allowlist.DOCUMENT_EXTENSIONS` utökas inte.
- Ingen annotation processor, ingen reflektion, inga preview-funktioner.
