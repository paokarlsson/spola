# Spola – läget

## Klart

### Uppdrag 01: bygge och guardrails
- `Build.java`: `java Build.java` i `framework/` läser den explicita modullistan, ordnar
  modulerna efter `requires` i `module-info.java`, kompilerar med `javax.tools`
  (`-Xlint:all,-deprecation,-removal -Xdoclint:all/protected -Werror`), packar `out/<modul>.jar` med
  JDK:ns jar-verktyg och kör guardrails från den byggda jar-filen. Exitkod 0/1.
- Modulen `spola.guardrails` (`modules/guardrails/`) med fyra kontroller i varje bygge:
  källkod (Compiler Tree API med attribuering), bytekod (Class-File API inklusive
  parameter- och typannoteringar, class-filer igenkända på namn och innehåll), beroenden
  (`module-info.class`), filer (jar-arkiv utanför `out/`, igenkända på namn och på innehåll,
  byggfiler från Maven/Gradle/Bazel/Buck/Pants/Mill/Make m.fl. och symboliska länkar).
- Utöver uppdragets tre kontroller: importer från annat än JDK/projekt och läsning av
  annoteringar via reflektion (REGLER §1 och §2).
- Filer utan filändelsen `.java` (utanför `out/` och fixture-katalogerna) kontrolleras
  fail-closed av `LauncherCheck`, eftersom source launchern och jshell kör filer med vilket
  namn som helst: bara dokument (`.md`) är tillåtna, varje annan fil ger
  `UNCHECKABLE_SOURCE`. Ett dokument läses på alla sätt launchern kan läsa det (varje
  teckenkodning i JDK:n, `#!`-rad avslutad av `\r`, `\n` eller `\r\n`, varje release javac
  tar för `--source`, i JDK 25 alltså 25 ned till 8). Parsar någon läsning rapporteras
  dokumentet som `UNCHECKABLE_SOURCE`; dokument analyseras aldrig som Java.
- Filkontrollen är fail-closed för zip: en fil som börjar som en zip men som `ZipFile` inte
  kan öppna (skadad, okänd komprimering) räknas som jar.
- Självtest (sedan uppdrag 02 tester i `modules/guardrails/test/`, körda av `spola.test`)
  mot 10 fixtures med exakt angivna förväntade överträdelser, plus kontroll att
  tillåtelselistan i koden stämmer med tabellen i `REGLER.md`, att varje fil under
  `fixtures/` används av sin fixture, och att huvudkontrollen bara undantar de listade
  fixture-katalogerna (inte hela `fixtures/`).
- Bygget tar ungefär 3–4 s (dokumentkontrollen står för ungefär 1 s).

### Uppdrag 02: testkörare
- Modulen `spola.test` (`modules/test/`): tester som värden (`test`, `suite`,
  `parameterized`), nästlade sviter, fixtures före/efter per test och per svit med
  garanterad städning, timeout per test (ärvs från sviter, standard 60 s), hoppa över
  (`skip`), assertions för likhet, sanningsvärde, null, undantag och samlingar med förväntat
  och faktiskt värde i felmeddelandet, rapport till stdout (rad per test, stackspår,
  sammanfattning med antal och tid). Semantiken står i ARCHITECTURE.md ("Tester").
- Varje modul har testkällor i `modules/<m>/test/` (modulens paket, ingen
  `module-info.java`) och en explicit testingång i `Build.java` (`TestEntry(klass, metod)`).
  Bygget kompilerar modulen med testerna till `out/test-classes/`, kör guardrails (som också
  kontrollerar testkällor och testbytekod) och kör sedan alla modulers tester; ett
  fallerande test ger rött bygge. Sist kontrollerar bygget sin egen exit-vakt i två
  barn-JVM:ar (`java Build.java --exit-guard-probe ...`): efter beslutet får andra shutdown
  hooks och `deleteOnExit` gå klart, före beslutet ger `System.exit(0)` rött.
- Guardrails självtest är flyttat till `GuardrailsTests` (ett test per kontroll, ett
  parametriserat fall per fixture) och körs av `spola.test`; `GuardrailsTool selftest` är
  borttaget. Guardrails `Project` har fått testkällor (`tests`, `testReads`) och
  `GuardrailsTool check` flaggorna `--tests` och `--test-reads`.
- Körarens egna tester: 77 tester (assertions, fixtures, parametrisering, timeout, nästlade
  sviter, rapport, `TestTool`, inklusive `System.exit` i ett test, i ingångsmetoden och via
  `PrintWriter`-överlagringen, i barn-JVM:ar).
  Guardrails: 17 tester.
- Bygget tar ungefär 9 s (testerna ungefär 2 s, varav timeout-testerna 1,7 s; exit-vaktens
  två barn-JVM:ar ungefär 2–3 s).

