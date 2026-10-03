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
  på `ServerSocket` med en virtuell tråd per anslutning, acceptor som plattformstråd, en
  plattformstråd (keeper) som inte är daemon och håller JVM:en igång tills `close()` är klart, `Handler`, `Request` (rå target, sökväg, query, `host()`, version, `Headers`,
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
  samtidigt. JVM:en lever tills `close()` är klart och, när en handler anropade `close()`,
  tills den handlerns svar har gått ut (även när `main` redan returnerat). Oläst kropp läses före svaret, så en trasig kropp ger sin status i stället för
  handlerns svar.
- `README.md` i modulen med Spring Boot/Tomcat-exempel, gränstabell mot Spring Boots
  egenskaper och tabell över vad som avvisas.
- 172 tester i `spola.http` (cirka 16 s): `HttpClient` (GET, POST, strömmad POST, HEAD),
  keep-alive och pipelining, 71 felaktiga huvuden och 14 felaktiga chunked kroppar med status
  och RFC-avsnitt (kropparna med en handler som läser hela, en del av eller ingenting av kroppen), smuggelförsök, chunked åt båda hållen (byte för byte), 100-continue,
  tidsgränserna med korta värden, `maxConnections`, fel i handler och `BodyWriter`, svarets
  validering och `Date`, mjukt stopp (även i en egen JVM där `main` returnerat och en handler
  stoppar servern), 1 000 samtidiga klienter, loggar och felkroppar utan
  styrtecken, och källkodsreglerna.

### Uppdrag 07b: HTTP-server del 2, TLS (HTTPS) ovanpå `spola.http`
- TLS som valfritt värde: `HttpServerOptions.tls` (`Optional<TlsOptions>`, `withTls`,
  `withoutTls`; `DEFAULT` utan TLS, den gamla konstruktorn finns kvar). `HttpTimeouts` fick
  `handshakeTimeout` (standard 10 s; den gamla konstruktorn med fem värden finns kvar).
- `TlsOptions` (record: `TlsIdentity`, `ClientAuth`, `Optional<TlsTrust>`, protokoll,
  chiffersviter) med `pkcs12(path, password[, alias])`, `pem(chain, key)`, `of(SSLContext)`,
  `withProtocols`, `withCipherSuites`, `withClientAuth`. `TlsIdentity`, `TlsTrust` (PKCS#12
  eller PEM), `ClientAuth` (`NONE`/`WANT`/`NEED`), `TlsInfo`, `TlsMaterialException`.
  `Request.scheme()` och `Request.tls()`.
- Material läses och kontrolleras när `TlsOptions` skapas; lösenord kopieras och nollställs;
  inga lösenord eller nyckelbytes i meddelanden, orsaker eller `toString()`. Strikt PEM-läsare
  utan preview-API; okrypterad PKCS#8 (RSA, EC, Ed25519/Ed448); krypterade nycklar och PKCS#1
  avvisas med konverteringskommando. Nyckeln kontrolleras mot certifikatet, kedjans ordning
  kontrolleras.
- Bara TLS 1.3 och 1.2; JDK:ns chiffersviter som standard, uttrycklig lista kontrolleras.
  ALPN bara `http/1.1`. Handslaget på anslutningens virtuella tråd inom en total
  `handshakeTimeout` från accept (vakttråden stänger vid deadline); anslutningar i handslag
  räknas mot `maxConnections`. Misslyckade handslag loggas på `DEBUG`, escapat.
- Över TLS: samma HTTP-semantik, `writeTimeout`, mjukt stopp med `close_notify` (ledig
  anslutning: `shutdownInput`, tråden stänger själv), avbrott via TCP-socketen. Läsningarnas
  tidsgränser (huvud, ledig, kropp, efterläsning) hålls också som deadlines av vakttråden,
  eftersom en `SSLSocket`-läsning kan överleva sin `soTimeout` (en post en byte i taget).
- PEM-läsaren kräver utfylld Base64 (längd multipel av fyra, RFC 7468 strikt).
- Modulen sätter inga system- eller säkerhetsegenskaper. README: HTTPS-avsnitt med PKCS#12,
  PEM och mTLS bredvid Spring Boot, tabell `server.ssl.*` → `TlsOptions` med avvikelser,
  omförhandlingsrisken och `jdk.tls.rejectClientInitiatedRenegotiation`.
- Tester: 07a:s 172 oförändrade förväntningar (urvalet av protokolltester körs nu också över
  TLS via en `Transport`-parameter), plus TLS-tester: `HttpClient` GET/POST mot PKCS#12,
  PKCS#12 med alias, PEM RSA/EC/Ed25519 och `SSLContext` (värdnamnskontroll på, också ett
  negativt namnfall), TLS 1.3/1.2 och avvisade TLS 1.1/1.0/SSLv3 (JSSE-klient och råa
  ClientHello), ALPN, mTLS `NEED`/`WANT`/`NONE`, 32 fall felaktigt material, lösenordsarrayen,
  options-kontroller, `handshakeTimeout` (ClientHello en byte i taget och tyst klient medan en
  annan klient betjänas), huvud-, ledig- och efterläsningstid som totaler över TLS (en post en
  byte i taget), misslyckade handslag och loggar, `maxConnections` under handslag,
  `writeTimeout`, mjukt stopp och 200 samtidiga HTTPS-klienter. Certifikaten skapas av
  testerna med `keytool` och `KeyStore`-API:t (`TestPki`). Totalt 281 tester i modulen,
  cirka 30 s.

