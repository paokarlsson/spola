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
  värden likadant (`1` och `1L`) visas deras klasser. Likhet är `Objects.deepEquals`.
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
