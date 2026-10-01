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

## Återstår (enligt `uppdrag/PLAN.md`)
- 05–20 enligt planen.

## Kända problem och begränsningar
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