### Uppdrag 08a: web del 1, routing som kod, parametrar, filter och felhantering (`spola.web`)
- Rad 08 i `PLAN.md` är delad i 08a (detta: routing, procentavkodning, sökvägs- och
  queryparametrar, filter, attribut, felhantering, problem-svar) och 08b (JSON-kroppar via
  `spola.json.codec`, `Content-Type`/`Accept` med 415/406, cookies, formulärkroppar); se
  ARCHITECTURE.md ("Web del 1").
- Modulen `spola.web` (`modules/web/`): `requires transitive spola.http`, `requires
  transitive spola.json` (för `Problem.extensions`, se ARCHITECTURE.md), `requires static
  java.net.http` bara för testerna, ingen reflektion, inga ändringar i `spola.http`.
- `Router` (en `spola.http.Handler`, oföränderlig, trådsäker) byggd med `Router.builder()`:
  `get`/`post`/`put`/`patch`/`delete`/`route`, `filter`, `group(prefix, RouteGroup ->
  ...)` (nästlade, variabler i prefixet), `onException`, `build()`. `build()` samlar alla
  problem (mönster, prefix, metoder, samma metod och form, dubbla mappers, mappers för
  `Error`) i en `RouterDefinitionException`.
- Mönster: literal, `{namn}`, `{namn...}` sist, roten `/`, strikt avslutande snedstreck.
  Trie per segment, literal före variabel före catch-all med backtracking, oberoende av
  registreringsordning; 404, 405 med `Allow`, `HEAD` via `GET`, automatiskt `OPTIONS`,
  `OPTIONS *` ger 404.
- Strikt sökväg före routing (400): delning före avkodning, strikt UTF-8, `%2F`, `%5C`,
  kodade styrtecken, `.`/`..` (även kodade), `//`; ingen dubbelavkodning.
- `WebRequest` (oföränderlig): avkodad sökväg och segment, `pathParam` (typad med `Param`),
  catch-all som sträng och lista, lat query (högst en parsning, högst 1 000 par,
  form-urlencoded), `requiredQuery`, typade attribut med `AttributeKey`.
- `Param`: `STRING`, `INT`, `LONG`, `BOOLEAN`, `UUID`, `LOCAL_DATE`, `BIG_DECIMAL`,
  `enumOf`, `of`, alla strikta; fel ger 400 med parameterns namn och värdet kort och escapat.
- Filter (routerns för allt, gruppens för gruppens routes, första ytterst), undantag mappas i
  det steg där de uppstår (närmaste klass vinner), `WebException` och `Problem` (RFC 9457,
  `application/problem+json`, text högst 2 000 tecken), 500 utan meddelande och loggat
  escapat på `spola.web`; `RequestBodyException` går till `spola.http`.
- `README.md` i modulen: komplett exempel bredvid Spring Boot (kompilerat mot modulen) och
  avvikelsetabell mot Spring MVC.