### Uppdrag 03: DI-kärna
- Modulen `spola.di` (`modules/di/`, inga `requires`): typsäkra nycklar (`Key<T>`) med
  kvalificerare som vanliga värden, moduler (`ContainerModule`, `Binder.install`), bindningar
  med typad form (`to(A.class, B.class, Service::new)`, 0–5 beroenden) och resolverform
  (`to(Dependencies.of(...), r -> ...)`), `toInstance`.
- Scopes: lat och ivrig singleton, prototype, anropsscope på `ScopedValue`
  (`runInCallScope`/`callInCallScope`).
- `Container.create` validerar hela grafen innan något skapas: dubbla bindningar, bindningar
  utan mål, saknade beroenden (vem saknar vad, var bindningen gjordes), cykler med hela
  kedjan, singletons som fångar anropsscopade komponenter. Alla fel i en `WiringException`.
- Livscykel: `AutoCloseable`-singletons stängs i omvänd skapandeordning, anropsscopade när
  scopet slutar; fel samlas i `CloseException` och stoppar inte övriga.
- Ingen `java.lang.reflect`/`java.lang.invoke` i huvudkoden. `README.md` i modulen visar
  exempel bredvid Spring (`@Component`, `@Autowired`, `@Qualifier`).
- 48 tester (nycklar, bindningsformer, kvalificerare, moduler, validering, scopes, 1 000
  virtuella trådar mot en lat singleton, livscykel). Bygget tar ungefär 10 s.

### Uppdrag 04: konfiguration
- Modulen `spola.config` (`modules/config/`, inga `requires`): källor som värden
  (`Source.map`, `file`, `optionalFile`, `environment(prefix)`, `environmentTable(tabell)`,
  `systemProperties`, `arguments`), sammanslagna i den ordning de läggs till (senare vinner).
  Miljön kan injiceras som `Map`.
- Profiler: `activeProfiles(...)` i koden eller `activeProfilesFrom(nyckel)`;
  `addForEachProfile(profil -> Source)` lägger profilkällorna där anropet står.
- Platshållare `${a}`, `${a:standard}`, bokstavligt `$${`, cykler med hela kedjan.
- Bindning till records med `config.bind(prefix, c -> new Rec(...))` utan reflektion:
  `String`, `int`, `long`, `boolean`, `double`, `Duration`, `URI`, `Path`, enum, listor,
  `Optional`, egna `Converter`/`Function<String,T>`, nästlade records med `section`. Alla fel
  i ett `ConfigException`.
- Känsliga nycklar (lista eller predikat) maskeras i fel och i `describe()`, som visar varje
  nyckel, värde och vinnande källa. Värden byggda av känsliga värden maskeras också.
- `README.md` i modulen med Spring Boot-jämförelse och koppling till `spola.di` via
  `toInstance`. Beslut i ARCHITECTURE.md ("Konfiguration").
- 63 tester (prioritet med fyra källor och alla tolv ordnade par, miljöregler, filer,
  argument, profiler, platshållare, bindning av alla typer, felinsamling, hemligheter,
  1 000 virtuella trådar). Konfigurationsfiler i testerna skrivs till temporära kataloger.
  Bygget tar ungefär 12 s.

### Uppdrag 05a: util del 1, loggning och hjälpare
- Modulen `spola.util` (`modules/util/`, kräver bara `java.logging`, inga Spola-moduler):
  `Log` ovanpå `System.Logger` (`forClass`, `of`, fem nivåer, `{}`-platshållare som i SLF4J,
  sista `Throwable` som undantag, `\{}`, markering när `toString` kastar, lata varianter med
  `Supplier<String>` och `Log.defer`, inget arbete när nivån är av, ett loggningsanrop kastar
  aldrig på grund av dåliga värden/undantag eller en backend som kastar, oavsett backend, utom
  fatala `VirtualMachineError` utom `StackOverflowError`; gränsen är en `isLoggable` som
  kastar, som släpps igenom), `Logging.configure`
  (enradigt format på JUL-rotloggern, nivå per prefix där längsta prefix vinner, ersätter
  föregående anrop, egen utström eller `System.err`, `reset()`), `Lazy` (en gång under
  samtidighet, undantag och `null` memoreras inte, leverantören släpps), `Unchecked` med fyra
  `Throwing*`-gränssnitt (ingen sneaky throw), `ToString` med `secret`.
- Ingen `java.lang.reflect`/`java.lang.invoke` och ingen preview i huvudkoden. Inga
  resursfiler; loggning konfigureras i kod. `README.md` med Lombok- och Spring Boot-jämförelse
  och hur `logging.level.*` läses med `spola.config`. Beslut i ARCHITECTURE.md ("Util del 1").
