# Spola – arkitektur och designbeslut

Grundreglerna i `REGLER.md` går före allt i detta dokument.

## Katalogstruktur

```
framework/
  REGLER.md                 grundreglerna (lag)
  Build.java                bygget, körs med `java Build.java` (source launcher)
  modules/<modul>/src/      källkod för en modul, med module-info.java överst
  modules/<modul>/test/     modulens tester, i modulens egna paket, utan module-info.java
  modules/guardrails/fixtures/   medvetet felaktiga exempel för guardrails självtest
  modules/di/README.md      exempel för spola.di bredvid motsvarande Spring-kod
  modules/config/README.md  exempel för spola.config bredvid motsvarande Spring Boot-kod
  modules/util/README.md    exempel för spola.util bredvid motsvarande Lombok- och Spring Boot-kod
  out/                      byggresultat (ignoreras av git)
    classes/<modulnamn>/    kompilerade klasser per modul
    <modulnamn>.jar         en jar per modul (utan tester)
    test-classes/<modulnamn>/   modulen kompilerad igen tillsammans med sina tester
```

## Bygget (`Build.java`)

- **Compact source file** (Java 25) med `import module java.compiler` och
  `import module jdk.compiler`. Körs utan förkompilering och utan verktyg från tredje part.
- **Modullistan är explicit** i konstanten `MODULES` (`ModuleDir(namn, katalog, testingång)`).
  Ingen katalogscanning hittar moduler. En ny modul läggs till där, med sin testingång
  (`TestEntry(klass, metod)`, se "Tester" nedan). Varje modul har tester.
- **Beroendeordning:** bygget parsar varje `module-info.java` med `JavacTask.parse()` och läser
  `requires`. Namnet i filen måste stämma med listan. Modulerna sorteras topologiskt
  (djupet först, cykler ger fel). JDK-moduler påverkar inte ordningen.
- **Tidig kontroll av `requires`:** en `requires` som varken är en JDK-modul
  (`ModuleFinder.ofSystem()`) eller en listad modul stoppar bygget direkt med fil, rad och
  regel. Annars skulle javac fela med ett "module not found" som inte nämner regeln.
  Guardrails kontrollerar dessutom den kompilerade `module-info.class` (`DependencyCheck`).
  Regeln finns alltså på två ställen, med korsreferenser i kommentarerna; ändra båda.
- **Kompilering:** en javac-körning per modul via `javax.tools.ToolProvider`, i
  beroendeordning, med `--module-path out/classes` (redan byggda moduler) och
  `-d out/classes/<modul>`. Flaggor: `--release 25 -proc:none -Xlint:all,-deprecation,-removal
  -Xdoclint:all/protected -Werror`.
  - `-Werror` + lint: varningar är fel (kompileringsfel framför runtime-fel).
  - `-Xdoclint:all/protected` maskinverifierar REGLER §5 (Javadoc på publikt API).
  - `-deprecation` och `-removal` är avstängda så att `@Deprecated` (även
    `forRemoval = true`) på ett API aldrig gör bygget rött.
  - Symboliska länkar i en moduls källträd stoppar bygget innan kompileringen (se
    "Symboliska länkar" nedan).
- **Paketering:** en jar per modul med JDK:ns jar-verktyg
  (`java.util.spi.ToolProvider.findFirst("jar")`).
- **Guardrails körs från den nybyggda jar-filen:** bygget skapar ett `ModuleLayer` med
  `out/spola.guardrails.jar` och hämtar modulens `ToolProvider` (namn `spola-guardrails`)
  via `ServiceLoader`. Modulen deklarerar det explicit med
  `provides java.util.spi.ToolProvider with spola.guardrails.GuardrailsTool`. Inget
  reflektionsanrop och ingen ny JVM behövs, och artefakten som testas är den som levereras.
- **Ordning:** kompilera alla moduler, packa jar-filerna, kompilera alla moduler igen med
  sina tester (`out/test-classes/`), kör guardrails (som därmed också ser testkällorna och
  testklassernas bytekod), kör till sist testerna för alla moduler. Testerna för alla moduler
  körs även om en tidigare modul fick rött; bygget blir sedan rött och listar modulerna.
- Inga inkrementella byggen: `out/` raderas och byggs om varje gång.
- Exitkod 0 = grönt, 1 = rött.

## Modulen `spola.guardrails`

Publikt API (paket `spola.guardrails`):

| Typ | Roll |
|---|---|
| `Guardrails.check(Project, PrintStream)` | kör alla kontroller, returnerar sorterade `Violation` |
| `Project` | rot, output-katalog, undantagna kataloger, projektmoduler (namn → källkatalog), testkällor (namn → testkatalog) och vilka moduler testkällorna läser (`testReads`, i praktiken `spola.test`) |
| `Violation` | fil, rad (0 om ej tillämpligt), `Rule`, subjekt, plats i bytekoden |
| `Rule` | regel med avsnitt i `REGLER.md` och beskrivning |
| `GuardrailsTool` | `ToolProvider` + `main`: `check …` (självtestet är en del av modulens tester, se "Tester") |

Kontroller (paketprivata klasser):

| Kontroll | Hur | Regler |
|---|---|---|
| `LauncherCheck` | filer utan `.java`-ändelse: tillåtelselista (`Allowlist.DOCUMENT_EXTENSIONS`), dokument avkodade med varje `Charset` och parsade med en javac-task per release (från den körande JDK:ns ned till den äldsta javac tar); analyseras aldrig | varje fil som varken är `.java` eller `.md`; varje dokument som javac parsar i någon läsning vid någon release (`UNCHECKABLE_SOURCE`) |
| `SourceCheck` | `JavacTask.parse()` + `analyze()` (attribuering), `TreePathScanner`, `Trees.getElement` | annotering utanför tillåtelselistan (fullt kvalificerat namn efter attribuering, så en egen `Override` skiljs från `java.lang.Override`), annoteringar som inte går att lösa upp, `@interface`, import från annat än JDK/projekt, läsning av annoteringar via reflektion: anrop och method references till `java.base`-metoder vars returtyp (efter erasure, även arrayer) är `Annotation` eller `AnnotatedType`, samt `isAnnotationPresent` och `Method.getDefaultValue` |
| `BytecodeCheck` | `java.lang.classfile` | `Runtime(In)Visible{,Parameter,Type}Annotations` på klass, fält, metod, record-komponent och i `Code` (typannoteringar i metodkroppar), `AnnotationDefault`, nästlade annoteringar, klasser med `ACC_ANNOTATION` |
| `DependencyCheck` | `module-info.class` → `ModuleAttribute` | `requires` (även `static`/`transitive`) som varken är JDK-modul eller projektmodul |
| `FileCheck` | filträd inklusive länkar; innehållet i zip-filer (`java.util.zip`) | jar-arkiv utanför `out/`: efter namn (`.jar`, `.jmod`, `.war`, `.ear`, skiftlägesokänsligt) **och** efter innehåll (en zip-fil med vilket namn som helst, även med skript eller jmod-huvud framför, som innehåller en `.class`-post, en post som börjar med class-filens magiska tal, `META-INF/MANIFEST.MF`, ett arkiv efter namn eller en nästlad zip med något av detta); byggfiler från Maven, Gradle, Ant, Ivy, Bazel, Buck, Pants, Mill, sbt och Make (`pom.xml`, `build.gradle(.kts)`, `gradlew`, `mvnw`, `libs.versions.toml`, `build.xml`, `BUILD`, `BUCK`, `pants.toml`, `build.mill`, `Makefile`, …) samt alla filer under `.mvn/`, `.gradle/`, `.mill/`, `.bsp/`, filer som slutar på `.gradle`, `.gradle.kts`, `.sbt`, `.bzl` samt `project.clj` och `deps.edn`; varje symbolisk länk; en fil som börjar som zip (`PK\3\4`, `PK\5\6`) men som `ZipFile` inte kan öppna räknas som jar (fail-closed) |