- 288 tester i `spola.web` (cirka 2 s): avkodning mot UTF-8-gränserna, alla metoder via
  `HttpClient`, 32 matchningsfall i två registreringsordningar och backtracking-exemplet för sig, 404/405/`HEAD`/`OPTIONS`,
  22 felaktiga registreringar och sju problem i ett undantag, 36 felaktiga sökvägar över rå
  socket utan att handlern anropas, `%252F`, 17 query-fall plus gränsen 1 000/1 001 och lat
  parsning, 22 giltiga och 39 ogiltiga typade värden, filterordning och omfång, mappers,
  `RequestBodyException` (chunked över `maxBodySize`), problem-formatet, loggrader utan
  styrtecken, 1 000 routes och 200 samtidiga klienter, och källkodsreglerna.

### Uppdrag 08b: web del 2, JSON-kroppar, mediatyper och innehållsförhandling (`spola.web`)
- Planeraren delade 08b: **08b** (detta: `MediaType`, `Accept`, förhandling, JSON in och ut,
  415/406/413 och problem-svar för kroppsfel) och **08c** (cookies, `Set-Cookie`,
  formulärkroppar). Multipart ligger utanför båda. Se ARCHITECTURE.md ("Web del 2").
- `MediaType` (record): strikt RFC 9110 §8.3.1, gemener för typ/subtyp/parameternamn,
  `charset` utan hänsyn till skiftläge, `quoted-string` med escape, högst 64 parametrar och
  1 024 tecken, `includes` (jokertecken och parametrar), `isJson` (`+json`), `charset()`,
  kanonisk `toString`.
- `AcceptHeader`: q-värden (0–1, högst tre decimaler), accept-ext efter `q` ignoreras, flera
  fält slås ihop, komma i citat delar inte, högst 100 intervall; kvaliteten från det mest
  specifika intervallet oberoende av ordning, `q=0` utesluter, serverns ordning vid lika
  kvalitet, saknat `Accept` godtar allt. `charset=utf-8` i ett intervall godtar JSON utan
  `charset` (RFC 8259 §8.1), så `Accept: application/json; charset=utf-8` ger 200 (runda 2).
- `WebRequest`: `contentType()` (415), `accept()` (400), `negotiate(...)` (406/400),
  `readJson(codec)` och `readJson(codec, limits)` med ordningen `Content-Encoding` (415),
  `Content-Type`/`charset` (415 med `Accept: application/json`), tom kropp (400), strömmande
  parsning (413 över `maxInputSize`, 400 för syntax och gränser, utan text ur kroppen),
  mappning (400 med `errors`). Kroppen läses en gång (`IllegalStateException`).
  `RouterBuilder.jsonLimits`.
- `Responses`: `json` (förhandlar, buffrar, `application/json` utan charset,
  `Content-Length`), `created` (201 med `Location`), `noContent`; `encode`-fel ger 500.
- `Problem.FieldError`/`withErrors` och `WebException.errors()`: `errors`-formatet
  (`path`, `message`, högst 100) som 09 ska återanvända.
- `spola.json`: `JsonMappingException.problemCount()` (litet tillägg, se ARCHITECTURE.md).
  Inga befintliga tester ändrade; `module-info.java` i `spola.web` oförändrad.
- `README.md` i `modules/web/`: CRUD-exempel med JSON bredvid Spring Boot (exemplet är
  testkällan `ItemApi.java`, och ett test kontrollerar att README visar den oförändrad),
  förhandlingsexempel och nya avvikelserader.
- 455 tester i `spola.web` (cirka 2 s; 167 nya): 25 giltiga och 35 ogiltiga `MediaType`, 55
  förhandlingsfall genom servern, CRUD med `HttpClient` och `HEAD`, 11 fall av 415 och tre
  godtagna JSON-typer, 400 för tom kropp/syntax/djup, tre mappningsfel på olika nivåer, 250
  fel, loggrader utan styrtecken, 413 för en kropp på 2 MiB som strömmas chunked mot 1 KiB,
  exakt `maxInputSize + 1` lästa byte, `maxBodySize` från `spola.http`, egna gränser, läs en
  gång, mappers för 415/400, 406 och problem-svar med `Accept: text/html`, `encode`-fel, 200
  samtidiga klienter med JSON.

### Uppdrag 08c: web del 3, cookies, `Set-Cookie` och formulärkroppar (`spola.web`) — rad 08 i `PLAN.md` klar
- Med 08a, 08b och 08c är **rad 08 i `PLAN.md` (webblagret) klar**. Multipart och
  filuppladdning ligger utanför (se ARCHITECTURE.md, "Web del 3").
