Source: uppdrag/05a-util-loggning-och-hjalpare.md

# Util del 1: loggning och Lombok-hjälpare

## Mål
En fristående modul `spola.util` som ersätter de delar av Lombok som inte gäller builders:
`@Slf4j`/`@Log` (loggning ovanpå `System.Logger`), `@Getter(lazy = true)` (lat, trådsäker
memoisering), `@SneakyThrows` (checked exceptions i lambdas) och `@ToString.Exclude`
(`toString` som döljer hemliga fält). Dessutom en enradig konsolformatering och nivåer per
loggernamn, som motsvarar Spring Boots standardloggning och `logging.level.*`. De följande
modulerna (http-server, web, data) ska kunna logga genom den här modulen.

## Bakgrund / kontext
Uppdrag 01 (bygge, guardrails), 02 (testkörare, `spola.test`), 03 (DI-container,
`spola.di`) och 04 (konfiguration, `spola.config`) är klara. Läs `framework/REGLER.md`,
`framework/PROGRESS.md` och `framework/ARCHITECTURE.md` innan du börjar. Alla tester skrivs
med `spola.test`. Varje modul och dess testingång läggs till explicit i `MODULES` i
`Build.java`.

**Uppdelning av rad 05 i `PLAN.md` (beslut av planeraren):** rad 05 (util) delas i två
uppdrag. Detta uppdrag (05a) gäller loggning och hjälpare. Builders och with-metoder för
records kommer i **05b (util-builders)**, eftersom de kräver ett eget designbeslut (utan
annoteringar och utan runtime-reflektion återstår i praktiken genererad, incheckad källkod)
och är för stora för att rymmas här. Skriv uppdelningen i `PROGRESS.md` under "Återstår".

Records täcker redan `@Value`, `@Data`, `@EqualsAndHashCode`, `@Getter`,
`@AllArgsConstructor` och `@RequiredArgsConstructor` för oföränderliga typer; det ska stå i
README (se krav nedan) men kräver ingen kod. `Objects.requireNonNull` täcker `@NonNull`.

Observera från `PROGRESS.md`: guardrails tillåter bara `.java` och `.md` i `framework/`.
Checka inte in `logging.properties` eller andra resursfiler; konfiguration av loggning sker i
kod. Utöka inte `Allowlist.DOCUMENT_EXTENSIONS`.

## Krav
- Ny modul `spola.util` under `framework/modules/util/`, utan `requires` på andra
  ramverksmoduler (`spola.test` endast för tester). Får bero på JDK-moduler, t.ex.
  `java.logging` för konsolformateringen.
- **Loggning (`Log`):**
  - Skapas explicit: `Log.forClass(Order.class)` (loggernamn = klassens fullständiga namn,
    via `System.getLogger`) och `Log.of(System.Logger)` (för att injicera en egen logger i
    tester). Ingen stackinspektion eller anropar-detektering för att hitta klassen.
  - Nivåerna `trace`, `debug`, `info`, `warn`, `error` med motsvarande `isXxxEnabled()`.
    Mappning till `System.Logger.Level` (TRACE, DEBUG, INFO, WARNING, ERROR) dokumenteras.
  - Platshållare `{}` som i SLF4J: argument ersätter platshållarna i ordning; för få
    argument lämnar resterande `{}` orörda; ett överskott av argument ignoreras, **utom**
    att ett sista argument som är en `Throwable` och inte har förbrukats av en platshållare
    loggas som undantag (med stackspår). `\{}` skriver ett bokstavligt `{}`. `null` skrivs
    som `null`. Arrayer skrivs med `Arrays.deepToString`. Ett argument vars `toString`
    kastar ger inte undantag ut ur loggningen; i stället skrivs en markering med
    undantagets klassnamn. Exakta regler i Javadoc.
  - **Inget arbete när nivån är avstängd:** ingen formatering och inga `toString`-anrop på
    argumenten om nivån är avstängd. Lata varianter med `Supplier<String>` (och
    `Supplier<?>` per argument eller motsvarande) anropar inte leverantören när nivån är
    avstängd.
  - Trådsäker; en `Log` kan ligga i ett `static final`-fält och delas mellan virtuella
    trådar.
- **Konsolformatering och nivåer (`Logging`, beslut dokumenteras):** ett explicit anrop,
  t.ex. `Logging.configure(LoggingSettings)` eller motsvarande, som
  - installerar en enradig formatering på `java.util.logging`-rotloggern (tidsstämpel i
    ISO-8601 med millisekunder, nivå, trådnamn, loggernamn, meddelande; stackspår på
    följande rader),
  - sätter nivå per loggernamnsprefix (`Map<String, Level>`, t.ex. `"com.example" ->
    DEBUG`, `"" -> INFO` för roten), där längsta matchande prefix vinner och prefix matchar
    hela namnsegment (`com.ex` matchar inte `com.example`),
  - kan anropas igen och ersätter då föregående inställning helt (inga dubbla handlers),
  - kan skriva till en given `PrintStream`/`OutputStream` (för tester) eller `System.err`.
  Ingenting konfigureras som bieffekt av att klassen laddas eller att `Log` används. Att
  `spola.util` inte läser `spola.config` är avsiktligt; README visar hur nivåerna läses med
  `spola.config` (`logging.level.*`) och skickas in.