- 70 tester i första leveransen (platshållarregler, inget arbete vid avstängd nivå för alla fem nivåer,
  nivåmappning, 1 000 virtuella trådar mot en delad `Log`, konsolformat mot
  `ByteArrayOutputStream`, radbrytningar i undantagsmeddelanden kan inte förfalska en post,
  ett undantag vars `toString`/`getMessage` kastar ett `Error` loggas ändå,
  prefixnivåer, omkonfigurering, `Lazy` med 1 000 virtuella trådar,
  `Unchecked` för alla gränssnitt, `ToString` mot records). Bygget tar ungefär 14 s.
- Avslut av 05a (körning `20261002-0826-util-log-backend-fel`): garantin ovan hålls i `Log`
  självt (`Log.deliver`: kastar backenden med undantag loggas texten en gång till utan det,
  med markeringen `[log(<undantag>) threw <fel>]`; annars sväljs felet; högst två anrop).
  Regeln för fatala fel står bara i `Failures.rethrowIfFatal`. 79 tester (nya: backend som
  skriver ut ett dåligt undantag, backend som alltid kastar med räknare, `OutOfMemoryError`
  kastas vidare, `isLoggable` som kastar släpps igenom, JDK:ns standardbackend med
  `ConsoleHandler`/`SimpleFormatter` utan `Logging.configure`).

### Uppdrag 05b: util del 2, builders och with-metoder för records (`spola.codegen`)
- Rad 05 i `PLAN.md` är därmed klar (05a loggning och hjälpare, 05b builders och with-metoder).
- Modulen `spola.codegen` (`modules/codegen/`, kräver bara `jdk.compiler`): läser ett records
  källfil med Compiler Tree API (bara parsning) och genererar `<Record>With.java` bredvid den:
  `sealed interface <Record>With permits <Record>` med samma åtkomst som recordet, en
  `withX` per komponent, `toBuilder()`, `builder()` och en nästlad `Builder`. Recordet väljer
  stödet med `implements <Record>With`. Allt går genom den kanoniska konstruktorn.
  Stöder toppnivå- och nästlade records, generiska records med gränser, primitiva, array-,
  varargs- (som array) och generiska komponenttyper, källfilens importer (även
  `import module`). Medlemstyper kvalificeras; oanvända enkla importer tas bort.
- API: `RecordCodegen.generate(källfil, record) -> String`, `GeneratedFiles.write/find/check/
  regenerate`, `CodegenTool` (`ToolProvider` `spola-codegen`). Fel (`CodegenException`)
  namnger fil och record: saknad/icke-UTF-8/oparsbar fil, saknat record, inte ett record,
  privat record, inga komponenter, standardpaketet, komponent `builder`/`toBuilder`/`build`/
  `equals`, krockande `withX`, egen `toBuilder()`/`withX` i recordet, typen `Builder`.
- `Build.java`: `spola.codegen` kompileras först och `check` körs på `src/` och `test/` i varje
  modul innan något annat kompileras; en inaktuell eller handredigerad genererad fil ger rött
  bygge med sökväg och `java Build.java codegen`, även när filen inte längre kompilerar.
  `java Build.java codegen` genererar om alla genererade filer (skriver bara ändrade);
  `java Build.java codegen FIL RECORD` genererar en.
- `spola.demo`: `OrderLine` och `Summary.Rejection` implementerar `OrderLineWith` och
  `RejectionWith` (incheckade). `OrderLine.parse` bygger med `OrderLineWith.builder()`;
  `PartsTests` använder builders och with-metoder (`withQuantity`, `withReason`, `toBuilder`).
- `modules/codegen/README.md` (arbetsflödet, genererad kod bredvid Lomboks `@Builder`,
  `@Builder(toBuilder = true)` och `@With`), länkad från `modules/util/README.md`. Beslut i
  ARCHITECTURE.md ("Util del 2").
- 41 tester i `spola.codegen` (genererad kod kompilerad i minnet med byggets flaggor och körd:
  vanligt, publikt, generiskt, nästlat, primitiva/array/varargs, importer; determinism och
  rubrik; varje felfall; kontrollen efter tillagd/omdöpt komponent, handredigering, skadad
  rubrik, fel filnamn, borttaget record; omgenerering). `spola.demo` har 43 tester. Bygget
  tar ungefär 20–35 s (beroende på maskinen).

### Uppdrag 05c: integration av kärnmodulerna (`spola.demo`)
- Modulen `spola.demo` (`modules/demo/`, kräver `spola.di`, `spola.config`, `spola.util`;
  exporterar inget, inget beror på den): en lagertjänst som läser en orderfil (`sku;antal`),
  drar av ordrarna mot lagret och skriver en sammanfattning (behandlade, avvisade med orsak,
  slutsaldo) och ett HMAC-signerat revisionsspår. Ingången `InventoryApp` (`main` och
  `run(args, miljö, ut, logg)` som returnerar exitkod) och wiring-modulen `InventoryWiring` är
  de enda klasserna som nämner `Container`, `Binder` eller `Config`.