- `WebRequest`: `cookie(name)`, `cookieAll(name)`, `cookie(name, Param)` (400 med
  `Invalid cookie "x": ...`) och `cookies()`. Lat parsning, högst en gång per förfrågan och
  delad av alla `withAttribute`-kopior (`CookieState`, som `QueryState`). RFC 6265 §4.2.1/§5.4:
  flera `Cookie`-fält slås ihop, delning på `;`, trimning av mellanslag/tab, namnet en
  `token`, värdet `*cookie-octet` eller citerat; citattecknen behålls och inget avkodas.
  Felaktiga par hoppas över tyst; fler än 200 giltiga par ger 400 utan namn eller värden.
- `ResponseCookie` (oföränderlig, även buildern): `of(name, value)`, `expire(name)`,
  `path`, `domain`, `maxAge` (hela sekunder), `secure`, `httpOnly`, `sameSite`
  (`SameSite.STRICT/LAX/NONE`). Standard `Path=/`, `HttpOnly`, `SameSite=Lax`. Validering i
  `build()` (namn, värde, sökväg, domän, negativ `maxAge`, `SameSite=None` utan `Secure`,
  `__Secure-`, `__Host-`) med kort escapat `IllegalArgumentException`. Fast attributordning,
  inget `Expires`. `Responses.withCookie` ger ett eget `Set-Cookie`-fält per cookie.
- `WebRequest.readForm()`/`readForm(maxBytes)` och `FormData` (`first`, `all`, `get`,
  `required`, `asMap`, `isEmpty`): ordningen `Content-Encoding` (415) → `Content-Type`/
  `charset` (415 med `Accept: application/x-www-form-urlencoded`) → strömmande läsning till
  gränsen (413) → byte över 0x7F (400) → `Decoding.parseQuery` (400 utan text ur kroppen,
  högst 1 000 par). Tom kropp ger tomt formulär. Formulär och query slås inte ihop.
  `RouterBuilder.maxFormSize(int)` (standard 2 MiB). `BodyState` delas med `readJson`,
  `body()` och `bytes()`; ett andra försök ger `IllegalStateException` (500).
- `MediaType.APPLICATION_FORM_URLENCODED` (ny konstant). Interna ändringar: `JsonBody.checkEncoding`
  och `JsonBody.single` tar emot 415-huvudena/används av `FormBody`, `Decoding.parseQuery` har
  en variant med subjekt för detaljen (`The form body ...`). Inga ändringar i `spola.http`,
  inga nya `requires`, inga befintliga tester ändrade (bara de nya sviterna registrerade i
  `WebTests`).
- `README.md` i `modules/web/`: formulär- och cookieexempel bredvid Spring Boot (testkällan
  `ProfileApp.java`, kontrollerad av ett test), nya rader i jämförelsetabellen och sju nya
  avvikelserader.
- 591 tester i `spola.web` (cirka 2,5 s; 136 nya): 36 fall av cookieparsning, 200/201
  cookies, parsning en gång över kopior och 24 trådar, typade cookies; 22 giltiga och 29
  ogiltiga `ResponseCookie` med exakt text och utskrivbara meddelanden, tre `Set-Cookie`-rader
  över rå socket, `HttpClient` med `CookieManager` genom README-exemplet; formulärflödet,
  10 fall av 415, 8 fall av 400 med markörsträng, 1 000/1 001 par, 413 för 4 MiB chunked mot
  1 KiB, exakt `max + 1` lästa byte, egna gränser, `maxBodySize`, läs en gång, mappers, 200
  samtidiga klienter med formulär och cookies, och loggrader/problem-detaljer utan styrtecken.

### Uppdrag 09a: validering del 1, komponerbara validatorer och felrapporter (`spola.validation`)
- Rad 09 i `PLAN.md` är delad i **09a** (detta: den fristående modulen `spola.validation`) och
  **09b** (webbintegration: 400-svar med `errors`, `readJson`/`readForm` med validering,
  formulär till records). 09b är klar (se nästa avsnitt).
- Ny modul `spola.validation` (`modules/validation/`), utan `requires` på andra Spola-moduler;
  bara `requires static java.compiler` för kompileringsfelstestet. Tillagd i `MODULES` i
  `Build.java` med testingången `spola.validation.ValidationTests`. Inga andra moduler ändrade.
