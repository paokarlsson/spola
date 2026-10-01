Source: uppdrag/01-bygge-och-guardrails.md
# Bygge och guardrails

## Mål
Ett byggsystem som bara kräver JDK 25, plus en guardrails-modul som automatiskt stoppar
bygget om någon av grundreglerna bryts. Allt som byggs i senare uppdrag vilar på detta,
så reglerna måste vara maskinellt verifierade från första början istället för att lita på
att varje uppdrag kommer ihåg dem.

## Bakgrund / kontext
`framework/` innehåller bara `REGLER.md`. Läs den först; den gäller för detta och alla
kommande uppdrag. Inget annat finns ännu. Nästa uppdrag bygger en egen testkörare, så här
finns ännu ingen testinfrastruktur.

## Krav
- `framework/Build.java` körs med `java Build.java` (source launcher, ingen förkompilering).
- Bygget kompilerar alla moduler under `framework/modules/<modul>/` via
  `javax.tools.ToolProvider`, i beroendeordning härledd från `module-info.java`.
- Vilka moduler som finns och deras sökvägar listas explicit i Java-kod i bygget, inte
  genom att scanna kataloger.
- Bygget paketerar en jar per modul i `framework/out/` med hjälp av JDK:ns jar-verktyg
  (`java.util.spi.ToolProvider.findFirst("jar")`).
- Modulen `guardrails` innehåller tre kontroller som körs i varje build, efter kompilering:
  1. **Källkod:** parsar och attribuerar alla `.java`-filer med Compiler Tree API
     (`com.sun.source.util.JavacTask`, `Trees`) och rapporterar varje annotering vars fullt
     kvalificerade namn inte finns i tillåtelselistan i `REGLER.md`, samt varje
     annoteringsdeklaration.
  2. **Bytekod:** läser alla `.class`-filer med Class-File API (`java.lang.classfile`) och
     rapporterar varje annoteringsdescriptor utanför tillåtelselistan, inklusive
     parameter- och typannoteringar.
  3. **Beroenden:** läser alla `module-info.class` och rapporterar varje `requires` som inte
     är en JDK-modul eller en av projektets egna moduler. Rapporterar varje `.jar`-fil i
     `framework/` utanför `framework/out/`, och varje `pom.xml`, `build.gradle` eller
     `build.gradle.kts`.
- Varje överträdelse rapporteras med fil, rad (för källkod) och vilken regel som bröts.
- Guardrails självtestas: under `framework/modules/guardrails/fixtures/` finns minst ett
  medvetet felaktigt exempel per regel. Bygget kör guardrails mot varje fixture och
  misslyckas om någon fixture *inte* ger förväntad överträdelse. Fixtures kompileras inte
  in i några artefakter och undantas från huvudkontrollen.
- `Build.java` avslutar med exitkod 0 vid framgång och icke-noll vid fel.
- `framework/PROGRESS.md` och `framework/ARCHITECTURE.md` skapas.

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0.
2. Repot innehåller inga filer från Maven, Gradle eller andra byggverktyg, och inga
   `.jar`-filer utanför `framework/out/`.
3. Att lägga till `@Deprecated` på en metod i valfri modul ger fortfarande grönt bygge.
4. Att lägga till en egen `@interface` i valfri modul ger rött bygge med fil och rad i felet.
5. Att lägga till `requires java.desktop;` är tillåtet; att lägga till `requires foo.bar;`
   (som inte är en projektmodul) ger rött bygge.
6. Att placera en tom fil `framework/lib/x.jar` ger rött bygge.
7. Fixtures finns för: otillåten annotering i källkod, annoteringsdeklaration, otillåten
   annotering i bytekod, otillåtet `requires`, främmande jar-fil, byggfil från externt
   verktyg. Bygget verifierar varje fixture.
8. Att ta bort en fixtures förväntade överträdelse (t.ex. ändra den till giltig kod) ger
   rött bygge.
9. Inga andra paket än JDK:ns importeras någonstans.
10. `PROGRESS.md` och `ARCHITECTURE.md` finns och beskriver det som byggts.

## Utanför scope
- Testkörare (nästa uppdrag).
- Inkrementella byggen, cachning, parallell kompilering.
- Publicering av artefakter.

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Inga undantag.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
5