- Konfiguration: standardvärden i koden, fil (`config.file`), profilfil, miljö
  (`INVENTORY_`), argument; `audit.secret` är känslig; bindning till `*Settings`-records som
  läggs in i containern med `toInstance`. Loggnivåer ur `logging.level.*` via
  `Logging.configure`. Sex komponenter, två `OrderCheck` med kvalificerare, `AuditTrail` är
  `AutoCloseable` och stängs av containern. Felvägar (saknad/ogiltig konfiguration,
  wiring-fel, trasig orderrad, oläslig orderfil) ger ett meddelande och en exitkod (sysexits
  65/70/74/78), utan stackspår.
- Ändringar i kärnmodulerna efter friktionsrapporten: `Section.keys()` (`spola.config`),
  `LoggingSettings.parseLevel(String)` (`spola.util`) och att `Source.file`/`optionalFile`
  hoppar över ett inledande byte order mark (`spola.config`), med tester, Javadoc och README.
- `modules/demo/README.md`: körning, Spring Boot-jämförelse och avsnittet "Friktion" (3
  **Åtgärdat**, 5 **Kvar**, och det som fungerade bra).
- Runda 2 i 05c: orderfilen tål ett byte order mark först (Excel/Notepad); en artikel med
  styr-, format- eller avgränsartecken (ESC, U+0085, U+2028, U+202E ...) är en trasig rad
  (65) i stället för att skrivas rått till sammanfattningen och revisionsspåret; `audit.file`
  som pekar på orderfilen är ett konfigurationsfel (78); ett revisionsspår som inte kan
  öppnas ger 74 och nämner `audit.file`.
- Runda 3 i 05c: ett byte order mark först i `inventory.properties` dolde tyst första
  inställningen; nu hoppar `spola.config` över det (friktionspunkt 8). En orderfil som inte
  är UTF-8 ger 65 och första raden med fel i stället för 74 och `MalformedInputException`.
- 42 tester i `spola.demo` (hela programmet genom ingången, prioritetsordningen med alla fem
  lager, hemligheten i utdata/logg/fel, stängning vid lyckad och avbruten körning, loggnivåer
  från argument/miljö/profil, alla felvägar, modulgränserna på källkoden, HMAC mot RFC 4231
  testfall 1 och 2, orderradsformatet). `spola.config` har nu 68 tester och `spola.util` 80.
  Bygget tar ungefär 20 s.

### Uppdrag 06a: JSON del 1, trädmodell, parser och skrivare (`spola.json`)
- Rad 06 i `PLAN.md` är delad i 06a (detta: trädmodell, parser, gränser, skrivare) och 06b
  (mappning mellan records och JSON); se ARCHITECTURE.md ("JSON del 1").
- Modulen `spola.json` (`modules/json/`, inga `requires`, ingen reflektion): `sealed interface
  JsonValue` med `JsonObject` (ordning bevaras, likhet utan ordning), `JsonArray`,
  `JsonString`, `JsonNumber` (texten behålls, numerisk likhet, exakta omvandlingar som kastar
  `ArithmeticException`), `JsonBoolean`, `JsonNull`; fabriker och `JsonObject.builder()`.
- `Json.parse` från `String`, `byte[]`, `InputStream` och `Reader`, med och utan
  `JsonLimits` (`maxDepth` 512, `maxStringLength` 1 048 576, `maxNumberLength` 1 000,
  `maxEntries` 100 000, `maxInputSize` 4 MiB). Strikt RFC 8259 utan utvidgningar, dubbla
  nycklar avvisas, ensamma surrogat avvisas, strikt UTF-8 (RFC 3629) med egen avkodare,
  inledande BOM hoppas över. Parsern, skrivaren, likheten och hashkoden är inte rekursiva.
  `JsonParseException` med rad, kolumn och offset; meddelandet är alltid kort, utskrivbar ASCII.