- `Validator<T>` (funktionellt gränssnitt: `validate(value, errors)`, `check`, `require`,
  `of(constraints...)`, `lazy(supplier)`, `builder()`), `Validator.Builder` (`field`,
  `optional`, `nested`, `each`, `eachNested`, `rule` på objektet och på ett fält, `include`,
  `when`, `build`), `Errors` (`at`, `atIndex`, `atKey`, `reject`, `path`), `Violation(path,
  code, message)` utan värde, `Validated` (sealed: `Valid`, `Invalid(violations, count)`),
  `ValidationException`, `Constraint` (klass: `of`, `withMessage`, `withCode`, `code`,
  `message`, `accepts`) och `Constraints` (27 inbyggda med fasta koder och engelska texter).
- Sökvägar i `spola.json.codec`s notation, renderade med samma algoritm (deklarerade namn i
  sin helhet, `atKey` kortad efter 40 tecken med längden). Högst 100 överträdelser behålls,
  alla räknas; sökvägen renderas bara för de behållna. `getMessage()` är högst 2 000 tecken,
  utskrivbar ASCII. Meddelanden i `Violation` behåller bokstäver men escapar styrtecken,
  radavgränsare, bidi-tecken och ensamma surrogat, och kortas efter 500 tecken.
- `README.md` i `modules/validation/`: `CreateOrder`-exemplet bredvid Spring Boot (testkällan
  `OrderValidation.java`, kontrollerad av ett test), byggarens steg, tabell från Jakarta till
  Spola och avvikelsetabell.
- 251 tester i `spola.validation` (cirka 1 s): 147 tabellfall för constraints, 16 giltiga och
  27 ogiltiga e-postadresser, sökvägar mot kodekens fall, README-exemplet, gränsen med 10 000
  element och en miljon överträdelser, egna validatorer och constraints, kompileringsfel via
  `javax.tools`, undantag från användarkod, 200 virtuella trådar och escaping.

### Uppdrag 09b: validering del 2, validering och formulärbindning i webblagret (`spola.web` + `spola.validation`) — rad 09 i `PLAN.md` klar
- Med 09a och 09b är **rad 09 i `PLAN.md` (validering) klar**.
- `spola.web` har `requires transitive spola.validation` (inga andra nya `requires`);
  `spola.validation` är oförändrad och fristående. Ingen bryggmodul.
- `Problem.FieldError(path, message, Optional<String> code)`: den gamla konstruktorn ger tom
  `code`, `toJson()` skriver `code` sist och bara när den finns (mappningsfelen från
  `readJson` är byte för byte som förut), en kod är 1–100 tecken utskrivbar ASCII utan
  mellanslag.
- `WebException.invalid(Validated.Invalid<?>)` och `invalid(ValidationException)`: 400,
  `The request has N validation errors.` (singular för 1, ` The first 100 are listed.` över
  100) och `errors` med `path`, `message` och `code`.
- `WebRequest.readJson(codec, validator)` och `readJson(codec, limits, validator)`: alla
  kontroller från `readJson` först, sedan `validator.check`; validatorn anropas aldrig efter
  ett läs-, parsnings- eller mappningsfel, och dess undantag släpps igenom oförändrade.
- `FormBinder<T>` (`bind(FormFields)`, standardmetoden `bind(FormData)`) och `FormFields`
  (`required`, `optional`, `all`, `flag`) som samlar alla fel med koderna `required`, `type`
  och `single`, sökvägar i kodekens notation (privat kopia av renderingen), tomt värde som
  saknat utom för `Param.STRING`, inga ekade värden, högst 100 fel (alla räknas) och
  undantagsregeln för bindern. `WebRequest.readForm(binder)`, `readForm(binder, validator)`,
  `bindQuery(binder)` och `bindQuery(binder, validator)` (detaljen `Request body has N
  problems.` respektive `Query has N problems.`).
- `ValidationException` från tjänstekod mappas inte automatiskt (500); README visar mappern på
  en rad.
- `README.md` i `modules/web/`: JSON-exemplet (`OrderApi.java`, `@Valid @RequestBody`) och
  formulärexemplet (`SignupApp.java`, `@Valid @ModelAttribute` + `BindingResult`) bredvid
  Spring Boot, kontrollerade av ett test; sex nya rader i jämförelsetabellen och sex nya
  avvikelserader.