- **`Lazy<T>`:** `Lazy.of(Supplier<T>)` som räknar ut värdet högst en gång även när många
  (virtuella) trådar anropar `get()` samtidigt. Beslut om undantag (fattas här): ett
  undantag från leverantören memoreras **inte**; nästa `get()` försöker igen. `null` från
  leverantören är ett fel (`NullPointerException` med tydligt meddelande). Leverantören
  släpps (referensen nollas) efter lyckad uträkning. `StableValue` är preview i JDK 25 och
  får inte synas i publikt API.
- **`Unchecked`:** funktionella gränssnitt som får kasta (`ThrowingSupplier`,
  `ThrowingFunction`, `ThrowingConsumer`, `ThrowingRunnable`) och adaptrar till
  `java.util.function` (`Unchecked.function(...)` osv.) samt `Unchecked.run`/`get` för
  direkta anrop. Beslut (fattas här, dokumenteras): ingen "sneaky throw" (att kasta ett
  checked exception utan att deklarera det); i stället slås det in: `IOException` →
  `UncheckedIOException`, `InterruptedException` → trådens avbrottsflagga sätts igen och
  undantaget slås in, övriga checked → `spola.util.UncheckedException` (eller motsvarande
  namn) med originalet som `cause`. `RuntimeException` och `Error` släpps igenom orörda.
- **`ToString`:** en liten byggare för `toString` i records och klasser som innehåller
  hemligheter: `ToString.of("User").add("name", name).secret("password", password)` ger
  `User[name=ada, password=***]` (samma form som en records `toString`). `secret` visar
  aldrig värdet, inte heller dess längd; `null` visas som `null` även för `secret`
  (beslut får ändras men ska dokumenteras).
- Ingen användning av `java.lang.reflect` eller `java.lang.invoke` i modulens huvudkod.
- `README.md` i modulen med Lombok/Spring-jämförelse.

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0, och `spola.util` med sin
   testingång står i `MODULES` i `Build.java`.
2. Modulens huvudkod importerar inget från `java.lang.reflect` eller `java.lang.invoke`,
   har inga `requires` på andra ramverksmoduler och använder inga preview-API:er i publikt
   API (kontrollerbart med sökning i källkoden och `module-info.java`).
3. Ett test med en injicerad `System.Logger` visar platshållarreglerna: ersättning i
   ordning, för få argument, för många argument, sista argumentet som `Throwable` (loggas
   som undantag och skrivs inte i texten), `Throwable` som förbrukas av en platshållare,
   `\{}`, `null`, en array och ett argument vars `toString` kastar.
4. Ett test visar att inga `toString`-anrop görs och ingen leverantör anropas när nivån är
   avstängd (räknare i testobjekt), och att de görs när nivån är på.
5. Ett test visar nivåmappningen för alla fem nivåer mot `System.Logger.Level`.
6. Ett test visar konsolformateringen mot en `ByteArrayOutputStream`: en rad per
   meddelande i angiven form (tidsstämpel matchas med ett mönster), stackspår på följande
   rader, nivå per prefix där längsta prefix vinner, att `com.ex` inte matchar
   `com.example`, och att ett andra `configure`-anrop inte ger dubbla rader.
7. Ett test visar att `Lazy` räknar ut värdet exakt en gång när 1 000 virtuella trådar
   anropar `get()` samtidigt, att ett undantag inte memoreras (andra anropet lyckas), och
   att `null` från leverantören ger `NullPointerException`.
8. Ett test visar `Unchecked` för alla fyra gränssnitten: `IOException` blir
   `UncheckedIOException`, `InterruptedException` sätter avbrottsflaggan igen, ett annat
   checked exception slås in med originalet som `cause`, och `RuntimeException`/`Error`
   släpps igenom som samma instans.
9. Ett test visar att `ToString` ger samma form som en records `toString` för vanliga fält
   och att ett hemligt värde inte förekommer i resultatet.
10. `README.md` i modulen visar varje hjälpare bredvid motsvarande Lombok-kod (`@Slf4j`,
    `@Getter(lazy = true)`, `@SneakyThrows`, `@ToString.Exclude`), förklarar vilka
    Lombok-annoteringar records ersätter, och visar hur `logging.level.*` läses med
    `spola.config` och skickas till `Logging`.
11. Inga resursfiler (t.ex. `logging.properties`) är incheckade under `framework/`, och
    `Allowlist.DOCUMENT_EXTENSIONS` är oförändrad.
12. Inga annoteringar utöver tillåtelselistan, inga beroenden utöver JDK:n (guardrails
    grön); ingen regel i `framework/REGLER.md` är bruten.
13. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade, inklusive besluten om
    platshållarregler, `Lazy` och undantag, `Unchecked` utan sneaky throw, hur
    konsolformateringen installeras, och uppdelningen av rad 05 i 05a/05b.

## Utanför scope
- Builders, with-metoder och annan kodgenerering för records (uppdrag 05b).
- Strukturerad loggning (JSON-format), MDC/kontext per anrop och metrics (uppdrag 18).
- Filrotation, asynkrona appenders och loggning till nätverk.
- Bryggor till SLF4J/Log4j (tredjepartskod är förbjuden).
- `@Delegate`, `@Cleanup` (try-with-resources räcker), `@Synchronized`.

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Ingen reflektion i modulens huvudkod. Inga nya filtyper i
`framework/`. Inga preview-funktioner i publikt API.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
4