Beslut:

- **Tillåtelselistan ligger i Java-kod** (`Allowlist`), inte inläst från `REGLER.md` vid
  körning. Självtestet läser tabellen i `REGLER.md` och kräver att mängderna är lika, så att
  de inte kan glida isär.
- **Okänd = otillåten:** en annotering eller import som inte går att lösa upp rapporteras,
  eftersom det inte går att bevisa att den är tillåten. Källanalysen körs utan class path.
- **Attribuering trots fel:** `--should-stop=ifError=FLOW` gör att javac attribuerar alla
  klasser även när en fixture innehåller fel (t.ex. olösbara importer).
- **Huvudkontrollen kräver felfri analys:** om javac rapporterar fel i huvudkontrollen
  avbryts den (exitkod 2) eftersom resultatet annars kunde vara ofullständigt.
- **Gruppering av källfiler:** alla listade moduler analyseras i en javac-task med
  modulspecifik `--module-source-path namn=katalog`. Övriga `.java`-filer (i dag bara
  `Build.java`) är launcher-program och analyseras en katalog i taget i den namnlösa
  modulen, så att två skript i olika kataloger får ha samma klassnamn. En `module-info.java`
  utanför de listade modulerna är ett fel med tydligt meddelande ("lägg till modulen i
  `MODULES`").
- **Vad som undantas:** bara katalogerna för de fixtures som står i `Fixtures.ALL`
  (`Fixtures.directories`, via `check --fixtures DIR`) undantas från käll-, bytekod- och
  beroendekontrollen, aldrig från filkontrollen. Allt annat under `fixtures/` (en lös
  `.java` direkt i katalogen, en olistad katalog) kontrolleras som vanligt. Självtestet
  ansvarar för allt i de undantagna katalogerna: varje fil där måste användas av sin fixture
  (`Fixture.inputs`, det enda stället som säger vilka filer en typ läser: `.java` för
  `SOURCE`/`BYTECODE`, `src/**.java` för `DEPENDENCIES`, `tree.txt` för `FILES`, allt utom
  `.java` för `LAUNCHER`; `LAUNCHER`-filer får vara vilka byte som helst, övriga måste vara
  UTF-8-text), och varje sådan fil kontrolleras med exakt angivna
  förväntningar. `out/` undantas från källkontrollen och är den enda plats där jar-arkiv får
  finnas.
- **Jar-arkiv känns igen på innehåll, inte bara namn.** En omdöpt jar (`.zip`, utan
  filändelse, med ett skript framför) är lika mycket vendorad kod. Zip-filer utan Java-kod
  (testdata) är tillåtna. `ZipFile` hittar centralkatalogen från filens slut, så data framför
  arkivet döljer det inte; nästlade arkiv läses med `ZipInputStream` upp till 8 nivåer, och
  djupare nästling räknas som Java-kod.
- **Filer utan `.java`-ändelse: tillåtelselista, inte igenkänning** (`LauncherCheck`,
  gauntlet-runda 5). Source launchern kör en fil med vilket namn som helst (`java --source N
  FIL`, `./FIL` med `#!`-rad), avkodad med standardteckenkodningen som `-Dfile.encoding`
  styr, och jshell kör vilken fil som helst. Tidigare rundor försökte känna igen Java på
  innehållet och hoppade över det som inte såg ut som Java; varje snävare test än
  launcherns (bara `\n` som radslut, strikt UTF-8, bara `--release 25`) blev en väg runt
  kontrollen. Nu är beslutet fail-closed:
  - Utanför `out/` och fixture-katalogerna får bara `.java`-källor och dokument (`.md`,
    `Allowlist.DOCUMENT_EXTENSIONS`) finnas. Varje annan fil är `UNCHECKABLE_SOURCE`, vad
    den än innehåller. Det enda predikatet för urvalet är `FileTree.isLauncherCandidate`
    (används av huvudkontrollen och av `Fixture.inputs` för `LAUNCHER`).
  - Ett dokument läses som launchern kan läsa det: avkodat med **varje** teckenkodning i
    JDK:n (felaktiga byte ersätts, som launcherns `InputStreamReader`), en `#!`-rad tömd
    fram till första `\r`, `\n` eller `\r\n` (som `BufferedReader.readLine`, efter de två
    byten `#!`), och parsat vid varje release från den körande JDK:ns (`--source N`,
    `--enable-preview` bara för den senaste, som launchern) nedåt tills javac avvisar
    releasen med `compiler.err.option.removed.source` (i JDK 25: 7). Den äldsta releasen
    är alltså javacs egen och inte ett tal i koden; vilket annat fel som helst som inte hör
    till en enskild läsning stoppar kontrollen (fail-closed). Alla öppna läsningar parsas i
    en javac-task per release (`-Xmaxerrs` högt så att varje enhet rapporterar sina egna
    fel); när en läsning av ett dokument parsar tas dokumentets övriga läsningar bort.
  - Läsningar utan `;`, `{` och `\` hoppas över utan parsning: en kompileringsenhet med
    någon deklaration eller import har ett semikolon eller en klammer (eventuellt som
    unicode-escape).
  - **Dokument analyseras aldrig som Java** (domarens bindande beslut, gauntlet-runda 5 i
    körningen 20261001-2101). Parsar någon läsning, vid någon release, rapporteras
    dokumentet som `UNCHECKABLE_SOURCE` med den första läsning som hittades
    ("read as UTF-8, --source 25"). Parsar ingen läsning är dokumentet inte Java och
    godkänns. Skälet: vad ett program betyder beror på teckenkodning, release och API-nivå
    (`java --source 8` binder överlagringar mot release 8:s API), så en analys av en
    läsning säger inget säkert om den som körs. Java hör inte hemma i dokument.
  - Kvar: jshell-indata i ett `.md`-dokument (se PROGRESS.md).
  - Gränsen: det JDK:n kör eller laddar direkt kontrolleras maskinellt. Innehåll som först
    måste packas upp eller avkodas (gzip, tar, base64, …) gör det inte (se PROGRESS.md).
- **Class-filer känns igen på innehåll:** bytekodkontrollen läser varje fil som slutar på
  `.class` *eller* börjar med `CAFEBABE`, så en omdöpt class-fil kontrolleras också.
- **Symboliska länkar är förbjudna** (regel `SYMBOLIC_LINK`, REGLER §1). Ingen kontroll
  följer länkar, så det som en länk pekar på (en jar utanför repot, en `.java` med otillåtna
  annoteringar) skulle annars passera okontrollerat. Filkontrollen listar därför alla
  vanliga filer *och* länkar (`FileTree.filesAndLinks`) och rapporterar varje länk, även
  länkar till kataloger och trasiga länkar. `Build.java` vägrar dessutom kompilera en modul
  vars källträd innehåller en länk, så att ingen länkad fil hamnar i en jar. Alternativet,
  att följa länkar, skulle kräva cykeldetektering och kontroll av filer utanför repot.
- **Oläsbara `.class`-filer** är ett analysfel med filnamn (exitkod 2), inte en tyst lucka.
- **Tillåtelselistan mot REGLER.md:** självtestet läser tabellen med ett reguljärt uttryck
  (`| `namn` | SOURCE/CLASS/RUNTIME |`). Ändras tabellens format blir självtestet rött och
  felmeddelandet pekar på `ALLOWLIST_ROW` i `GuardrailsTests.java`.
- **Utskrift i ASCII:** regelavsnitt skrivs som `REGLER.md 2. Annoteringar` (inte `§`) så att
  felen går att läsa i terminaler utan UTF-8.

Felformat: `fil[:rad]: REGEL (REGLER.md avsnitt): subjekt [at plats] - beskrivning`, t.ex.

```
modules/x/src/spola/x/Marker.java:3: ANNOTATION_DECLARATION (REGLER.md 2. Annoteringar): @interface spola.x.Marker - custom annotation types (@interface) are forbidden
```

## Självtest och fixtures

Självtestet ligger sedan uppdrag 02 i modulens testkällor
(`modules/guardrails/test/spola/guardrails/GuardrailsTests.java`, tillsammans med `TreeSpec`)
och körs av `spola.test` som vilka tester som helst: ett test per kontroll och ett
parametriserat test med ett fall per fixture. `GuardrailsTool` har inget `selftest`-kommando
längre. Testerna läser `modules/guardrails/fixtures` och `REGLER.md` relativt arbetskatalogen,
som är `framework/` när bygget kör dem.

- `Fixtures.ALL` listar explicit varje fixture: namn (katalog under
  `modules/guardrails/fixtures/`), typ och **exakt** vilka överträdelser den ska ge (fil, rad,
  regel, subjekt, plats). Självtestet jämför som multimängder: både saknade och oväntade
  överträdelser ger rött bygge. En fixture utan förväntade överträdelser är också ett fel,
  liksom en fixture-katalog som inte finns i listan, en fil direkt i `fixtures/` och en fil
  i en fixture-katalog som fixturen inte använder.
- Typer: `SOURCE` (källkontroll direkt på filerna), `BYTECODE` (källkontroll, sedan
  kompilering till en temporär katalog och bytekodkontroll), `DEPENDENCIES` (källkontroll av
  `src/`, sedan kompilering som moduler och beroende- och bytekodkontroll), `FILES` (varje
  rad i `tree.txt` skapas i en temporär katalog, filkontroll; en `tree.txt` som javac parsar
  som Java i någon läsning ger en oväntad överträdelse), `LAUNCHER` (samma urval och
  kontroll av filer utan `.java`-ändelse som huvudkontrollen, `Guardrails.checkOtherFiles`).
  `tree.txt` (formatet i `TreeSpec`) kan beskriva tomma filer, kataloger (`dir/`),
  symboliska länkar (`sökväg -> mål`) och genererat innehåll (`sökväg = zip(post, post =
  class, ...)`, `jmod(...)`, `script+zip(...)`, `truncated-zip(...)`, `class`), så att inga
  riktiga arkiv eller class-filer checkas in.
- Självtestet testar också sig självt: `layoutProblems` körs mot ett temporärt träd med
  lösa filer av alla slag, och `Guardrails.check` körs mot ett temporärt projekt med en
  `@interface` direkt under `fixtures/`, i en olistad katalog (båda ska rapporteras) och i
  en listad fixture-katalog (ska inte rapporteras).
- Radnumren i `Fixtures.ALL` gäller fixture-filerna som de är. Ändras en fixture-fil ska
  numren uppdateras i samma ändring; självtestet skriver ut de faktiska raderna.
- **Inga riktiga `.jar`- eller byggfiler i repot, inte ens som fixtures.** Fixtures för
  dessa beskrivs i `tree.txt` och skapas först i en temporär katalog. Därför håller
  acceptanskriteriet "inga jar-/byggfiler i repot" och filkontrollen kan köras utan
  undantag för fixtures.
- Fixtures kompileras aldrig in i någon artefakt; temporära kataloger raderas efter varje
  fixture.
- Fixtures är den enda platsen i repot där kod medvetet bryter mot reglerna (t.ex.
  `@interface`, import av `org.junit…`, modulen `foo.bar`). De är undantagna från
  huvudkontrollen enligt uppdrag 01 och ingår inte i något bygge.

Fixtures i dag: `source-annotation`, `annotation-declaration`, `bytecode-annotation`,
`foreign-requires`, `foreign-import`, `reflective-annotation-read`, `launcher-source`,
`foreign-jar`, `foreign-build-file`, `symbolic-link`.

Lägga till en fixture: skapa katalogen, lägg till en `Fixture` i `Fixtures.ALL` med de exakta
förväntade överträdelserna och kör `java Build.java`.

## Tester (`spola.test`)

Uppdrag 02. Ersätter JUnit utan annoteringar: tester är värden som byggs med vanliga
metodanrop, och varje moduls testingång står explicit i `Build.java`.

### API (paket `spola.test`)

| Typ | Roll |
|---|---|
| `Tests` | fabriker: `test(namn, () -> ...)`, `suite(namn, barn...)`, `parameterized(namn, indata, [etikett,] fall -> ...)` |
| `Test` | sealed: `TestCase` eller `Suite` |
| `TestCase` | record: namn, `Body`, egen timeout, skip-orsak; `.timeout(d)`, `.skip(orsak)` |
| `Suite` | record: namn, barn, `beforeAll`/`beforeEach`/`afterEach`/`afterAll`, timeout, skip-orsak; varje metod ger en ny svit |
| `Body`, `CaseBody<T>` | funktionella gränssnitt som får kasta `Exception` |
| `Assertions` | likhet (`assertEquals`, med tolerans för `double`, `assertNotEquals`, `assertSame`), sanningsvärde, null, undantag (`assertThrows` med typ och ev. exakt meddelande), samlingar (`assertSize`, `assertEmpty`, `assertContains`, `assertInOrder`, `assertAnyOrder`), `fail` |
| `AssertionFailure` | `AssertionError` med `expected()`/`actual()`; meddelandet visar båda på egna rader |
| `Runner` | `Runner.run(suite, PrintStream)` → `Report`; skriver rapporten |
| `Report`, `Result`, `Status` | resultat per test och per misslyckad svitfixture, räknare, `success()` |
| `TestTool` | `ToolProvider` `spola-test`: `--module M --class C --method m` |

Beslut:

- **Tester är värden, inga annoteringar.** En svit är ett träd av records. Namn är unika
  bland syskon, inte tomma och utan kontrolltecken (kontrolleras när trädet byggs), så att
  varje rapportrad pekar ut exakt ett test.
- **Parametrisering är en svit:** `parameterized` ger en `Suite` med ett `TestCase` per
  indata, namngivna `[i] etikett` (från 1, kontrolltecken visas escapade). Varje fall
  rapporteras för sig och sviten tar fixtures och timeout som vilken svit som helst.
- **Fixtures och städgaranti** (semantiken står i Javadoc för `Suite`):
  `beforeEach` körs yttersta sviten först, `afterEach` innersta först; inom en svit i den
  ordning de lades till. Varje `afterEach` körs efter varje startat test, även när en
  `beforeEach`, testet eller en annan `afterEach` fallerade (som JUnit 5; städkod får alltså
  tåla en setup som inte blev klar). Det första felet blir testets fel, senare läggs till som
  `suppressed`. Varje `afterAll` körs även när en `beforeAll` eller ett test fallerade.
- **Hoppat över (`SKIPPED`):** ett test eller en svit med `.skip(orsak)` körs inte (inga
  fixtures heller); fallerar en `beforeAll` körs inte resten av sviten, alla dess tester
  rapporteras som hoppade över med orsaken, och svitens `afterAll` körs. Den misslyckade
  svitfixturen rapporteras som ett eget fel (`Result.Kind.SUITE_FIXTURE`).
- **Grönt** betyder: minst ett test passerade och inget test eller svitfixture fallerade.
  En tom svit, eller en där alla tester hoppades över, är röd: en testingång som inte
  testar något ska inte se grön ut.
- **Timeout per test:** testets egen, annars närmaste omgivande svits, annars
  `Runner.DEFAULT_TIMEOUT` (60 s). Timeouten gäller hela testet (`beforeEach`, kropp,
  `afterEach`) och varje `beforeAll`/`afterAll` för sig. Varje test och svitfixture körs på en
  ny plattformstråd (daemon); köraren väntar högst timeouten, avbryter sedan tråden
  (`interrupt`) och väntar högst `GRACE` (1 s) till. Stannar tråden har testets städning körts
  innan nästa test börjar; annars överges tråden och körningen fortsätter (testets
  `afterEach` körs då bara om tråden någon gång blir klar). Felet (`TimedOut`) får trådens
  stackspår från när tiden gick ut, så att rapporten visar var testet hängde.
  Plattformstrådar, inte virtuella: en virtuell tråd som snurrar utan att blockera behåller sin
  bärartråd, och tillräckligt många sådana skulle svälta alla senare tester.
- **Rapport till stdout:** sviter som rubriker indenterade efter djup, en rad per test
  (`ok`, `FAILED`, `skipped ... - orsak`) med tid, sedan `FAILURES (n):` med fullständigt
  namn (`svit > svit > test`) och stackspår för varje fel, sist en sammanfattning:
  `namn: OK|FAILED - N tests, a ok, b failed, c skipped, d suite fixtures failed, in t ms`.
- **Felmeddelanden** visar förväntat och faktiskt värde på egna rader. Strängar visas i
  citattecken med escapes, arrayer och samlingar element för element; läses två olika
  värden likadant (`1` och `1L`) visas deras klasser, även element för element i samlingar
  (`[1 (java.lang.Integer)]`). Samlingar som innehåller varandra visas med `(cycle)` där de
  upprepas. Likhet är `Objects.deepEquals`.
- **`assertNotEquals` jämför boxade tal efter värde.** `Integer` är aldrig lika med `Long`,
  så `assertNotEquals(0, someLong)` skulle alltid passera (falskt grönt). Två boxade
  primitiva tal av olika typ (`Byte`…`Double`) räknas därför som lika när de har samma
  exakta värde (`float` vidgas förlustfritt till `double`, jämförs via `BigDecimal`).
  Primitiva överlagringar (`assertNotEquals(long, long)` som i JUnit) valdes bort: de gör
  anrop som `assertEquals(1, integerVariabel)` tvetydiga och kastar `NullPointerException`
  vid `null`. `assertEquals(0, 0L)` fallerar redan (säkert håll) och visar klasserna.
- **Fail closed vid `System.exit`.** Testerna körs i byggets JVM. Ett test som anropar
  `System.exit(0)` skulle annars avsluta bygget med 0 utan rapport. Två shutdown hooks:
  `TestTool` registrerar en från det att testingången anropas tills körningen är slut (så
  att även `System.exit` i ingångsmetoden eller en statisk initierare fångas). Den skriver
  vilken testingång som kördes (i `PrintWriter`-överlagringen kopieras den buffrade
  utskriften till anroparens writers först) och stoppar JVM:en med `Runtime.halt(3)`
  (`TestTool.EXIT_DURING_RUN`). Den finns så att verktyget är fail closed även utan bygget.
  `Build.java` har en under hela bygget som stoppar med 1 om `main` inte har bestämt
  exitkoden. Har `main` bestämt koden gör den ingenting: övriga shutdown hooks och JDK:ns
  `deleteOnExit`-städning ska få gå klart (en `halt` där skulle tyst skippa dem vid varje
  bygge). En sen `System.exit` från en övergiven testtråd kan ändå inte ändra koden, eftersom
  ytterligare `System.exit`-anrop blockerar när nedstängningen väl har börjat. Om ett test
  avslutar JVM:en under bygget går båda hookarna, och den som hinner först bestämmer koden (1
  eller 3); båda är röda. Testat i barn-JVM:ar: `ExitProbe`/`ToolTests` för `TestTool` (i
  ett test, i ingångsmetoden, via `PrintWriter`-överlagringen) och `checkExitGuard` i
  `Build.java` (`java Build.java --exit-guard-probe ...`), som efter testerna kontrollerar
  att `System.exit(0)` efter beslutet ger 0 och låter en annan hook och `deleteOnExit` gå,
  och att `System.exit(0)` före beslutet ger 1. Barn-JVM per modul valdes bort:
  långsammare, och skyddet mot `Runtime.halt(0)`, det enda hooks inte fångar, är ingen
  rimlig risk i god tro.
- **Testkällor hör till modulen.** `modules/<m>/test/` har samma paket som modulen och ingen
  `module-info.java`, så testerna når paketprivat kod. Bygget kompilerar `src` + `test`
  tillsammans till `out/test-classes/<m>` med `--add-modules spola.test --add-reads
  <m>=spola.test`; modulens `module-info.java` nämner aldrig `spola.test`, och den jar som
  levereras (från `out/classes`) innehåller inga tester. `-Xlint:-exports` gäller bara
  testkompileringen: testingången är publik i ett exporterat paket och returnerar
  `spola.test.Suite` utan `requires transitive`, vilket är meningen för testkod.
- **Guardrails ser testkällorna** som en del av modulen (`--tests m=dir`, `--test-reads
  spola.test` → `--module-source-path m=src:test` och `--add-reads` i analysen), och
  bytekoden i `out/test-classes` kontrolleras som all annan bytekod.
- **Testingången är explicit** i `Build.java` (`TestEntry("spola.x.XTests", "suite")`).
  Bygget laddar per modul ett nytt `ModuleLayer` med `out/test-classes/<m>`, `spola.test`
  och de andra modulernas jar-filer, ger samma läsbarhet som vid kompileringen
  (`Controller.addReads`) och exporterar ingångens paket till `spola.test`
  (`Controller.addExports`). Därefter hämtas `spola-test` via `ServiceLoader` (som
  guardrails) och körs med `--module m --class C --method suite`.
- **Det enda reflektionsanropet:** `TestTool` slår upp klassen med `Class.forName(Module,
  String)` och anropar metoden med `Method.invoke`. Det går inte att undvika: bygget
  kompilerar ingången i samma körning och kan inte anropa den direkt. Metoden måste vara
  `public static Suite m()`; annat ger exitkod 2 med orsak. Inga annoteringar läses.
- **Arbetskatalog:** testerna körs med `framework/` som arbetskatalog (bygget kräver det),
  så tester får läsa filer relativt den (guardrails läser `REGLER.md` och fixtures).
- **Namnkrock:** en testklass med ingången `suite()` skuggar den statiskt importerade
  `Tests.suite(...)`; skriv då `Tests.suite(...)` i klassen (som `GuardrailsTests` och
  `RunnerTests` gör). Hjälpklasser för delsviter använder `tests()`.
- **Körarens egna tester** (`modules/test/test/`) kör små sviter med en inre `Runner` och
  kontrollerar `Report` och utskriften: assertions, fixtures (ordning, städning när testet
  kastar efter att en fixture öppnat en fil, `beforeAll`-fel), parametrisering (tre fall
  varav ett fel ger två ok och ett fel), timeout (sovande och avbrottsokänsliga tester,
  ärvd timeout), nästlade sviter, rapporten och `TestTool`.

## DI-containern (`spola.di`)

Uppdrag 03. Ersätter Spring Core. Modulen har inga `requires` (bara `java.base`); testerna
läser `spola.test` som alla andra moduler. Exempel bredvid Spring: `modules/di/README.md`.

### API (paket `spola.di`)

| Typ | Roll |
|---|---|
| `Key<T>` | record: typ + valfri kvalificerare (vilket värde som helst med värdebaserad `equals`: enum, record, sträng; inte array, inte primitiv typ) |
| `ContainerModule` | funktionellt gränssnitt `configure(Binder)`: en återanvändbar grupp bindningar |
| `Binder` | `bind(Class)`/`bind(Key)` → `BindingBuilder`, `install(module)`; bara `Container.create` skapar den, oanvändbar efteråt |
| `BindingBuilder<T>` | `to(...)`: typad form (0–5 beroenden som `Class` eller `Key`), resolverform `to(Dependencies, Factory)`, `toInstance(T)` |
| `Binding` | `in(Scope)`, högst en gång |
| `Scope` | `SINGLETON` (lat, standard), `EAGER_SINGLETON`, `PROTOTYPE`, `CALL` |
| `Factory<T>` | `create(Resolver) throws Exception`; `Factory.Of0`–`Of5` för den typade formen |
| `Resolver` | `get(Key)`/`get(Class)`: bara deklarerade beroenden, bara medan fabriken körs |
| `Dependencies` | deklarationen för resolverformen: `of(Class...)`, `of(Key...)`, `and(...)`, `none()` |
| `Container` | `create(modules...)`, `get`, `runInCallScope`, `callInCallScope`, `close` |
| `WiringException` | grafen är ogiltig; `problems()` listar alla fel; inget har skapats |
| `ResolutionException` | något kunde inte hämtas eller skapas; meddelandet börjar med kedjan `cannot resolve A -> B: ...` |
| `CloseException` | en eller flera `close` misslyckades; felen är `suppressed`, i stängningsordning |

### Beslut

- **Hur beroenden deklareras (kravet "svårt att deklarera fel").** Valideringen utan
  reflektion kräver att varje bindning säger vad den behöver. Två former:
  - *Typad form, den föredragna:* `to(OrderRepo.class, Clock.class, OrderService::new)`.
    Nycklarna är fabrikens argument: containern hämtar dem och anropar fabriken med dem.
    Fabriken får ingen resolver och når alltså inget annat, och kompilatorn kontrollerar att
    varje nyckels typ passar parametern. Deklarationen och användningen är samma uttryck och
    kan inte glida isär. Glömmer man ett beroende kompilerar det inte (konstruktorn får för få
    argument). Gränsen är fem beroenden; fler är i regel ett tecken på en klass som bör delas.
  - *Resolverform,* som uppdragets exempel: `to(Dependencies.of(OrderRepo.class), r -> new
    OrderService(r.get(OrderRepo.class)))`. Här kan deklarationen och användningen skilja
    sig, så resolvern är begränsad: den lämnar bara ut deklarerade nycklar (annars
    `ResolutionException` med det deklarerade i meddelandet) och bara medan fabriken körs
    (en sparad resolver ger fel). Att be om något odeklarerat upptäcks därför första gången
    komponenten skapas, inte vid `create`; det är priset för formen, och skälet till att den
    typade formen föredras. Att deklarera för mycket är ofarligt (strängare kontroll).
  - Dessutom vägrar `Container.get` att köras inifrån en fabrik i samma container (en
    `ScopedValue` är bunden medan fabriken körs), så att en fabrik som fångat containern inte
    går förbi deklarationen.
  - Alternativ som valdes bort: att köra fabrikerna med en inspelande resolver vid `create`
    (skapar objekt och kör sidoeffekter innan grafen är godkänd), kodgenerering (utanför
    scope) och reflektion över konstruktorer (förbjudet).
- **Validering vid `create`,** innan någon fabrik körs, på de deklarerade beroendena. Alla
  fel samlas i en `WiringException`, i ordningen: dubbla bindningar, bindningar utan mål,
  saknade beroenden (vem saknar vad, och andra bindningar av samma typ som ledtråd), cykler
  (hela kedjan, `A -> B -> C -> A`), singletons som fångar `CALL`-komponenter. Varje
  bindning minns var `bind` anropades (`StackWalker`, bara klass-, metod- och filnamn och
  rad; ingen `Class`-referens, ingen reflektion), och felen visar det.
- **Cykelsökning:** djupet först från varje bindning i bindningsordning; ett beroende som
  redan ligger på stigen sluter en cykel, som rapporteras från där den gick in i stigen. Varje
  nod avslutas en gång, så varje cykel rapporteras en gång och varje cyklisk del av grafen
  ger minst en rapport (alla elementära cykler räknas inte upp).
- **Fångade anropsscopade objekt (captive dependency):** en singleton (lat eller ivrig) som
  beror på en `CALL`-komponent, direkt eller via prototyper, skulle behålla instansen från
  det första anropsscopet. Det är ett fel vid `create`. En singleton → singleton → `CALL`
  rapporteras på den inre singletonen.
- **Trådsäkra singletons:** en `Slot` per singleton med dubbelkontrollerad låsning (volatile
  fält + `ReentrantLock`). Virtuella trådar som väntar på låset lämnar sin bärartråd. Tråden
  håller låset medan fabriken körs och kan då ta beroendenas lås; det kan inte låsa sig
  eftersom grafen är acyklisk och fabriker bara når deklarerade beroenden. Kastar fabriken
  sparas inget och nästa `get` försöker igen. Samma `Slot` används för `CALL`-instanser
  inom ett scope (trådar som forkas med `StructuredTaskScope` delar scopet).
- **Anropsscope:** varje container har en egen `ScopedValue<CallScope>`;
  `runInCallScope`/`callInCallScope` binder ett nytt scope (instanser + egen livscykel) och
  stänger dess `AutoCloseable`-instanser i omvänd ordning när kroppen är klar. Kastade
  kroppen läggs stängningsfelen till som `suppressed`. Nästlade scope är oberoende. Utanför
  ett scope ger en `CALL`-komponent `ResolutionException`.
- **Livscykel:** en instans räknas som skapad när dess fabrik returnerar, så dess beroenden
  är skapade före den och stängs efter den. `close()` stänger i omvänd skapandeordning, fångar
  varje fel (alla `Throwable`) och fortsätter, och kastar sedan en `CloseException` med felen
  som `suppressed`. Upprepad `close()` gör inget; `get` efter `close` ger
  `IllegalStateException`. Blir en lat singleton klar efter att containern stängts stängs den
  direkt och anroparen får ett fel i stället för ett stängt objekt. Misslyckas en ivrig
  singleton i `create` stängs det som redan skapats.
- **Vad containern inte stänger:** prototyper (containern vet inte när anroparen är klar, och
  att hålla dem skulle läcka minne; som i Spring och Guice) och instanser från `toInstance`
  (den som skapade dem äger dem).
- **Moduler:** en `ContainerModule` installeras högst en gång per container (likhet med
  `equals`, så en record-modul som installeras från två ställen körs en gång, och rekursiv
  installation tar slut). Bindningar av samma nyckel från två moduler är ett fel, inte en
  överskrivning: överskrivning är magi som döljer misstag.
- **Ingen fallback mellan nycklar:** `Key.of(T.class)` och `Key.of(T.class, q)` är olika
  nycklar, och en bindning av en subtyp gäller inte för supertypen. Det som binds är det som
  hämtas.
- **Fabriker får kasta checked exceptions** (`throws Exception`), så att konstruktorer som
  kastar `IOException` kan användas som method references. Containern lindar in felet i
  `ResolutionException` med kedjan och felet som `cause`; ett `ResolutionException` från ett
  djupare beroende släpps igenom oförändrat (det har redan den längre kedjan).
- **Nyckelnamn i meddelanden** är typens kanoniska namn (binärt namn för lokala och anonyma
  klasser), så två typer med samma enkla namn inte kan förväxlas.

## Konfiguration (`spola.config`)

Uppdrag 04. Ersätter Spring Boots `application.properties`, `@Value`,
`@ConfigurationProperties` och profiler. Modulen har inga `requires` (bara `java.base`), och
testerna läser `spola.test`. Exempel bredvid Spring Boot: `modules/config/README.md`.

### API (paket `spola.config`)

| Typ | Roll |
|---|---|
| `Source` | en källa, skapad explicit: `map`, `file`, `optionalFile`, `environment(prefix[, env])`, `environmentTable(tabell[, env])`, `systemProperties`, `arguments`; `description()` visas i fel och dump |
| `Config.Builder` | `add(Source)`, `activeProfiles(...)`/`activeProfilesFrom(nyckel)`, `addForEachProfile(profil -> Source)`, `sensitive(nycklar)`/`sensitive(predikat)`, `build()` |
| `Config` | oföränderlig: `get`, `sourceOf`, `keys`, `profiles`, `isSensitive`, `describe()`, `bind(prefix, funktion)` |
| `Section` | det bindningsfunktionen läser från: `string`, `intValue`, `longValue`, `booleanValue`, `doubleValue`, `duration`, `uri`, `path`, `enumValue`, `list`, `optional`, `value(nyckel, Converter)`, `section(delprefix, funktion)`, `has` |
| `Converter<T>` | record: typnamn + `Function<String,T>`; inbyggda konstanter (`INT`, `DURATION`, …) och `forEnum(Class)` |
| `ConfigException` | alla problem på en gång; `problems()` |
| `ConfigProblem` | sealed: `SourceError`, `PlaceholderError`, `MissingKey`, `InvalidValue` |

### Beslut

- **Prioritetsordning = ordningen i koden.** Källorna läggs till med `add` och en senare källa
  vinner för samma nyckel. Ingen ordning är inbyggd; README rekommenderar standardvärden, fil,
  profilfiler, miljö, argument (samma som Spring Boot i stora drag). Källor läses vid
  `build()`, inte när de skapas (så en `Source` kan skapas tidigt och testas med injicerade
  `Map`:ar), och `build()` läser om dem varje gång.
- **Miljövariabler: ingen "relaxed binding".** Två explicita former. (a) Tabell:
  `Source.environmentTable(Map.of("DATABASE_URL", "db.url"))`, bara listade variabler läses.
  (b) Prefix: `Source.environment("APP_")`, där regeln är exakt: variabelnamnet måste börja
  med prefixet (skiftlägeskänsligt), prefixet tas bort, resten görs till gemener
  (`Locale.ROOT`) och `_` blir `.`. Variabler utan prefixet läses inte; en variabel som är
  exakt prefixet ignoreras; ett tomt prefix vägras (det vore att läsa hela miljön). Två
  variabler som ger samma nyckel är ett fel i stället för att en av dem tyst vinner. Båda
  formerna tar valfritt en `Map` som ersätter processens miljö, så tester aldrig beror på den
  riktiga miljön. Skäl: Spring Boots relaxed binding gör att flera stavningar träffar samma
  fält, och vilken som vinner går inte att se i koden.
- **Profilernas plats i prioritetsordningen:** där `addForEachProfile` anropas. Profilkällorna
  vinner över allt som lagts till före anropet och förlorar mot allt efter. Inom anropet läggs
  en källa per aktiv profil i profilernas ordning, så en senare profil vinner. Funktionen från
  profilnamn till källa skrivs av användaren; inga filnamn är inbyggda. Valt framför en fast
  plats (t.ex. "direkt efter basfilen") eftersom en fast plats vore en dold regel; placeringen
  syns nu i koden. `addForEachProfile` får anropas flera gånger (t.ex. en fil och en map per
  profil).
- **Aktiva profiler** anges en gång: i koden (`activeProfiles`) eller från en nyckel
  (`activeProfilesFrom`). Nyckeln slås upp i de vanliga källorna (`add`) sammanslagna med
  vanlig prioritet, oavsett var i kedjan de står, så `--app.profiles=dev` (argumenten läggs
  sist) fungerar. Profilkällor kan inte aktivera profiler: en profilkälla som sätter nyckeln
  är ett fel (annars vore resultatet beroende av ordningen på en dold andra omgång).
  Platshållare i profilnyckelns värde ersätts inte. Profilnamn är `[A-Za-z0-9][A-Za-z0-9_.-]*`,
  så ett namn från ett argument inte kan bli `../` i användarens sökvägsfunktion.
- **Filer:** `java.util.Properties`-format, läst som UTF-8 via `Reader` (felaktig UTF-8 ger
  fel i stället för ersättningstecken). En valfri fil som inte finns (`NoSuchFileException`)
  ignoreras; en valfri fil som finns men inte går att läsa är fortfarande ett fel. Sökvägen
  görs absolut och står i källans beskrivning och i felet.
- **Argument:** `--nyckel=värde`; argument utan `--` lämnas åt applikationen, `--` avslutar.
  `--nyckel` utan `=` är ett fel (en flagga utan värde är tvetydig: tom sträng eller `true`?).
- **Platshållare** ersätts efter sammanslagningen, en gång per nyckel, i nyckelordning (så
  resultatet är deterministiskt). `${nyckel:standard}` använder standardvärdet bara om ingen
  källa har nyckeln; finns nyckeln men går den inte att ersätta är det ett fel, ingen
  fallback. Ett bokstavligt `${` skrivs `$${` (samma idé som `$$` i Make och Compose).
  Cykler upptäcks med en stack av nycklar under rekursionen och rapporteras en gång med hela
  kedjan (`a -> b -> c -> a`); nycklar som beror på en nyckel som redan fallerat fallerar utan
  eget fel, så varje grundorsak rapporteras en gång. Alla problem från källor, profiler och
  platshållare samlas och `build()` kastar ett `ConfigException` med alla.
- **Bindning utan reflektion:** `config.bind(prefix, c -> new Rec(c.string("a"), ...))`.
  Nästlade records med `c.section("delprefix", ...)`, samma mekanism och samma felrapport.
  Omvandlingar är `Converter<T>`-värden (typnamn + funktion); enum via `Class<E>` och
  `Enum.valueOf`. Typnamnet för en enum tas från `Class.getSimpleName()`, som bara läser
  klassens namn (inget i `java.lang.reflect`, inga medlemmar, inga annoteringar).
- **Hur alla fel samlas i en funktion som returnerar ett record:** läsmetoderna kastar inte.
  Ett saknat eller ogiltigt värde registreras i en lista som hör till bindningen
  (`MissingKey`/`InvalidValue` med nyckel, förväntad typ och, för ogiltiga värden, källa) och
  metoden returnerar en ersättare (`null`, `0`, `false`, tom lista/`Optional`, eller angivet
  standardvärde). När funktionen returnerat kastar `bind` ett `ConfigException` med hela
  listan. Kastar funktionen själv (t.ex. en record-konstruktor som vägrar en ersättar-`null`)
  efter att problem registrerats, kastas `ConfigException` och funktionens undantag släpps:
  det är troligen en följd av ersättaren, och dess meddelande kan innehålla värden (även
  känsliga). Har inga problem registrerats släpps funktionens undantag igenom oförändrat.
  En `Section` går inte att använda efter att bindningen är klar (`IllegalStateException`),
  så en sparad referens inte kan läsa förbi felinsamlingen. Ett `bind`-anrop är en rapport;
  för en rapport för hela applikationen binds ett rotrecord med `section` för delarna.
  Valt framför alternativen: ett resultatobjekt per värde (`Result<T>`) gör varje
  record-konstruktion till kombinatorkod, och att köra funktionen två gånger (först för att
  samla nycklar) kör användarkod med påhittade värden två gånger.
- **Hemligheter:** nycklar markeras med en lista eller ett predikat. Ett känsligt värde visas
  som `******` i `describe()`, `toString()` och alla felmeddelanden; för ett ogiltigt känsligt
  värde utelämnas också omvandlarens meddelande (t.ex. `For input string: "..."`), och inget
  undantag med värdet hängs på som `cause`. Platshållarfel i ett känsligt värde visar inte
  platshållarnamnet (det kan vara en del av hemligheten). Ett värde som byggs av ett känsligt
  värde via en platshållare blir självt känsligt ("taint"), så `url=jdbc:...?pw=${db.password}`
  inte läcker i dumpen. Nycklar är aldrig hemliga.
- **Oföränderlig och trådsäker:** `Config` har bara `final`-fält med en oföränderlig kopia
  (`TreeMap` i `unmodifiableMap`) av records; säker publicering via `final`. En `Section` hör
  till en bindning; dess fellista är synkroniserad om funktionen själv använder flera trådar.

## Util del 1: loggning och hjälpare (`spola.util`)

Uppdrag 05a. Ersätter Lomboks `@Slf4j`/`@Log`, `@Getter(lazy = true)`, `@SneakyThrows` och
`@ToString.Exclude`, samt Spring Boots konsolformat och `logging.level.*`. Modulen kräver bara
`java.logging` (för konsolformateringen) och inga andra Spola-moduler; testerna läser
`spola.test`. Exempel bredvid Lombok och Spring Boot: `modules/util/README.md`.

**Uppdelning av rad 05 i `PLAN.md`:** rad 05 (util) är delad i 05a (detta: loggning och
hjälpare) och 05b (builders och with-metoder för records). 05b kräver ett eget designbeslut
(utan annoteringar och utan runtime-reflektion återstår i praktiken genererad, incheckad
källkod) och ryms inte här.

### API (paket `spola.util`)

| Typ | Roll |
|---|---|
| `Log` | `forClass(Class)`, `of(System.Logger)`, `defer(Supplier)`; `trace/debug/info/warn/error` med `(String)`, `(String, Object)`, `(String, Object, Object)`, `(String, Object...)`, `(Supplier<String>)`, `(Supplier<String>, Throwable)`; `isXxxEnabled()` |
| `Log.Deferred` | ett lat argument (från `Log.defer`) |
| `Logging` | `configure(LoggingSettings)`, `reset()` |
| `LoggingSettings` | record: nivå per prefix, utström, tidszon; `defaults()`, `withLevel`, `withLevels`, `withOutput`, `withZone` |
| `Lazy<T>` | `of(Supplier)`, `get()`, `isComputed()`; implementerar `Supplier<T>` |
| `Unchecked` | `run`, `get`, `runnable`, `supplier`, `function`, `consumer`, `wrap(Exception)` |
| `ThrowingRunnable/Supplier/Function/Consumer` | funktionella gränssnitt med `throws Exception` |
| `UncheckedException` | inslaget checked undantag; `getCause()` är originalet |
| `ToString` | `of(namn)`, `add(namn, värde)`, `secret(namn, värde)`, `toString()` |

Paketprivat: `MessageFormatter` (platshållarna), `LineFormatter` och `LineHandler`
(konsolformatet).

### Beslut

- **`Log` ovanpå `System.Logger`, skapad explicit.** `Log.forClass(X.class)` är
  `System.getLogger(X.class.getName())`; ingen stackinspektion. `System.getLogger` är i sig
  anroparkänslig i JDK:n, och anroparen är då `spola.util`; det påverkar bara lokalisering
  (resursbuntar), som `Log` inte använder. `Log.of(System.Logger)` gör att tester kan
  injicera en inspelande logger. Nivåmappning: `trace`→`TRACE`, `debug`→`DEBUG`,
  `info`→`INFO`, `warn`→`WARNING`, `error`→`ERROR`.
- **Platshållarregler (exakt som i `Log`s Javadoc):** en genomläsning från vänster.
  `{}` tar nästa argument; slut på argument lämnar `{}` kvar; överskott ignoreras, utom att
  ett sista argument som är `Throwable` och inte förbrukats skickas som undantag (inte som
  text). Ett `Throwable` som en platshållare förbrukar blir text (`toString()`) och loggas inte
  som undantag (vi följer uppdraget, inte SLF4J 2:s specialfall). `\{}` ger bokstavligt `{}`;
  `\\{}` ger ett bakstreck följt av en platshållare (som SLF4J). Även metoden utan argument
  formateras, så att `\{}` betyder samma sak överallt. `null` (argument eller meddelande) blir
  `null`; arrayer `Arrays.deepToString` (primitiva arrayer `Arrays.toString`). Kastar
  `toString()` (eller en `Log.defer`-leverantör eller en meddelandeleverantör) skrivs
  `[toString() threw <klass>]` (resp. `supplier`, `message supplier`); undantagets meddelande
  skrivs inte, eftersom det kan innehålla värdet. Bara `VirtualMachineError` utom
  `StackOverflowError` (t.ex. `OutOfMemoryError`) släpps ut. Loggern får ett färdigt
  meddelande via `log(Level, String)`/`log(Level, String, Throwable)`, aldrig
  `MessageFormat`-parametrar, så `'` och `{0}` i text tolkas inte.
- **Inget arbete när nivån är av:** varje metod frågar `isLoggable` först. Metoderna med ett
  och två argument skapar inte ens argumentarrayen då (varargs-arrayen skapas av anroparen;
  det går inte att undvika i Java). Lata argument är explicita (`Log.defer`) i stället för att
  varje `Supplier`-argument tolkas som lat: ett objekt som råkar implementera `Supplier` (t.ex.
  `Lazy`) ska inte ändra betydelse när det loggas, och överlagringar
  `(String, Supplier<?>...)` bredvid `(String, Object)` ger olika beteende beroende på antalet
  argument.
- **Konsolformatet installeras explicit på JUL-rotloggern.** `System.Logger` skriver till
  `java.util.logging` när `java.logging` finns, så formatet hamnar där. `configure` tar bort
  *alla* handlers på rotloggern (JDK:ns `ConsoleHandler` skulle annars skriva varje rad två
  gånger; främmande handlers stängs inte eftersom de inte är våra), återställer nivåerna som
  ett tidigare anrop satte, sätter nya nivåer och lägger till en handler. Nivåerna är riktiga
  JUL-loggernivåer (inte ett filter i handlern), så `isLoggable` svarar rätt och avstängda
  nivåer kostar inget. JUL:s hierarki ärver nivån från närmaste förfader per punktsegment, så
  "längsta prefix vinner" och "hela segment" följer av den. Loggrarna hålls med starka
  referenser, annars kan JUL skräpsamla dem och nivån försvinner. Saknas `""` blir roten
  `INFO` (som Spring Boot). Mappning till JUL som JDK:n gör: `TRACE`→`FINER`,
  `DEBUG`→`FINE`, `ERROR`→`SEVERE`. Ingen statisk initiering gör något; `reset()` (främst för
  tester) återställer rotloggerns handlers och nivå från före första `configure`.
- **Radformat:** `tidsstämpel nivå [tråd] logger : meddelande`, tidsstämpeln ISO-8601 med
  millisekunder och offset (`uuuu-MM-dd'T'HH:mm:ss.SSSXXX`) i vald zon, nivån i `Log`s namn
  utfylld till fem tecken (JUL-nivåer däremellan avrundas nedåt: `CONFIG`→`DEBUG`). Trådnamnet
  tas från tråden som loggar (handlern är synkron); en namnlös virtuell tråd blir
  `virtual-<id>`. Radbrytningar och andra styrtecken utom tab i meddelande, trådnamn och
  loggernamn skrivs som `\n`, `\r`, `\uXXXX`: en post är alltid en rad och ett loggat värde
  kan inte förfalska rader (log forging). Stackspår på följande rader, oförändrade. Varje rad
  skrivs och flushas direkt; rader från olika trådar blandas aldrig. En `PrintStream` skrivs
  med sin egen teckenkodning, annan `OutputStream` med UTF-8; strömmen stängs aldrig.
- **`Lazy`:** dubbelkontroll med ett `volatile`-fält och en `ReentrantLock` (en virtuell tråd
  som väntar pinnar inte bäraren). Undantag memoreras inte (nästa `get()` försöker igen: ett
  tillfälligt fel ska inte bli permanent). `null` är ett fel (`NullPointerException`), också
  utan memorering; `null` som "inte uträknat" gör att värdet inte kan vara `null`, och ett
  `Optional` uttrycker frånvaro tydligare. Leverantören nollas efter lyckad uträkning.
  Rekursiv `get()` från den egna leverantören ger `IllegalStateException` i stället för
  oändlig rekursion. `toString()` räknar inte ut och visar inte värdet. `StableValue` är
  preview i JDK 25 och används inte.
- **`Unchecked` utan sneaky throw:** ett checked undantag kastas aldrig odeklarerat (det
  döljer det för kompilatorn och omöjliggör `catch`). `IOException` → `UncheckedIOException`,
  `InterruptedException` → avbrottsflaggan sätts igen och undantaget slås in i
  `UncheckedException`, övriga checked → `UncheckedException` med originalet som `cause`,
  `RuntimeException`/`Error` släpps igenom som samma instans. Gränssnitten deklarerar
  `throws Exception` (inte `Throwable`), så bara `Exception` behöver slås in.
  `InterruptedIOException` är en `IOException` och blir `UncheckedIOException` utan att
  flaggan rörs.
- **`ToString`:** samma form som en records `toString` (`Namn[a=1, b=2]`, värden med
  `String.valueOf`, arrayer expanderas inte). `secret` skriver `***` för ett värde som inte är
  `null` och anropar aldrig dess `toString()`, så varken värde eller längd läcker; `null` visas
  som `null` (avslöjar bara om hemligheten finns, vilket hjälper vid felsökning).