- 730 tester i `spola.web` (cirka 2,5 s; 139 nya): `FieldError` och `WebException.invalid`,
  `readJson` med validator genom server och `HttpClient` (giltigt värde, fem fel i fält och
  nästlad lista, åtta fall med samma svar som utan validator och validatorn oanropad, 250 fel,
  undantag till mapper och 500, egna gränser, inget ekat lösenord, `ValidationException` med
  och utan README-mappern), en tabell med 52 bindningsfall, gränsen 1 000/101/100 fel,
  undantagsregeln, `FormFields` efter bindningen, 26 sökvägsfall mot `Errors.at`/`atIndex`,
  `readForm`/`bindQuery` med binder och validator genom server, och 200 samtidiga klienter med
  delade `static final` binder, kodek och validator.
- Ett befintligt test ändrades: `SourceRulesTests` fäster `spola.web`s exakta `requires`, och
  den förväntade listan fick `transitive spola.validation` (annars kan bygget inte vara grönt).
  Övriga befintliga testfiler är oförändrade utom registreringen i `WebTests`.

### Uppdrag 10a: säkerhet del 1, lösenordshashning och autentisering (`spola.security`)
- Rad 10 i `PLAN.md` är delad i **10a** (detta: den fristående modulen `spola.security`) och
  **10b** (filterkedja i `spola.web`, `SecurityContext` per förfrågan, HTTP Basic,
  formulärinloggning, auktoriseringsregler, 401/403). **Rad 10 fortsätter i 10b.**
- **Säkerhetskritisk kod.** Modulen hashar lösenord och avgör inloggningar. Den är testad mot
  RFC 7914 §11 och mot JDK:ns `SecretKeyFactory`, men **en människa bör granska den** innan
  ramverket används på riktigt.
- Ny modul `spola.security` (`modules/security/`), utan några `requires` alls (bara
  `java.base`). Tillagd i `MODULES` i `Build.java` med testingången
  `spola.security.SecurityTests`. Inga andra moduler ändrade. Loggar på `System.Logger`
  `spola.security`.
- `Pbkdf2.deriveSha256(password, salt, iterations, lengthBytes)`: PBKDF2-HMAC-SHA256 (RFC 8018
  §5.2) på `javax.crypto.Mac`; kräver lösenord och salt på minst en byte, `iterations >= 1` och
  `1 <= lengthBytes <= 1024`.
- `PasswordHasher` (`hash`, `matches`, `needsRehash`) och `Pbkdf2PasswordHasher` (`defaults()`:
  600 000 iterationer, 16 byte salt, 32 byte hash; `of(...)` inom 10 000–10 000 000, 16–64,
  16–64). Lösenord: UTF-8 med `REPORT`, 1–1 024 tecken (kontrolleras före kodningen), ingen
  normalisering, byte-arrayen nollställs. PHC-format `$pbkdf2-sha256$i=N$salt$hash` (Base64
  utan utfyllnad) med strikt tolkning: högst 256 tecken, utskrivbar ASCII, fem delar, `i` utan
  inledande nolla 1–10 000 000, kanonisk Base64, salt 8–64 och hash 16–64 byte. En felaktig
  hash ger `IllegalArgumentException` utan strängen. Jämförelse med `MessageDigest.isEqual`.
- `User` (record; användarnamn 1–256 tecken, roller 1–100 tecken utskrivbar ASCII utan
  mellanslag, oföränderlig sorterad kopia, `toString()` utan hash och med escapade styrtecken),
  `UserStore`, `InMemoryUserStore` (`add`, `find`, `updatePassword`; trådsäker,
  skiftlägeskänslig), `Identity` (`hasRole`), `AuthResult` (sealed: `Authenticated`,
  `Failed`), `Failure` (`BAD_CREDENTIALS`, `DISABLED`), `PasswordUpgrade` och `Authenticator`
  (dummyhash för okända och ogiltiga användarnamn och `null`-lösenord, `DISABLED` bara efter rätt
  lösenord, uppgradering vid `needsRehash` där ett `RuntimeException` loggas med klassnamnet och
  inloggningen ändå lyckas).
- `README.md` i `modules/security/`: exemplet (`LoginExample.java`, kontrollerat av ett test)
  bredvid Spring Security (`PasswordEncoder`-böna, `InMemoryUserDetailsManager`,
  `DaoAuthenticationProvider` med `UserDetailsPasswordService`), jämförelsetabell och
  avvikelsetabell.
