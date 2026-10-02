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
- Tillägg i kärnmodulerna efter friktionsrapporten: `Section.keys()` (`spola.config`) och
  `LoggingSettings.parseLevel(String)` (`spola.util`), med tester, Javadoc och README.
- `modules/demo/README.md`: körning, Spring Boot-jämförelse och avsnittet "Friktion" (2
  **Åtgärdat**, 5 **Kvar**, och det som fungerade bra).
- Runda 2 i 05c: orderfilen tål ett byte order mark först (Excel/Notepad); en artikel med
  styr-, format- eller avgränsartecken (ESC, U+0085, U+2028, U+202E ...) är en trasig rad
  (65) i stället för att skrivas rått till sammanfattningen och revisionsspåret; `audit.file`
  som pekar på orderfilen är ett konfigurationsfel (78); ett revisionsspår som inte kan
  öppnas ger 74 och nämner `audit.file`.
- 40 tester i `spola.demo` (hela programmet genom ingången, prioritetsordningen med alla fem
  lager, hemligheten i utdata/logg/fel, stängning vid lyckad och avbruten körning, loggnivåer
  från argument/miljö/profil, alla felvägar, modulgränserna på källkoden, HMAC mot RFC 4231
  testfall 1 och 2, orderradsformatet). `spola.config` har nu 66 tester och `spola.util` 80.
  Bygget tar ungefär 17 s.

## Återstår (enligt `uppdrag/PLAN.md`)
- Rad 05 är delad: 05a (loggning och hjälpare) är klar; **05b (util-builders):** builders och
  with-metoder för records återstår (kräver eget designbeslut: utan annoteringar och
  runtime-reflektion återstår i praktiken genererad, incheckad källkod).
- 06–20 enligt planen.
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
