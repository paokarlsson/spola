Source: uppdrag/05a-util-loggning-och-hjalpare.md
# Util del 1, avslut: undantag från backend får inte slippa ut ur `Log`

## Mål
Gör klart uppdrag 05a (`uppdrag/05a-util-loggning-och-hjalpare.md`). Iterationen
`20261002-0751-util-loggning-och-hjalpare` levererade hela `spola.util` (alla 13
acceptanskriterier bedömdes uppfyllda) men stannade (STALLED) på ett kvarvarande MAJOR-fynd.
Det här steget rättar det fyndet och de tre MINOR-fynden, och låser gränsen för garantin så
att den inte glider vidare. När steget är PASS är 05a klart och uppdragsfilen flyttas till
`uppdrag/klara/`.

Planerarens beslut: det här är ett regel 2-steg (känt problem som blockerar). Det skriver
inte om 05a, så `Source:` pekar på 05a-filen. Godkänt här betyder att 05a är levererat.

## Bakgrund / kontext
Läs `framework/REGLER.md`, `framework/PROGRESS.md` (avsnittet "Uppdrag 05a" och "Kända
problem" för util), `framework/ARCHITECTURE.md` ("Util del 1") och
`gauntlet/runs/20261002-0751-util-loggning-och-hjalpare/report.md` samt `round-3/findings.md`.

Läget i dag:
- `LineFormatter`/`LineHandler` (installeras av `Logging.configure`) fångar redan ett
  `Throwable` från ett undantags `toString`/`getMessage`/`printStackTrace` och loggar en
  markering. Det gäller bara när `Logging.configure` har anropats.
- `Log.emit` (ca rad 586–593) och supplier-varianten `log(Level, Supplier<String>, Throwable)`
  (ca rad 605–609) skickar ett avslutande `Throwable` direkt till
  `logger.log(level, text, thrown)`. Med JDK:ns standardbackend (JUL `ConsoleHandler` +
  `SimpleFormatter`, som inte får röras som bieffekt) eller en egen `System.Logger` via
  `Log.of` slipper `AssertionError` (när `e.toString()` kastar) och `StackOverflowError`
  (rekursiv `getMessage`) ut ur loggningsanropet. Domaren reproducerade det mot den byggda
  jar-filen.
- Regeln "kasta vidare fatala `VirtualMachineError` utom `StackOverflowError`" står i dag på
  två ställen (`LineHandler` rad ~37 och `MessageFormatter.marker` rad ~106).

## Krav
- **Garantin (beslut, fattas här):** ett anrop till en loggmetod i `Log` (alla nivåer, med
  och utan platshållare, med och utan avslutande `Throwable`, lata varianter) kastar aldrig
  på grund av
  1. argumentens eller det avslutande undantagets `toString`/`getMessage`/
     `getLocalizedMessage`/`printStackTrace`/`getCause` m.m., eller
  2. att backendens `System.Logger.log(...)` kastar, oavsett orsak,
  utom för fatala `VirtualMachineError` som inte är `StackOverflowError` (t.ex.
  `OutOfMemoryError`), som kastas vidare. Garantin gäller oberoende av backend: med och utan
  `Logging.configure`, och med en egen `System.Logger` via `Log.of`.
- **Utanför garantin (beslut, dokumenteras):** om backendens `isLoggable(level)` kastar
  släpps undantaget igenom; `Log` gissar inte nivån. En `Supplier<String>`/`Log.defer` som
  användaren skickar in och som själv kastar behandlas som i dag (beteendet dokumenteras i
  Javadoc, ändra det inte i det här steget om det inte krävs för garantin ovan).
- **Fallback när backendens `log(level, text, thrown)` kastar:** logga en gång till utan
  undantaget: originaltexten följd av en markering som innehåller det avslutande undantagets
  klassnamn och klassnamnet på felet som uppstod (samma markeringsstil som
  `MessageFormatter.marker`). Kastar även det andra anropet sväljs felet (förutom fatala
  `VirtualMachineError` utom SOE). Samma sak när `log(level, text)` utan undantag kastar:
  försök inte igen, svälj (förutom fatala). Ingen rekursion, inga oändliga försök.
- **En enda regel för fatala fel:** en delad paketprivat hjälpare (t.ex.
  `Failures.rethrowIfFatal(Throwable)`) används av `Log`, `LineHandler`, `LineFormatter` och
  `MessageFormatter`. Regeln skrivs inte ut på något annat ställe.
- **MINOR från runda 3:**
  - MNT-2: kommentar på `RuntimeException`-inslagningen i `LineHandler.publish` som förklarar
    att den bara finns för `reportError`s signatur.
  - MNT-3: `LineFormatter.stackTrace` tilldelar inte om `text` i ett catch-block; en
    hjälpare returnerar antingen stackspåret eller reservraden.
- Javadoc i `Log` (klassnivå) beskriver garantin och gränsen exakt som ovan.
- Inget annat i `spola.util` ändras i sak. Alla befintliga 70 tester ska fortsatt gå gröna.

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0.
2. Ett test med en injicerad `System.Logger` (via `Log.of`) vars `log(level, msg, thrown)`
   anropar `thrown.toString()` och `thrown.printStackTrace(...)` visar för (a) ett undantag
   vars `toString` kastar `AssertionError` och (b) ett undantag med rekursiv `getMessage`
   (ger `StackOverflowError`) att inget slipper ut ur `log.warn("x", e)` och att en post med
   originaltexten och markeringen (med båda klassnamnen) loggas.
3. Ett test med en injicerad `System.Logger` vars `log`-metoder alltid kastar
   (`RuntimeException` respektive `AssertionError`) visar att `log.info("x")`,
   `log.error("x {}", arg)` och `log.error("x", e)` inte kastar, och att anropet görs högst
   två gånger per loggning (räknare).
4. Ett test visar att ett `OutOfMemoryError` som backenden kastar (simulerat med
   `throw new OutOfMemoryError("test")`) kastas vidare ur `Log`.
5. Ett test utan `Logging.configure` (JDK:ns standardbackend, med `System.err` omdirigerad
   till en `ByteArrayOutputStream` under testet och återställd efteråt, eller motsvarande
   isolering som inte lämnar kvar global konfiguration) visar att fall (a) och (b) i
   kriterium 2 inte kastar ur `log.warn("x", e)`.
6. Regeln om fatala fel finns på exakt ett ställe i huvudkoden (kontrollerbart med sökning
   efter `VirtualMachineError` i `modules/util/src/`: bara hjälparen och Javadoc).
7. MNT-2 och MNT-3 är åtgärdade enligt kraven.
8. Javadoc i `Log` beskriver garantin och undantaget för `isLoggable`.
9. Alla acceptanskriterier i `uppdrag/05a-util-loggning-och-hjalpare.md` gäller fortfarande
   (inga befintliga tester borttagna eller försvagade).
10. Inga annoteringar utöver tillåtelselistan, inga beroenden utöver JDK:n, ingen
    `java.lang.reflect`/`java.lang.invoke` i huvudkoden, inga resursfiler (guardrails grön);
    ingen regel i `framework/REGLER.md` är bruten.
11. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade: garantin och dess gräns
    (`isLoggable`) står under "Util del 1", och fyndet står inte kvar som känt problem.

## Utanför scope
- Ny funktionalitet i `spola.util`, builders och with-metoder (uppdrag 05b).
- Skydd mot backends som blockerar, är långsamma eller tappar poster.
- `isLoggable` som kastar (dokumenteras, åtgärdas inte).
- Att ändra JDK:ns standardkonfiguration av JUL som bieffekt.
- Ett nytt test för `Lazy` utan `System.gc()` (AUD-1 från runda 2, ingen åtgärd krävs).

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Ingen reflektion i huvudkoden. Inga nya filtyper i `framework/`.
Håll ändringen liten: bara `Log`, de tre formaterings-/handlerklasserna, den nya hjälparen,
tester och dokumentation.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
2