- 101 tester i `spola.security` (cirka 1 s): RFC 7914-vektorerna, 60 slumpade fall mot
  `SecretKeyFactory`, hasharen, 44 felaktiga lagrade hashar, `User`/`Identity`/
  `InMemoryUserStore`, `Authenticator` med räknande hasher, 200 virtuella trådar, markörer för
  lösenord och hashar i `toString()` och undantag, README och källregler.

## Återstår (enligt `uppdrag/PLAN.md`)
- 10b (rad 10 fortsätter: filterkedja, HTTP Basic, formulärinloggning, auktorisering, 401/403)
  och 11–20 enligt planen (rad 08 är klar med 08a–08c, rad 09 med 09a och 09b).
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
- **Säkerhet (spola.security, 10a):** säkerhetskritisk; bör granskas av en människa. Bara
  PBKDF2-HMAC-SHA256 (ingen BCrypt, SCrypt, Argon2 eller delegerande hasher). Ingen låsning av
  konton och ingen hastighetsbegränsning; varje försök kostar en PBKDF2-beräkning, så en
  angripare kan belasta CPU:n (utanför 10a). Ingen normalisering av
  lösenord (Unicode eller blanksteg) eller användarnamn. Ingen lösenordspolicy. Lösenordet
  finns som `CharSequence` (oftast en `String`) hos anroparen och kan inte nollställas av
  modulen; bara de kodade byten och PBKDF2:s interna kopior nollställs, och `Mac`-instansens
  inre tillstånd (nyckelns ipad/opad) rensas inte (JDK:n har inget API för det). Ett lösenord
  som `matches` vägrar (tomt, för långt, ensamt surrogat) ger `false` utan PBKDF2, alltså
  snabbare än andra; det avslöjar inget om användaren. En hash med andra inställningar än
  hasharens (inom tolkningsgränserna, t.ex. 1 iteration) verifieras med sina egna inställningar;
  den som kan skriva i användarlagringen kan alltså sänka kostnaden för sin egen rad.
  Konstanttid gäller jämförelsen av hashar, inte uppslagningen i `UserStore`.
- **Validering i webben (spola.web, 09b):** formulär är platta; nästlade namn
  (`address.city`, `items[0].qty`) binds inte till nästlade objekt (skicka JSON). Ingen
  generering av binders från records (allt skrivs i bindern). Ingen i18n av meddelanden eller
  koder. `FormFields` är inte trådsäker och kontrollen efter bindningen är bästa försök. Ett
  undantag som bindern kastar efter insamlade fel kastas bort (också en `WebException` med
  annan status, och ett `IllegalStateException` från en `Param` som returnerar `null`); ett
  `Error` släpps alltid igenom. En `Violation` med en kod över 100 tecken (eller med
  mellanslag, om den byggts för hand) ger `IllegalArgumentException` i `WebException.invalid`,
  alltså 500. `flag` godtar bara `on`, `true`, `1`, `false` och `0` (skiftlägeskänsligt); ett
  tomt värde ger `false`. Validering av sökvägs-, cookie- och huvudparametrar sker bara genom
  `Param`. Ingen multipart.
- **Validering (spola.validation, 09a):** ingen cykeldetektering; en objektgraf som innehåller
  sig själv valideras tills stacken tar slut (`StackOverflowError` släpps igenom). Djupa
  strukturer valideras rekursivt på Java-stacken. Ingen i18n och ingen interpolering av
  meddelanden. `email()` godtar inte internationaliserade adresser. `matches` kör användarens
  mönster på hela värdet utan tidsgräns. `Errors` som används efter sin validering ger
  `IllegalStateException` (bästa försök, inte trådsäkert).