- `Json.write`/`writePretty` till `String` och `Appendable`, `writeUtf8`/`writePrettyUtf8`
  till `OutputStream`; escaping av `"`, `\`, U+0000–U+001F, U+007F, U+2028, U+2029.
- Läsare med sökväg (`string`, `number`, `bool`, `object`, `array`, `get`, `optional`) som
  kastar `JsonAccessException` (`$.items[2].sku: expected string but found number`).
- `README.md` i modulen med Jackson-jämförelse och avvikelsetabell.
- 287 tester i `spola.json`: RFC 8259 §13-exemplen, 57 accepterade och 97 avvisade dokument
  (varje med regel, genom alla fyra ingångar, rundresa genom båda skrivarna), positioner och
  säkra meddelanden (även för 1 MiB indata), varje gräns vid och över gränsen, 100 000 nästlade
  `[` på plattformstråd och virtuell tråd, oändlig ström och `Reader`, tal, dubbla nycklar,
  ordning, BOM, skrivaren tecken för tecken, läsarna, 1 000 virtuella trådar, och UTF-8-
  avkodaren mot JDK:ns avkodare (alla tvåbytessekvenser, tre- och fyrbytesstarter, alla
  kodpunkter). Bygget tar ungefär 30–45 s.

### Uppdrag 06b: JSON del 2, explicit mappning mellan Java och JSON (`spola.json.codec`)
- Nytt exporterat paket `spola.json.codec` i `spola.json` (fortfarande inga `requires`, ingen
  reflektion; trädmodellen beror inte på paketet). Se ARCHITECTURE.md ("JSON del 2").
- `JsonCodec<T>` med `encode`, `decode`, `read(String)`, `read(byte[])`, `write`,
  `writePretty` och `map(to, from)` för egna värdetyper (undantag från `to` blir problem).
- Inbyggda kodekar på `JsonCodecs`: `STRING`, `BOOLEAN`, `INT`, `LONG`, `DOUBLE`,
  `BIG_DECIMAL`, `BIG_INTEGER`, `UUID`, `INSTANT`, `LOCAL_DATE`, `LOCAL_TIME`,
  `LOCAL_DATE_TIME`, `OFFSET_DATE_TIME`, `DURATION`, `JSON`; ingen tvångsomvandling, tal
  exakt inom typens intervall, skydd mot exponent-DoS, `java.time` som ISO-8601.
- Kombinatorer: `list`, `set` (dubbletter är fel), `map`, `optional`, `nullable`, `lazy`,
  `enumOf` (med `name()` eller eget namn).
- Objekt: `JsonCodec.object(Konstruktor::new, field(...), ...)` med 0–16 fält och en allmän
  form med fälten som nycklar; `optionalField` (utelämnas när tom), `orElse`, okända
  medlemmar avvisas (`ignoringUnknown()`), dubbla namn är fel när kodeken skapas.
- Sealed hierarkier: `JsonCodec.sealed("type", x -> switch (x) {...}, VARIANTER...)` med
  `Variant`; diskriminatorn först, saknad/okänd/fel typ ger problem som listar taggarna.
- Felrapport: `JsonMappingException` med alla `JsonProblem` (sökväg + meddelande) i
  dokumentordning, högst 100 plus en sammanfattning, `getMessage()` på en rad om högst 2 000
  tecken, allt utskrivbar ASCII med värden avkortade.
- Djup: över 512 nivåer blir det ett problem i båda riktningarna, aldrig
  `StackOverflowError`.
- `README.md` i modulen utökad med mappningsavsnittet, Jackson-jämförelsen och
  avvikelsetabellen.
- 128 nya tester (415 i `spola.json`): rundresa för en graf med alla kodekar och kombinatorer
  och rekursion via `lazy`, 41 avvisade och 18 accepterade skalärfall, felinsamling på flera
  nivåer, gränsen på 100, säkra meddelanden, objektvarianterna, 16 och 22 fält, sealed
  hierarkier, `map` och `null`, 100 000 nivåer på plattformstråd, virtuell tråd och 512 KiB
  stack, 1 000 virtuella trådar, och källkodsreglerna (ingen reflektion, inget beroende från
  trädet).
- Runda 2: deklarerade fältnamn kortas aldrig i sökvägar och skrivs som i
  `JsonAccessException` (`$.name`/`["..."]`); namn från data (okända medlemmar, nycklar i
  `map`) kortas över 40 tecken. Gränserna för `JSON`-kodekens djup och för `BIG_INTEGER`/
  `BIG_DECIMAL` mot `maxNumberLength` är dokumenterade. 417 tester i `spola.json`.

### Uppdrag 07a: HTTP-server del 1, HTTP/1.1 på virtual threads (`spola.http`)
- Rad 07 i `PLAN.md` är delad i 07a (detta: HTTP/1.1 över TCP, keep-alive, pipelining,
  chunked, `Expect: 100-continue`, gränser, tidsgränser, mjukt stopp) och 07b (TLS); se
  ARCHITECTURE.md ("HTTP-server del 1").
- Modulen `spola.http` (`modules/http/`, inga `requires` på ramverksmoduler, `requires static
  java.net.http` bara för testerna, ingen reflektion, inte `jdk.httpserver`): `HttpServer`
  på `ServerSocket` med en virtuell tråd per anslutning, acceptor som plattformstråd (inte
  daemon), `Handler`, `Request` (rå target, sökväg, query, `host()`, version, `Headers`,
  lat kropp), `Response` med sealed `ResponseBody` (`Empty`, `Bytes`, `Streaming`) och
  `BodyWriter`, `Headers` (oföränderliga, ordnade, skiftlägesokänsliga, validerade),
  `HttpLimits`, `HttpTimeouts`, `HttpServerOptions`, `RequestBodyException`, `HttpVersion`.
- Strikt parsning: CRLF överallt, token, obs-fold, styrtecken, `Host`, version (505),
  target-former och RFC 3986-tecken, `Content-Length`/`Transfer-Encoding` mot smuggling,
  chunked med tillägg (rad högst 4 096 byte) och trailers. 414/431/413 vid gränserna.
- Tidsgränser: `headerTimeout` total (deadline per läsning), `idleTimeout`, `readTimeout`
  utan framsteg, `writeTimeout` via en vakttråd som stänger socketen, `shutdownTimeout`.
- `maxConnections`: acceptor väntar på en plats (Semaphore) innan `accept`; nya anslutningar
  väntar i backlog (1 024).
- Fel: 500 utan detaljer, `Error` dödar inte servern, avbruten anslutning när ett strömmande
  svar redan börjat; loggning via `System.Logger` (`spola.http`) med escapade undantag.
- Mjukt stopp: lediga anslutningar stängs, pågående får `Connection: close`, resten avbryts
  efter `shutdownTimeout`; `close()` väntar på alla anslutningstrådar (högst 1 s efter
  avbrottet) och håller ingen monitor, så en handler och en shutdown hook kan anropa den
  samtidigt. Oläst kropp läses före svaret, så en trasig kropp ger sin status i stället för
  handlerns svar.
- `README.md` i modulen med Spring Boot/Tomcat-exempel, gränstabell mot Spring Boots
  egenskaper och tabell över vad som avvisas.
- 171 tester i `spola.http` (cirka 12 s): `HttpClient` (GET, POST, strömmad POST, HEAD),
  keep-alive och pipelining, 71 felaktiga huvuden och 14 felaktiga chunked kroppar med status
  och RFC-avsnitt (kropparna med en handler som läser hela, en del av eller ingenting av kroppen), smuggelförsök, chunked åt båda hållen (byte för byte), 100-continue,
  tidsgränserna med korta värden, `maxConnections`, fel i handler och `BodyWriter`, svarets
  validering och `Date`, mjukt stopp, 1 000 samtidiga klienter, loggar och felkroppar utan
  styrtecken, och källkodsreglerna.

## Återstår (enligt `uppdrag/PLAN.md`)
- 07b (TLS ovanpå `spola.http`) och 08–20 enligt planen.
- **Kvar från friktionsrapporten i 05c** (`modules/demo/README.md`, avsnitt "Friktion"):
  - **DI:** den typade formen av `to(...)` går inte att blanda `Class` och `Key` i; ett enda
    kvalificerat beroende gör att alla måste skrivas `Key.of(...)` (punkt 3).
  - **Konfiguration:** konfigurationsfilens plats är själv konfiguration och kräver en första
    omgång med samma källor och känsliga nycklar upprepade; ingen källa vars sökväg läses från
    en nyckel (punkt 4).
  - **Uppstart:** konfiguration, loggning, container och översättning av feltyper till
    meddelande och exitkod skrivs för hand (ingen gemensam supertyp för felen,
    `Container.create` tar bara varargs); hör till uppdrag 19 (punkt 5).
  - **Loggning:** `Logging.configure` är global för JVM:en; ett program som tar sin
    logg-ström som parameter måste konfigurera och återställa runt anropet, och två samtidiga
    anrop delar loggning (punkt 6).
  - **Loggning/konfiguration:** ett ogiltigt logger-prefix (`logging.level.a..b`) upptäcks av
    `LoggingSettings` efter bindningen, inte i `ConfigException` (punkt 7).

## Kända problem och begränsningar
- **HTTP (spola.http):** felaktiga chunked kroppar upptäcks först när kroppen läses
  (kroppen läses lat för `100 Continue`), så handlern anropas för dem; den får aldrig kroppen
  och servern svarar med kroppens status. Efter ett strömmande svar, eller med
  `Expect: 100-continue` och en kropp som aldrig begärdes, upptäcks felet först efter
  handlerns svar; då stängs anslutningen efter svaret. En handler som svarar utan att läsa en
  stor kropp får sitt svar skickat först när servern läst kroppen. `Transfer-Encoding` där `chunked` inte är sist ger
  400 (RFC 9112 §6.3) i stället för uppdragets 501. En ny anslutning får vänta
  `headerTimeout` på första byten och sedan `headerTimeout` till för huvudet. En handler som
  varken gör I/O eller reagerar på avbrott lever kvar efter `close()` (som slutar vänta 1 s
  efter avbrottet och loggar en varning). En förfrågan som kommer just när `close()` stänger en
  ledig anslutning går förlorad (klienten ser en stängd anslutning). Efterläsningen efter
  `Connection: close` håller anslutningen (och en plats i `maxConnections`) upp till 2 s om
  klienten inte stänger. Chunk-tilläggens radgräns (4 096 byte) och backlog (1 024) är fasta.
  Ingen TLS (07b), ingen routing eller procentavkodning (08), ingen HTTP/2, ingen komprimering.
  Kroppen kan bara läsas på handlerns tråd medan handlern eller dess `BodyWriter` kör.
- **JSON (spola.json):** indenterad utskrift växer med kvadraten på djupet (indraget), så
  `writePretty` på ett mycket djupt träd (tusentals nivåer) ger mycket stor utdata; kompakt
  utskrift är linjär. Ett parsat träd tar upp till ungefär 25 gånger indatans storlek i minne
  (cirka 110 MB vid standardgränsen 4 MiB). Inget strömmande API (utanför uppdraget).
  `bigIntegerValueExact` vägrar heltal med fler än 10 000 siffror. Kolumner räknas i
  kodpunkter, inte i grafem eller visningsbredd. C1-styrtecken (U+0080–U+009F) escapas inte av
  skrivaren (bara de tecken uppdraget anger).
- **JSON-mappning (spola.json.codec):** kodekar kan inte implementeras utanför paketet; egna
  typer byggs med `map(to, from)` över en befintlig kodek. Djupgränsen är fast (512, som
  `JsonLimits.DEFAULT`) och kan inte ställas per anrop. Rekursionen är begränsad till 512
  behållare men varje nivå tar några stackramar per kodek i kedjan; en tråd med mycket liten
  stack (under ungefär 512 KiB för en nod med en lista) kan fortfarande få `StackOverflowError`
  vid 512 nivåer. Avkodningen går igenom hela dokumentet även efter 100 problem (för att
  räkna). Ett `Error` (inte `RuntimeException`) från applikationskod (konstruktor, `map`,
  getter) släpps igenom. Ingen generering av kodekar från records (kan bli ett senare
  uppdrag med `spola.codegen`), inga namnstrategier, inga mappar med andra nycklar än
  `String`. En okänd medlem eller `map`-nyckel längre än 40 tecken kortas i sökvägen, så två
  sådana namn med samma början kan få samma sökväg. `BIG_INTEGER` skriver upp till 10 000
  siffror men `read` läser bara 1 000 tecken (`JsonLimits.DEFAULT`).
- **Codegen (spola.codegen):** generatorn läser bara syntaxträdet, så en medlemstyp som ärvs
  från en supertyp till recordet (eller en typ runt det) kvalificeras inte; den genererade
  filen kompilerar då inte förrän typen skrivs kvalificerad i recordet. Genererade filer känns
  igen bara på rubriken; tas den bort kontrolleras filen inte längre. Kontrollen jämför tecken
  för tecken, så en utcheckning med `core.autocrlf=true` (radslut `\r\n`) ger rött bygge.
  Ett record som självt deklarerar `withX(...)` med en parameter (även med en annan typ än
  komponentens) avvisas. Generiska varargs-komponenter ger javac:s heap pollution-varning i
  recordet självt (inte i den genererade koden).
- **Util (spola.util):** `Logging.configure` tar bort alla handlers på JUL-rotloggern, även
  främmande (de stängs inte). En nivå som någon annan satt på en logger (t.ex. i en
  `logging.properties`) lämnas orörd och går före ett kortare prefix för den loggern och dess
  barn. En post som loggas medan `configure` pågår kan gå förlorad. `Logging` gäller bara
  `System.Logger` med JDK:ns JUL-baserade `LoggerFinder`; med en egen `LoggerFinder` styr den
  formatet själv. Escapingen av styrtecken gör ett bokstavligt `\n` i ett meddelande
  omöjligt att skilja från en escapad radbrytning. Stackspårsrader får en tab först (skydd mot
  förfalskning); verktyg som läser vanliga JDK-stackspår ser därför en extra tab. Kastar
  backendens `isLoggable` släpps undantaget ur `Log` (utanför garantin, avsiktligt). Ett fel
  från backenden som `Log` sväljer skrivs ingenstans. Varargs-metoderna i `Log` får sin array av
  anroparen även när nivån är av (Java skapar den vid anropet).
- **Konfiguration (spola.config):** ett `bind`-anrop ger en felrapport; fel i två separata
  `bind`-anrop rapporteras var för sig (bind ett rotrecord med `section` för en rapport).
  Okända nycklar (stavfel i en fil) upptäcks inte. Listelement kan inte innehålla komma.
  Platshållare i profilnyckelns värde ersätts inte. Kastar bindningsfunktionen efter att
  problem registrerats släpps dess undantag (det kan innehålla värden). Enum-namn matchas
  exakt (skiftlägeskänsligt). Bara properties-format (JSON i uppdrag 06).
- **DI (spola.di):** i resolverformen upptäcks ett odeklarerat beroende först när
  komponenten skapas (inte vid `Container.create`); den typade formen har inte problemet.
  Nycklar bär en `Class<T>`, så generiska typer (`List<String>`) kan inte vara nycklar
  direkt; slå in dem i en egen typ eller record. Ingen `Provider`/lat injektion: en
  singleton som behöver en anropsscopad komponent per anrop måste själv bli `CALL` eller
  `PROTOTYPE`, eller hämtas via containern i anropet. Prototyper och `toInstance`-instanser
  stängs inte av containern. Cykelsökningen rapporterar minst en cykel per cyklisk del av
  grafen, inte alla elementära cykler.
- **Tester (spola.test):** ett test som inte stannar när det avbryts efter sin timeout
  överges på en daemon-tråd; dess `afterEach` körs bara om tråden någon gång blir klar och
  kan då gå samtidigt med senare tester. Ett test som anropar `System.exit` ger rött bygge
  (utan resterande tester och sammanfattning), se ARCHITECTURE.md ("Fail closed vid
  `System.exit`"); `Runtime.halt` i ett test går förbi alla shutdown hooks och kan inte
  fångas i samma JVM. Tester körs i samma JVM som bygget, i ordning, inte parallellt (parallell körning är
  utanför uppdraget).
- Testerna förutsätter `framework/` som arbetskatalog (bygget kräver det).
- `TestTool` använder reflektion (`Class.forName` + `Method.invoke`) för att anropa
  testingången; se ARCHITECTURE.md.
- Filkontrollen och källkontrollen täcker `framework/`, inte hela repot (uppdraget anger
  `framework/`). Repot i övrigt innehåller i dag inga jar- eller byggfiler.
- Reflektionskontrollen hittar direkta anrop och method references till JDK-metoder som
  returnerar annoteringar eller annoterade typer (`getAnnotation`, `getParameterAnnotations`,
  `getAnnotatedReturnType`, …) samt `isAnnotationPresent` och `Method.getDefaultValue`.
  Indirekta anrop (t.ex. via `MethodHandles.lookup()` med metodnamnet som sträng) upptäcks
  inte.
- Symboliska länkar är förbjudna i `framework/` (även fixtures). På Windows kräver
  självtestets fixture `symbolic-link` rätt att skapa länkar.
- `--should-stop=ifError=FLOW` är en dold (men stabil sedan JDK 9) javac-flagga.
- Bytekodkontrollen läser class-filer i `framework/` (i praktiken `out/classes`), inte
  innehållet i jar-filerna, som byggs från samma klasser. En jar-fil utanför `out/` är
  redan ett fel i sig, så dess klasser bytekodkontrolleras inte.
- Nästlade zip-arkiv med data framför sig (skript + zip *inuti* en annan zip) känns inte
  igen; på översta nivån gör de det. En skadad zip med data framför sig känns inte heller
  igen (JVM:en kan inte ladda den).
- **Komprimerat eller kodat innehåll kontrolleras inte maskinellt:** gzip, tar, xz, 7z,
  base64, krypterat och liknande måste packas upp eller avkodas innan JDK:n kan köra eller
  ladda det, så guardrails läser inte in sådana filer (gränsen från gauntlet-runda 3:
  maskinkontrollen täcker det som JDK:n kör eller laddar direkt). REGLER §1 gäller ändå och
  upprätthålls av granskning.
- **jshell-indata i dokument:** jshell kör vilken text som helst, rad för rad, och hoppar
  över det som inte kompilerar. Ett `.md`-dokument kan därför innehålla kodrader som
  `jshell DOKUMENT.md` kör. Det går inte att avgöra maskinellt utan att förbjuda all prosa,
  så dokument granskas som text. Alla andra filnamn (även `.jsh`) är redan förbjudna.
- Ett `.md`-dokument som javac kan parsa som Java (även ett kodexempel som utgör hela
  filen) ger rött bygge. Kodexempel i dokument måste därför stå tillsammans med prosa
  (Markdown-rubriker och text gör att filen inte parsar).
- **Fixture-innehåll är bara låst genom sina förväntade överträdelser** (domarens gräns,
  runda 5): `tools/*` och `docs/*.md` i `launcher-source` är medvetet körbar Java, och mer
  sådant innehåll i dem skulle inte märkas av självtestet. `tree.txt` i `FILES`-fixtures
  kontrolleras dock: parsar den som Java i någon läsning blir självtestet rött. I övrigt
  upprätthålls fixture-innehåll av granskning.
- Förväntade radnummer i `Fixtures.ALL` är hårdkodade och måste uppdateras när en
  fixture-fil ändras (självtestet skriver ut de faktiska raderna).
- Nya filtyper i `framework/` (resurser, testdata) kräver att `Allowlist.DOCUMENT_EXTENSIONS`
  utökas medvetet; de kontrolleras då som dokument. Editorfiler (`.swp`, `.idea/`) i
  `framework/` ger rött bygge.