- **Web (spola.web, 08c):** inget `Expires` skrivs (beslut i uppdraget). JDK:ns
  `java.net.CookieManager` tolkar ett `Set-Cookie` med `Max-Age` men utan `Expires` som RFC
  2965 och skickar tillbaka `$Version="1"; namn="värde";$Path=...`; värdet kommer då med
  citattecken, som Spola behåller, så `cookie(namn)` ger `"värde"`. Webbläsare följer RFC
  6265 och berörs inte; ett test fäster beteendet. Sessionscookies (utan `maxAge`) fungerar
  med `CookieManager`. Efter en 413 från `readForm` läser `spola.http` resten av kroppen (upp
  till `maxBodySize`) innan svaret skickas, som för `readJson`. En formulärkropp får bara
  innehålla synlig ASCII: råa mellanslag, tab eller radbrytningar (t.ex. ett avslutande
  `\r\n` från ett handskrivet `curl`-anrop) ger 400. `readForm` kräver `Content-Type` också
  för en tom kropp. Överhoppade cookies loggas inte, så en felaktig cookie syns inte i
  serverns logg. Ingen multipart, inga sessioner, signerade cookies eller CSRF (uppdrag 11).
- **Web (spola.web, 08b):** efter en 413 från `readJson` läser `spola.http` resten av kroppen
  (upp till `maxBodySize`, utan att buffra) innan svaret skickas; en klient som skickar en
  stor kropp får alltså vänta tills den skickats. `readJson` kan inte veta om någon läst
  `request.http().body()` direkt (bara `WebRequest.body()`/`bytes()` räknas). Bara UTF-8
  godtas (`charset=utf8` utan bindestreck ger 415). Ett `Accept` med bara tomma element
  behandlas som saknat. `Responses.json` buffrar hela svaret (inget strömmande JSON-svar).
  Ett parsat träd tar upp till ungefär 25 gånger kroppens storlek i minne (se JSON), så
  `jsonLimits` bör sättas lägre än 4 MiB för publika API:er med många samtidiga klienter.
  Accept-ext efter `q` ignoreras, även ett andra `q`. `text/plain;charset=utf-8` i `Accept`
  godtar inte ett erbjudet `text/plain` (bara JSON har undantaget). Det gamla
  `HttpURLConnection`-standardhuvudet (med ett ensamt `*`) ger 400. Ingen `Accept-Charset`,
  `Accept-Language`, ETag eller villkorliga förfrågningar.
- **Web (spola.web, 08a):** queryn parsas lat, så en ogiltig query (felaktig kodning, över
  1 000 par) ger 400 bara om något läser den. För en förfrågan som får 400 från
  sökvägskontrollen ser filtren den råa sökvägen i `path()` och tomma `pathSegments()`.
  Routingen avgörs före filtren; ett filter kan inte skicka förfrågan till en annan route.
  Ett `Error` från handler eller filter går förbi filtren och mappers till `spola.http`
  (500 i klartext). Ett undantag som omsluter en `RequestBodyException` och har en mapper
  (t.ex. för `Exception`) mappas av den. C1-tecken och andra Unicode-tecken är tillåtna i
  sökvägen (bara escapade i loggar). `Param.enumOf` läser konstanterna med
  `Class.getEnumConstants()` en gång. Gruppfilter körs inte för
  404/405 under gruppens prefix. Mönster med `;` (matrisparametrar) har ingen särskild
  betydelse.
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
  Routing och procentavkodning finns i `spola.web` (08a); ingen HTTP/2, ingen komprimering.
  Kroppen kan bara läsas på handlerns tråd medan handlern eller dess `BodyWriter` kör.
- **HTTPS (spola.http, 07b):** ingen omladdning av certifikat i drift, ingen SNI med flera
  certifikat, ingen spärrkontroll (CRL/OCSP) av klientcertifikat utöver JDK:ns standard.
  Krypterade PEM-nycklar, PKCS#1 och separata nyckellösenord i PKCS#12 stöds inte; JKS bara
  via `TlsOptions.of(SSLContext)`. Klientinitierad omförhandling i TLS 1.2 styrs bara av JDK:ns
  globala `jdk.tls.rejectClientInitiatedRenegotiation` (modulen sätter den inte). Vanlig HTTP
  till TLS-porten får inget HTTP-svar, bara en stängd anslutning. `Request.tls()` kräver en
  `getSession()` per förfrågan. Nyckelmaterialets text (`String` från filen) kan inte
  nollställas. Väcks en blockerad läsning inte av `shutdownInput` (beror på plattformen)
  stängs en ledig TLS-anslutning vid mjukt stopp först vid `shutdownTimeout`. En klient som
  överskrider `headerTimeout` över TLS kopplas ned utan `408` (socketen stängs under
  TLS-lagret). Över TLS måste en hel TLS-post komma inom `readTimeout`.
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
