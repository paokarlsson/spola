# Spola – arkitektur och designbeslut

Grundreglerna i `REGLER.md` går före allt i detta dokument.

## Katalogstruktur

```
framework/
  REGLER.md                 grundreglerna (lag)
  MONSTER.md                modulkatalog, API-mönster och vad som hör hemma i Spola
  Build.java                bygget, körs med `java Build.java` (source launcher)
  modules/<modul>/src/      källkod för en modul, med module-info.java överst
  modules/<modul>/test/     modulens tester, i modulens egna paket, utan module-info.java
  modules/guardrails/fixtures/   medvetet felaktiga exempel för guardrails självtest
  modules/di/README.md      exempel för spola.di bredvid motsvarande Spring-kod
  modules/config/README.md  exempel för spola.config bredvid motsvarande Spring Boot-kod
  modules/util/README.md    exempel för spola.util bredvid motsvarande Lombok- och Spring Boot-kod
  modules/codegen/README.md arbetsflödet för genererade builders/with-metoder, bredvid Lombok
  modules/demo/README.md    integrationsprogrammet: körning, Spring Boot-jämförelse, friktionsrapport
  modules/json/README.md    exempel för spola.json och spola.json.codec bredvid Jackson, avvikelser från Jacksons standard
  modules/http/README.md    exempel för spola.http bredvid Spring Boot/Tomcat, gränserna mot Spring Boots egenskaper, HTTPS och `server.ssl.*`
  modules/web/README.md     exempel för spola.web bredvid Spring MVC, avvikelser från Spring MVC
  modules/validation/README.md  exempel för spola.validation bredvid Jakarta Bean Validation, avvikelser från Jakarta
  modules/security-web/README.md  exempel för spola.security.web bredvid Spring Security, avvikelser från Spring Security
  out/                      byggresultat (ignoreras av git)
    classes/<modulnamn>/    kompilerade klasser per modul
    <modulnamn>.jar         en jar per modul (utan tester)
    test-classes/<modulnamn>/   modulen kompilerad igen tillsammans med sina tester
    codegen/spola.codegen/  generatorn, kompilerad av `java Build.java codegen`
    release/                `java Build.java release`: <modul>-<version>.jar, -sources.jar, SHA256SUMS
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
- **Genererade filer kontrolleras först:** `spola.codegen` kompileras före alla andra moduler,
  och dess `ToolProvider` (`spola-codegen`, laddad från `out/classes/spola.codegen` i ett eget
  `ModuleLayer`) kör `check` på källkods- och testkatalogerna för varje modul i `MODULES`.
  Först därefter kompileras resten. Se "Util del 2" nedan. `spola.codegen` får därför bara
  kräva JDK-moduler; bygget stoppar annars med ett meddelande.
- **`java Build.java codegen [FIL RECORD]`** kompilerar bara `spola.codegen` (till
  `out/codegen/`) och kör `regenerate` (utan argument) eller `generate FIL RECORD`. Ett annat
  första argument än `codegen`, `release` och `version` är ett fel i stället för att ignoreras.
- **Ordning:** kompilera alla moduler, packa jar-filerna, kompilera alla moduler igen med
  sina tester (`out/test-classes/`), kör guardrails (som därmed också ser testkällorna och
  testklassernas bytekod), kör till sist testerna för alla moduler. Testerna för alla moduler
  körs även om en tidigare modul fick rött; bygget blir sedan rött och listar modulerna.
- Inga inkrementella byggen: `out/` raderas och byggs om varje gång.
- **Version:** konstanten `VERSION` i `Build.java` (semver, kontrolleras mot ett mönster vid
  varje bygge) blir modulversion i varje jar (`jar --module-version`), så att
  `java --describe-module` och `ModuleDescriptor.rawVersion()` visar den. Den ligger i
  `Build.java` och inte i en egen fil, eftersom guardrails bara tillåter `.java`- och `.md`-filer
  i `framework/` (`LauncherCheck`). `java Build.java version` skriver ut den åt skripten.
- **Release:** `java Build.java release` kör hela bygget och skriver sedan `out/release/`:
  modulens jar under namnet `<modul>-<version>.jar`, en källkods-jar av `src/` och
  `SHA256SUMS` i det format `sha256sum -c` läser. `spola.demo` (`NOT_RELEASED`) är ett
  exempel och släpps inte. Publiceringen sker utanför bygget: `scripts/release.sh` taggar och
  `.github/workflows/release.yml` bygger om från taggen och laddar upp med `gh`. Workflowet
  installerar JDK 25 från Ubuntus paket och använder inga actions från marketplace
  (REGLER §1 i andan). Inget Maven-repo: konsumenter hämtar jar-filerna från releasen.
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
| `Section` | det bindningsfunktionen läser från: `string`, `intValue`, `longValue`, `booleanValue`, `doubleValue`, `duration`, `uri`, `path`, `enumValue`, `list`, `optional`, `value(nyckel, Converter)`, `section(delprefix, funktion)`, `has`, `keys()` (sedan 05c) |
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
  fel i stället för ersättningstecken). Ett byte order mark först i filen hoppas över (sedan
  05c). En valfri fil som inte finns (`NoSuchFileException`)
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
hjälpare) och 05b (builders och with-metoder för records, modulen `spola.codegen`, se "Util
del 2" nedan).

### API (paket `spola.util`)

| Typ | Roll |
|---|---|
| `Log` | `forClass(Class)`, `of(System.Logger)`, `defer(Supplier)`; `trace/debug/info/warn/error` med `(String)`, `(String, Object)`, `(String, Object, Object)`, `(String, Object...)`, `(Supplier<String>)`, `(Supplier<String>, Throwable)`; `isXxxEnabled()` |
| `Log.Deferred` | ett lat argument (från `Log.defer`) |
| `Logging` | `configure(LoggingSettings)`, `reset()` |
| `LoggingSettings` | record: nivå per prefix, utström, tidszon; `defaults()`, `withLevel`, `withLevels`, `withOutput`, `withZone`; `parseLevel(namn)` (sedan 05c) |
| `Lazy<T>` | `of(Supplier)`, `get()`, `isComputed()`; implementerar `Supplier<T>` |
| `Unchecked` | `run`, `get`, `runnable`, `supplier`, `function`, `consumer`, `wrap(Exception)` |
| `ThrowingRunnable/Supplier/Function/Consumer` | funktionella gränssnitt med `throws Exception` |
| `UncheckedException` | inslaget checked undantag; `getCause()` är originalet |
| `ToString` | `of(namn)`, `add(namn, värde)`, `secret(namn, värde)`, `toString()` |

Paketprivat: `MessageFormatter` (platshållarna), `LineFormatter` och `LineHandler`
(konsolformatet), `Failures` (regeln för vilka fel loggningen släpper igenom).

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
- **Garantin: ett loggningsanrop kastar inte, oavsett backend.** Ett anrop till en
  loggmetod i `Log` (alla nivåer, med och utan platshållare, med och utan avslutande
  `Throwable`, lata varianter) kastar aldrig på grund av (1) argumentens eller det avslutande
  undantagets `toString`/`getMessage`/`getLocalizedMessage`/`printStackTrace`/`getCause`
  m.m., eller (2) att backendens `System.Logger.log(...)` kastar, oavsett orsak. Undantaget är
  fatala `VirtualMachineError` utom `StackOverflowError` (t.ex. `OutOfMemoryError`), som
  kastas vidare. Garantin gäller med JDK:ns standardbackend (utan `Logging.configure`), med
  `Logging`s format och med en egen `System.Logger` via `Log.of`. Den hålls i `Log` självt,
  inte bara i `LineFormatter`/`LineHandler`: JDK:ns `ConsoleHandler`/`SimpleFormatter` fångar
  bara `Exception`, så ett `AssertionError` eller `StackOverflowError` från
  `printStackTrace` slapp annars ut, och vi får inte ändra JDK:ns konfiguration som bieffekt.
  Alla anrop till backenden går genom en metod (`Log.deliver`). Kastar
  `log(level, text, thrown)` loggas texten en gång till utan undantaget, följd av
  `[log(<undantagets klass>) threw <felets klass>]` (samma stil som platshållarmarkeringen,
  bara klassnamn); kastar även det andra anropet, eller kastar `log(level, text)` utan
  undantag, sväljs felet. Högst två anrop, ingen rekursion. Ett svalt fel skrivs ingenstans:
  det enda stället att skriva det på är backenden som just misslyckades.
- **Gränsen för garantin:** kastar backendens `isLoggable(level)` släpps undantaget igenom
  (också ur `isXxxEnabled`); `Log` gissar inte om nivån är på. En meddelandeleverantör eller
  `Log.defer`-leverantör som kastar ger som förut en markering i stället för texten
  (`[message supplier threw …]`, `[supplier threw …]`). En backend som blockerar, är långsam
  eller tappar poster upptäcks inte.
- **En regel för fatala fel:** "kasta vidare `VirtualMachineError` utom
  `StackOverflowError`, fånga allt annat" står bara i `Failures.rethrowIfFatal`, som `Log`,
  `MessageFormatter`, `LineFormatter` och `LineHandler` anropar. `StackOverflowError` fångas
  eftersom en rekursiv `toString`/`getMessage` är ett fel i ett värde, inte i JVM:en, och
  stacken är avvecklad när felet fångas.
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
  loggernamn skrivs som `\n`, `\r`, `\uXXXX` (även U+2028/U+2029): postens rad är alltid en
  rad. Stackspåret (`printStackTrace`, med orsaker och undertryckta undantag) följer på nästa
  rader, varje rad med en tab först (ramar får alltså två tabbar); en radbrytning i ett
  undantagsmeddelande ger en ny rad med tab, och övriga styrtecken i den skrivs som escapes.
  En rad som börjar i kolumn 0 är därför alltid början på en post: ett loggat värde, inte
  heller ett undantagsmeddelande, kan förfalska en post (log forging). Valt framför att skriva
  om `printStackTrace` för att escapa varje meddelande: tabben är en enkel regel som täcker
  orsaker, undertryckta undantag och egna `toString` utan att återskapa JDK:ns format. Kastar
  `printStackTrace` något (t.ex. en `toString` som kastar `AssertionError` eller en rekursiv
  `getMessage` som ger `StackOverflowError`) kastas det påbörjade spåret och ersätts av en rad
  med undantagets klassnamn och en markering; bara fatala `VirtualMachineError` utom
  `StackOverflowError` släpps igenom, samma regel som för platshållare. Varje rad
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

## Util del 2: builders och with-metoder för records (`spola.codegen`)

Uppdrag 05b. Ersätter Lomboks `@Builder`, `@Builder(toBuilder = true)` och `@With` för
records. Modulen kräver bara `jdk.compiler`; testerna läser `spola.test`. Arbetsflöde och
exempel bredvid Lombok: `modules/codegen/README.md`.

### API (paket `spola.codegen`)

| Typ | Roll |
|---|---|
| `RecordCodegen` | `generate(källfil, recordnamn) -> String` (den genererade källkoden), `targetFile`, `interfaceName`, `SUFFIX` |
| `GeneratedFiles` | `header`, `write(källfil, recordnamn)`, `find(kataloger)`, `check(kataloger) -> List<Problem>`, `regenerate(kataloger)`; `Problem`, `Written`, `Regeneration`, `MARKER`, `REGENERATE` |
| `CodegenException` | ett fel vid generering; meddelandet namnger fil och record |
| `CodegenTool` | `ToolProvider` `spola-codegen`: `generate FIL RECORD`, `regenerate KATALOG...`, `check KATALOG...` |

Paketprivat: `RecordReader` (parsning och kontroller), `RecordModel` (det som genereras från),
`Renderer` (texten).

### Beslut

- **Genererad, incheckad källkod.** REGLER §2 förbjuder annoteringar som markering, och
  därmed en annotation processor (den behöver en annotering att reagera på, och `-proc:none`
  gäller i bygget). Reflektion i runtime (läsa recordets komponenter med
  `Class.getRecordComponents` och anropa konstruktorn via `MethodHandle`) är förbjuden där den
  går att undvika (REGLER §4), ger runtime-fel i stället för kompileringsfel och kan inte
  följas med "Go to definition". Java 25 har inga `with`-uttryck utanför preview. Kvar blir
  vanlig Java-kod som skrivs av ett verktyg, checkas in och kompileras som all annan kod.
- **Opt-in via `implements` och ett sealed gränssnitt.** För `Person` genereras
  `sealed interface PersonWith permits Person` i samma paket och katalog, och recordet väljer
  stödet med `implements PersonWith`. Gränssnittet ger with-metoderna och `toBuilder()` som
  `default`-metoder direkt på recordet utan att något i recordet genereras eller ändras.
  `sealed` med recordet som enda tillåtna typ gör omvandlingen `(Person) this` säker och
  hindrar andra klasser från att implementera gränssnittet. `builder()` är statisk i
  gränssnittet (`PersonWith.builder()`), eftersom statiska gränssnittsmetoder inte ärvs.
  Namnet `<Record>With` är en fast regel i generatorn; inget upptäcks genom namnet.
- **Åtkomst följer recordet:** gränssnittet är publikt bara om recordet och alla typer runt
  det är publika (medlemmar i ett gränssnitt räknas som publika), annars paketprivat. Ett
  privat record kan inte nämnas av ett gränssnitt bredvid och är ett fel.
- **Allt går genom den kanoniska konstruktorn:** `build()` anropar `new Person(...)`,
  `withX` är `toBuilder().x(x).build()`. Den kompakta konstruktorns validering och
  normalisering körs därför alltid. Kostnaden (en builder per `withX`) är liten och koden blir
  en rad per metod.
- **Källparsning, inte klasser.** Generatorn parsar recordets källfil med
  `JavacTask.parse()` (ingen attribuering, ingen klassväg). Den kan därför köras innan något
  kompilerats, och även när den genererade filen eller recordet inte kompilerar. Typerna skrivs
  från syntaxträdet i en fast form; annoteringar tas bort. Eftersom gränssnittet ligger på
  toppnivå kvalificeras namn på medlemstyper i recordet och i typerna runt det
  (`Rejection` → `Summary.Rejection`, närmaste scope först, typparametrar lämnas). Källfilens
  importer följer med (`import module`, on-demand och statiska); en enkel import vars namn
  ingen komponenttyp eller gräns använder tas bort. Det ändrar ingen namnupplösning (en enkel
  import påverkar bara sitt eget namn) och gör att den genererade filen inte beror på det
  recordets övriga kod använder (t.ex. `spola.util.ToString` i en `toString`). Begränsning:
  medlemstyper som ärvs från en supertyp syns inte i syntaxträdet och kvalificeras inte.
  Komponenter är recordets icke-statiska fält i trädet (parsern gör dem till fält; andra
  instansfält är inte tillåtna i ett record).
- **Namn som skulle krocka är fel, inte specialfall:** komponenterna `builder`, `toBuilder`,
  `build` och `equals` (sättaren `equals(Object)` skulle krocka med `Object.equals`), två
  komponenter som ger samma `withX`, en egen `toBuilder()` eller `withX(...)` med en parameter i
  recordet (den skulle tyst ersätta den genererade), och en typ eller typparameter som heter
  `Builder` (betyder den nästlade klassen inne i gränssnittet).
- **Rubriken är enda igenkänningen.** En genererad fil börjar med
  `// Generated by spola.codegen from <Fil>.java, record <Namn>. Do not edit.` och
  `// Regenerate after changing the record: java Build.java codegen (in framework/)`.
  Rubriken anger bara källfilens namn (den ligger i samma katalog), inte sökvägen: utdata
  beror bara på källfilens text och namn, så samma källa ger samma byte var den än ligger, och
  rubriken blir densamma oavsett hur kommandot anropades. Filnamnet på den genererade filen
  avgör ingenting; en fil med rubriken men fel namn rapporteras. `write` skriver aldrig över en
  fil utan rubriken eller en genererad fil för ett annat record.
- **Kontrollen i varje bygge, före kompileringen.** Bygget kompilerar `spola.codegen` först
  och kör `check` på `src/` och `test/` i varje modul i `MODULES` innan någon annan modul
  kompileras: varje genererad fil genereras om i minnet och jämförs tecken för tecken (även
  radslut och blanktecken). Ett ändrat record (komponent tillagd, omdöpt, ny typ), en
  handredigerad fil, en skadad rubrik, en borttagen källfil och ett record som inte
  implementerar gränssnittet ger rött bygge med filens sökväg och `java Build.java codegen`.
  Eftersom inget kompileras först kommer meddelandet även när den inaktuella filen inte
  kompilerar (en omdöpt komponent), i stället för ett javac-fel som inte säger varför.
  Kontrollen kostar en parsning per genererad fil.
- **Generering är explicit.** Ett vanligt bygge skriver aldrig filer (bara kontroll).
  `java Build.java codegen` utan argument genererar om alla befintliga genererade filer och
  skriver bara de som ändras, så på ett rent träd ändras inget. Ett record som ännu inte
  implementerar gränssnittet får sin fil ändå, med en rad om vad som fattas.
- **Deterministisk och läsbar utdata:** fast ordning (rubrik, paket, importer, `builder()`,
  `toBuilder()`, en `withX` per komponent i komponentordning, `Builder` med fält, sättare och
  `build()`), fyra mellanslag per nivå, radslut `\n`, Javadoc på allt som
  `-Xdoclint:all/protected` kräver (också typparametrarna), privata konstruktorer i `Builder`
  (en implicit publik konstruktor skulle ge en doclint-varning). Den genererade koden
  kompilerar utan varningar under `-Xlint:all -Werror`, också för generiska records
  (omvandlingen `(Pair<A, B>) this` är kontrollerad eftersom `Pair` är final och implementerar
  `PairWith<A, B>`).
- **Testerna kompilerar och kör genererad kod i minnet** (`javax.tools` med byggets flaggor,
  en egen `ClassLoader` över de kompilerade byte). Den kompilerade testklassen skapas med
  `Class.forName(...).getConstructor().newInstance()`, reflektion som bara finns i
  testkoden: klassen finns först när testet körs. Den implementerar
  `Supplier<List<String>>`, så resten är vanliga anrop.

## Integration: kärnmodulerna i ett program (`spola.demo`)

Uppdrag 05c. En lagertjänst för en liten webbutik (orderfil in, sammanfattning ut) som
använder `spola.di`, `spola.config` och `spola.util` tillsammans. Syftet är att pröva
ergonomin innan fler moduler bygger på API:erna; utfallet är programmet och friktionsrapporten
i `modules/demo/README.md` (avsnitt "Friktion").

### Struktur

- `InventoryApp` är ingången: `main(String[])` och
  `run(List<String> args, Map<String,String> miljö, PrintStream ut, PrintStream logg)` som
  returnerar en exitkod. Den bygger konfigurationen, binder den, konfigurerar loggningen,
  skapar containern i en try-with-resources, kör och översätter fel till ett meddelande och en
  exitkod (sysexits: 65 trasig orderfil, 70 wiring/komponent/oväntat, 74 I/O, 78
  konfiguration).
- `InventoryWiring` är wiring-modulen: källornas ordning, bindningen till records och
  containermodulerna (`settings(app, ut)`, `STOCK`, `CHECKS`, `PROCESSING`).
- Bara de två nämner `Container`, `Binder` eller `Config`; komponenterna (`Stock`,
  `OrderCheck` med `QuantityLimit`/`BlockedSkus`, `AuditTrail`, `OrderProcessor`,
  `ReportWriter`) tar records och varandra i konstruktorn. Ett test (`BoundaryTests`)
  kontrollerar det på källkoden.
- Ingen modul får bero på `spola.demo`, och modulen exporterar inget paket.

### Beslut

- **Konfigurationsfilens plats är själv konfiguration** (`config.file`). Den läses i en
  första, mindre omgång (standardvärden, miljö, argument) innan hela konfigurationen byggs. Båda
  omgångarna markerar samma nycklar som känsliga, så ett fel redan i första omgången inte kan
  visa hemligheten. Friktionen står i rapporten (**Kvar**).
- **Settings-records heter `*Settings`, inte `*Config`,** så att en sökning efter `Config`
  bara träffar ingången och wiring-modulen.
- **Wiring-felet nås genom ingången.** Ett wiring-fel är ett programmeringsfel, inte något en
  slutanvändare kan framkalla med konfiguration. Ingången har därför en paketprivat överlagring
  som tar containermodulerna som funktion; testet utelämnar `CHECKS` och kör programmet genom
  ingången. `main` och den publika `run` använder alltid `InventoryWiring::modules`.
- **Orderfilen är extern data och valideras vid inläsningen** (REGLER §4). Ett byte order mark
  först i filen hoppas över. En artikel får bara innehålla synliga tecken
  (`OrderLine.visible`: inte Cc, Cf, Zl, Zp, Zs, surrogat eller otilldelade); annars är raden
  trasig. Valet är att avvisa vid inläsningen i stället för att escapa vid utskrift, så att
  sammanfattningen och det signerade revisionsspåret bara innehåller artiklar som går att
  läsa och jämföra, och så att det finns en enda plats att kontrollera.
- **`audit.file` får inte vara orderfilen.** Att öppna spåret tömmer filen, så ingången
  avvisar det som konfigurationsfel innan containern skapas (`AuditTrail.overwritesOrders`,
  som konstruktorn också kontrollerar). Ett spår som inte kan öppnas blir
  `UncheckedIOException` i komponenten och exitkod 74 i ingången.
- **Loggningen gäller under anropet.** `run` anropar `Logging.configure` med logg-strömmen och
  `Logging.reset()` i `finally`, eftersom JUL-konfigurationen är global för JVM:en och strömmen
  tillhör anroparen. Två samtidiga `run` i samma JVM skulle dela loggning (se rapporten, **Kvar**).
- **Fel utan stackspår.** Kända fel skrivs som `error: <meddelande>` på logg-strömmen. Ett
  oväntat `RuntimeException` ger `error: unexpected failure: <undantag>` och exitkod 70;
  stackspåret loggas bara på DEBUG.
- **Revisionsspåret (`AuditTrail`) är signerat med HMAC-SHA256** (JDK:ns `javax.crypto.Mac`)
  med den känsliga nyckeln `audit.secret`, så hemligheten har en verklig användning utan att
  någonsin skrivas ut. Implementationen testas mot RFC 4231 testfall 1 och 2.

### Ändringar i kärnmodulerna (05c)

De två första är tillägg. Den tredje ändrar beteendet bara för filer som börjar med ett byte
order mark; inget API ändras.

- **`Section.keys()`** (`spola.config`): de relativa nycklarna under en sektion, sorterade.
  Skäl: en map vars nycklar inte är kända i förväg (`inventory.stock.<sku>`,
  `logging.level.<prefix>`) gick inte att binda inne i bindningsfunktionen, så värdena kunde
  inte kontrolleras i samma rapport som resten. En nyckel lika med prefixet och en vars
  relativa del är blank tas inte med (den senare skulle inte gå att läsa med `Section`).
- **`LoggingSettings.parseLevel(String)`** (`spola.util`): läser nivånamn som i Spring Boot
  (`TRACE`, `DEBUG`, `INFO`, `WARN`/`WARNING`, `ERROR`, `OFF`, `ALL`, skiftlägesokänsligt).
  Skäl: README visade en `switch` som varje applikation fick kopiera. Metoden kastar
  `IllegalArgumentException` utan värdet i meddelandet; som omvandlare i `spola.config`
  (`c.value(nyckel, "log level", LoggingSettings::parseLevel)`) visar konfigurationen värdet
  själv, och maskerar det om nyckeln är känslig.
- **`Source.file`/`Source.optionalFile` hoppar över ett byte order mark** (U+FEFF) först i
  filen (`spola.config`). Skäl: Windows PowerShell 5.1 (`-Encoding UTF8`) och äldre Notepad
  skriver ett, och `Properties.load` gjorde det till en del av första nyckeln, så den
  inställningen ignorerades tyst. Bara ett inledande BOM tas bort; ett U+FEFF längre in är
  vanlig text, som förut. Samma regel som orderfilen i `spola.demo` redan hade.

## JSON del 1: trädmodell, parser och skrivare (`spola.json`)

Uppdrag 06a. Rad 06 i `PLAN.md` är delad i två uppdrag av planeraren: **06a** (detta:
trädmodell, strikt parser, gränser, skrivare) och **06b** (explicit mappning mellan records
och JSON med kodekar som värden, felrapport med JSON-sökväg för alla fel samtidigt, sealed
hierarkier med diskriminator). Skälet är att parsern är säkerhetskritisk (REGLER §4) och att
parsning och mappning var för sig är stora nog för ett eget uppdrag. Modulen har inga
`requires` (bara `java.base`), ingen reflektion och inget delat föränderligt tillstånd.

### API (paket `spola.json`)

| Typ | Roll |
|---|---|
| `JsonValue` | `sealed interface` med exakt sex fall |
| `JsonObject`, `JsonArray` | behållare; fabriker (`of`, `builder`, `copyOf`) och läsare med sökväg |
| `JsonString`, `JsonBoolean` (records), `JsonNull` (enum), `JsonNumber` | skalärer |
| `Json` | `parse` (String, byte[], InputStream, Reader, med och utan `JsonLimits`), `write`, `writePretty`, `writeUtf8`, `writePrettyUtf8` |
| `JsonLimits` | record med gränserna; `DEFAULT` och `withX` |
| `JsonParseException` | rad, kolumn, offset, beskrivning |
| `JsonAccessException` | läsarfel: sökväg, förväntad och faktisk typ |

Interna klasser: `Parser`, `Input` (text och strikt UTF-8), `TextWriter`, `Equality`,
`Paths`, `Text` (säker återgivning av indata i meddelanden).

### Beslut

- **Trädet är en sealed hierarki med värdesemantik.** `switch` med mönster täcker alla fall
  utan `default`. `JsonString` och `JsonBoolean` är records; `JsonNull` är en enum med ett
  värde; `JsonObject`, `JsonArray` och `JsonNumber` är finala klasser eftersom de behöver
  egen likhet och dold intern representation.
- **Inget i modellen är rekursivt.** Parsern håller öppna behållare i en lista på heapen,
  skrivaren likaså, och likhet jämförs med en explicit stack (`Equality`). Hashkoden för
  `JsonObject`/`JsonArray` räknas ut en gång när behållaren skapas, från barnens redan
  uträknade hashkoder. Därför kan träd av godtyckligt djup (byggda i kod eller parsade med
  högt `maxDepth`) jämföras, hashas och skrivas på vilken trådstack som helst, också en
  virtuell tråds; djupgränsen skyddar minnet, inte stacken. Indenterad utskrift växer med
  kvadraten på djupet (indraget), vilket är formatets natur.
- **Dubbla nycklar avvisas** med namnet (escapat, högst 40 tecken) och positionen för den
  andra förekomsten. Namn jämförs efter avkodning (`"a"` och `"\u0061"` är samma), exakt
  (ingen skiftlägesvikning eller Unicode-normalisering). RFC 8259 §4 lämnar beteendet öppet;
  olika parsrar som väljer olika värden är en känd källa till säkerhetsfel (en kontroll som
  läser första och en hanterare som läser sista värdet). Fabrikerna och buildern avvisar
  dubbletter på samma sätt (`IllegalArgumentException`).
- **Medlemmarnas ordning bevaras** (insättningsordning) vid parsning och skrivning, men är
  inte en del av likheten: JSON-objekt är oordnade (RFC 8259 §4), och två dokument med samma
  medlemmar ska jämföras lika. Arrayers ordning är en del av likheten.
- **Tal lagras som sin text** (validerad mot grammatiken i RFC 8259 §6) och omvandlas på
  begäran. De exakta omvandlingarna (`intValueExact`, `longValueExact`,
  `bigIntegerValueExact`) kastar `ArithmeticException`, JDK:ns konvention
  (`BigDecimal.intValueExact`), i stället för ett eget undantag: felet är aritmetiskt, inte
  ett parsfel. `bigIntegerValueExact` vägrar resultat med fler än
  `JsonNumber.MAX_EXACT_DIGITS` (10 000) siffror, så `1e1000000000` inte kan låsa en tråd i
  minuter. `bigDecimalValue` är billigt för alla exponenter som ryms i `BigDecimal` (skala
  inom `int`) och kastar `ArithmeticException` annars. `doubleValue` avrundar som
  `Double.parseDouble`.
- **Talens likhet är numerisk** och räknas på en kanonisk form: tecken, siffrorna utan
  inledande och avslutande nollor, och exponenten som `BigInteger`. `1`, `1.0`, `1e0` och
  `10e-1` är lika med samma hashkod, och `-0` är lika med `0`. Den kanoniska formen tar
  linjär tid i textens längd oavsett exponent (inget `BigDecimal.stripTrailingZeros`, inga
  tiopotenser), så likhet och hash kan inte användas för DoS. Texten är inte en del av
  likheten. Hashkoden räknas ut när talet skapas.
- **Strängar är alltid giltig Unicode.** Parsern avvisar ensamma surrogat, escapade eller ej;
  ett escapat högt surrogat måste följas av ett escapat lågt (RFC 8259 §7), ett rått av ett
  rått. `JsonString` och namn i `JsonObject` avvisar ensamma surrogat
  (`IllegalArgumentException`). Därför har varje träd en UTF-8-form och
  `parse(write(v)).equals(v)` gäller för alla träd, inte bara parsade (så länge trädet ryms
  inom de gränser som ges till `parse`; ett träd byggt i kod kan vara djupare eller större än
  `JsonLimits.DEFAULT`).
- **Byte-indata avkodas av en egen strikt UTF-8-avkodare** som följer grammatiken i RFC 3629
  §4 byte för byte (inte JDK:ns `CharsetDecoder`), eftersom felet ska ha exakt byte-offset,
  rad och kolumn, och avkodningen ska kunna stoppas vid storleksgränsen. Testerna jämför den
  med JDK:ns avkodare (`CodingErrorAction.REPORT`) för alla tvåbytessekvenser, alla tre- och
  fyrbytesstarter med varje andra byte och gränsbyten därefter, och alla kodpunkter.
- **Byte order mark:** ett inledande U+FEFF (EF BB BF i byte-indata) hoppas över i all indata
  (RFC 8259 §8.1 tillåter det), på samma sätt som `spola.config` gör med
  konfigurationsfiler. Det räknas i offset och storleksgräns men inte som en kolumn. Ett
  U+FEFF längre in, utanför en sträng, är ett fel; i en sträng är det ett vanligt tecken.
  UTF-16- och UTF-32-indata accepteras inte (RFC 8259 §8.1 kräver UTF-8).
- **Standardgränserna** (`JsonLimits.DEFAULT`):
  - `maxDepth` 512: djupare legitima dokument är sällsynta; Jackson har 1 000. Uppdragets beslut.
  - `maxStringLength` 1 048 576 UTF-16-enheter: en fjärdedel av standardstorleken, nog för
    t.ex. en inbäddad bild i base64 på 750 kB; Jackson har 20 000 000.
  - `maxNumberLength` 1 000 tecken: som Jacksons `StreamReadConstraints`. Tal längre än så är
    inte siffror från verkliga system, och omvandlingarna är linjära i längden.
  - `maxEntries` 100 000 per objekt eller array: en gräns oberoende av storleken för den som
    höjer `maxInputSize`; större samlingar i en förfrågan är ovanliga och kan tillåtas
    uttryckligen.
  - `maxInputSize` 4 MiB: trädet tar flera gånger indatans storlek i minne, eftersom varje
    värde är ett objekt. Uppmätt för 4 MiB indata: `[10,10,...]` och `{"ab":10}` upprepat
    ungefär 25 gånger (cirka 110 MB), `[[1],[1],...]` och `["ab",...]` ungefär 13 gånger.
    För att hålla nere det delas ensiffriga tal, `true`/`false`/`null` och tomma behållare,
    ett namn som förekommer många gånger lagras en gång per dokument, och behållarna lagras
    i exakt storlek. 4 MiB räcker för vanliga API-anrop och håller ett enskilt dokument kring
    100 MB heap i värsta fall; den som tar emot många samtidiga dokument sänker gränsen.
  Precis vid en gräns accepteras; en över ger `JsonParseException` som namnger gränsen. En
  ström eller `Reader` läses aldrig mer än en enhet förbi `maxInputSize`, så en ström som
  aldrig tar slut ger fel i stället för `OutOfMemoryError`. Storleken räknas i byte för
  byte-indata och i UTF-16-enheter för text, eftersom det är det som faktiskt läses.
- **Fel:** `JsonParseException` (okontrollerat) har rad (från 1; `\n`, `\r` och `\r\n`
  avslutar en rad), kolumn (från 1, i kodpunkter) och offset (byte för byte-indata, UTF-16-
  enheter för text; `isByteOffset()`), och en kort beskrivning av vad som förväntades och vad
  som hittades, med hänvisning till RFC:n. Meddelandet är alltid utskrivbar ASCII: indata
  citeras som högst ett tecken (`'x'` eller `U+0009`) eller ett namn på högst 40 tecken med
  allt utanför utskrivbar ASCII som `\uXXXX`, så att ett fel kan loggas utan att förfalska
  loggrader. Läsfel från en ström eller `Reader` är inte parsfel: `parse(InputStream)` och
  `parse(Reader)` deklarerar `IOException`.
- **Skrivarens escaping** är minimal men säker: `"` och `\` alltid; U+0000–U+001F alltid
  (kortform `\b \t \n \f \r` där den finns, annars `\u00xx` med gemena hexsiffror); och
  dessutom U+007F, U+2028 och U+2029. Motivering: U+2028 och U+2029 är radavslutare i
  JavaScript före ES2019 och i många loggverktyg och textredigerare, så rå JSON inbäddad i
  `<script>` eller en loggrad kan brytas; U+007F (DEL) är ett styrtecken (kategori Cc) som
  terminaler kan tolka. Allt annat, surrogatpar inräknade, skrivs rått (giltig UTF-8 och
  minsta möjliga utdata). C1-styrtecknen (U+0080–U+009F) escapas inte; de är inte
  radavslutare i JSON eller JavaScript, och uppdraget anger exakt vilka tecken som escapas.
  `/` escapas inte. Tal skrivs med sin lagrade text.
- **Indenterad utskrift** är deterministisk: två blanksteg per nivå, ett element eller en
  medlem per rad, `": "` efter namnet, `\n` som radbrytning oavsett plattform, ingen
  avslutande radbrytning, tomma behållare som `{}` och `[]`.
- **`writeUtf8` heter inte `write`**, eftersom en `PrintStream` är både `Appendable` och
  `OutputStream` och anropet då vore tvetydigt.
- **Läsare med sökväg.** `JsonObject` och `JsonArray` bär ett fält `path` som inte ingår i
  värdet (`equals`, `hashCode`, skrivaren). Läsarna (`object`, `array`, `get`, `optional`)
  returnerar en vy som delar medlemmarna men har vägen dit (`$.items[2]`), så att ett fel
  längre ner kan nämna hela sökvägen. Ett parsat eller byggt värde, och ett värde hämtat via
  `members()`/`values()`, har vägen `$`. Valet framför en separat markörtyp: läsarna sitter
  direkt på trädet, som uppdraget anger (`obj.string("name")`), och vyn kostar ett litet objekt
  utan kopiering. `optional(namn)` är tom när medlemmen saknas eller är `null`, eftersom de
  två betyder samma sak för en valfri medlem i de flesta API:er; `contains` och `members()`
  skiljer dem åt. Namn som inte är identifierare skrivs `["a.b"]` i sökvägen, escapade.

## JSON del 2: explicit mappning mellan Java och JSON (`spola.json.codec`)

Uppdrag 06b. Kodekar som värden, byggda av method references och lambdas, mappar records,
enums, samlingar, `Optional`, `java.time`, egna värdetyper och sealed hierarkier till och
från trädmodellen. Avkodningen samlar alla problem med JSON-sökväg.

### API (paket `spola.json.codec`)

| Typ | Roll |
|---|---|
| `JsonCodec<T>` | abstrakt klass: `encode`, `decode`, `read(String)`, `read(byte[])`, `write`, `writePretty`, `map(to, from)`; statiska `object` (0–16 fält och den allmänna formen), `field`, `optionalField`, `sealed` |
| `JsonCodecs` | inbyggda kodekar (`STRING` … `DURATION`, `JSON`) och kombinatorerna `list`, `set`, `map`, `optional`, `nullable`, `lazy`, `enumOf` |
| `ObjectCodec<R>` | kodeken för objekt med namngivna medlemmar; `ignoringUnknown()` |
| `Field<R, T>` | ett fält: namn, kodek, getter; `orElse(default)`; också nyckel i den allmänna formen |
| `FieldValues<R>` | de avkodade värdena i den allmänna formen, `get(field)` |
| `Constructors.Of1` … `Of16` | konstruktorformerna för `object` med 1–16 fält |
| `Variant<T, S>`, `Variant.Tagged<T>` | en subtyp i en sealed hierarki (tagg + objektkodek) och väljarens resultat |
| `JsonMappingException`, `JsonProblem` | felrapporten: `problems()` med sökväg och meddelande |

Interna klasser: en per kodekslag (`ScalarCodec`, `MappedCodec`, `ListCodec`, `SetCodec`,
`MapCodec`, `OptionalCodec`, `NullableCodec`, `LazyCodec`, `EnumCodec`, `SealedCodec`),
`Decoding`/`Encoding` (tillståndet för ett anrop), `Path` (sökvägen som kedja, renderas bara
vid fel) och `Messages` (säker återgivning av text utifrån).

### Beslut

- **Plats och beroende.** Ett exporterat paket i `spola.json`, inte en egen modul: mappningen
  hör ihop med trädet och modulen har fortfarande inga `requires`. Beroendet går bara åt ena
  hållet: ingen klass i paketet `spola.json` nämner `spola.json.codec` (ett test läser
  källorna). `Paths` och `Text` i `spola.json` är paketprivata, så paketet har egna motsvarigheter
  (`Path`, `Messages`) med samma format och samma escaping, i stället för att trädets publika
  API vidgas. Modulens testingång (`spola.json.JsonTests`) tar med kodektesterna via
  fullständigt namn; det är testkod, inte huvudkod.
- **`JsonCodec` är en abstrakt klass med paketprivat konstruktor, inte ett interface.** Den
  interna avkodningen tar en sökväg och ett problemtillstånd (`decodeAt(json, path,
  decoding)`), som inte ska vara publikt API. En egen kodek byggs av de befintliga med
  `map(to, from)`; den mängden räcker för egna värdetyper och håller felinsamling, sökvägar
  och djupgräns i ramverket. Statiska fabriker för objekt och hierarkier ligger på
  `JsonCodec` (som uppdraget skisserar: `JsonCodec.object`, `JsonCodec.sealed`), skalärer och
  kombinatorer på `JsonCodecs`, så att `import static spola.json.codec.JsonCodecs.*` ger allt
  man kombinerar med och `JsonCodecs.map(c)` (en `Map`) inte krockar med instansmetoden
  `c.map(to, from)`.
- **Ingen reflektion behövs.** Allt en kodek vet kommer från argumenten: konstruktorn är en
  method reference (`Person::new`) vars parametertyper kompilatorn kontrollerar mot fältens
  typer (`Constructors.OfN`), getters är method references, enums ges som `E.values()`, och
  sealed hierarkier väljs med en `switch` i applikationens kod. Den allmänna formen använder
  fälten som typade nycklar: `FieldValues.get(Field<R, T>)` gör en okontrollerad
  språk-cast till `T` av värdet på fältets index, vilket är säkert eftersom värdet avkodades
  av just det fältets kodek. Ingen `Class` används: inte `isInstance`, `cast`,
  `getRecordComponents`, `getEnumConstants` eller `getPermittedSubclasses` (ett test läser
  källorna).
- **Felinsamling.** Ett `Decoding`-objekt per `decode`-anrop. En kodek som hittar ett fel
  rapporterar det och returnerar `null`; anroparen ser att något gick fel på att antalet
  rapporterade problem ökade, aldrig på returvärdet (`null` är ett giltigt värde för
  `nullable`). Därför kan ett objekt avkoda alla sina medlemmar, rapportera alla fel och låta
  bli att anropa konstruktorn om något fält misslyckades. Ordningen är dokumentordning:
  medlemmarna gås igenom i den ordning de står i objektet; saknade medlemmar rapporteras efter
  objektets övriga medlemmar (de har ingen plats i dokumentet), i fältordning. Undantag
  används inte för felen vid avkodning, så 10 000 felaktiga element kostar inga stackspår.
- **Gränsen på 100 problem.** De första 100 sparas (sökvägen renderas först då), resten
  räknas, och ett sista problem på `$` säger hur många som utelämnades. Avkodningen fortsätter
  ändå genom hela dokumentet för att kunna räkna; dokumentets storlek är redan begränsad av
  parserns gränser. `getMessage()` är en rad (radbrytning vore ett rått styrtecken) med
  antalet och så många problem som får plats inom 2 000 tecken, sedan `... (N more)`.
- **Säkra meddelanden.** Allt i sökvägar och meddelanden är utskrivbar ASCII; tecken utanför
  skrivs `\uXXXX` (som i `JsonParseException`). Ett värde ur dokumentet citeras med högst 40
  tecken plus längden. I sökvägar skiljer `Path` på två slags namn (runda 2, BRK-1): ett namn
  som kodeken deklarerar (ett fält i `object`, en sealed kodeks diskriminator) skrivs alltid
  helt och exakt som `spola.json` skriver det (`$.name` eller `["escapat"]`), så att två fält
  aldrig får samma sökväg och `JsonAccessException` och kodeken ger samma sökväg för samma
  namn. Ett namn som kommer från data (en okänd medlem, en nyckel i `map`) kortas över 40
  tecken (`["nnnn..."... (10000 characters)]`): annars kunde 100 problem under en lång nyckel
  bli hundratals megabyte, och deklarerade namn har applikationen själv valt. Ett
  undantagsmeddelande från applikationskod (konstruktor, `map`) återges med högst 200 tecken
  (40-teckensgränsen gäller citat av ett värde ur dokumentet, inte undantagstext), eftersom det är text som applikationen skrivit för att
  förklara felet (`quantity must be between 1 and 999`) och 40 tecken skulle klippa vanliga
  meddelanden; det escapas på samma sätt. Undantagsklassens namn tas inte med (det är en
  implementationsdetalj som inte hör hemma i ett svar till en klient). Meddelanden från `DateTimeParseException`
  används inte alls, eftersom de citerar indata oescapat.
- **Okända medlemmar avvisas som standard**, ett problem per medlem, med de kända namnen i
  meddelandet. Spring Boot sätter `FAIL_ON_UNKNOWN_PROPERTIES=false`; här vore det en väg för
  mass assignment (en klient sätter `admin`) och för stavfel som passerar tyst. `ignoringUnknown()`
  ger en ny kodek som hoppar över dem, uttryckligen och per kodek.
- **Ingen tvångsomvandling.** Varje skalärkodek tar exakt en JSON-typ. Heltal kräver ett
  exakt heltalsvärde inom typens intervall (`JsonNumber.intValueExact` m.fl.), så `1.0` och
  `1e2` är `int` men inte `1.5`. `DOUBLE` avvisar tal som skulle bli oändliga och tal skilda
  från noll som skulle bli 0 (`1e-400`): båda ligger utanför typens intervall. Övrig
  avrundning till närmaste `double` är typens natur och accepteras. `BIG_INTEGER` har
  `JsonNumber`s gräns (10 000 siffror) och `BIG_DECIMAL` kräver skala inom ±10 000, så att ett
  billigt `1e1000000000` inte blir dyrt när applikationen räknar med det (`toBigInteger`,
  `toPlainString`). Gränserna gäller i båda riktningarna så att allt som kodas också går att
  avkoda. `UUID` kräver formen 8-4-4-4-12 (`UUID.fromString` tar också `1-2-3-4-5`).
- **`java.time` som ISO-8601-strängar** med typernas egna `parse` och `toString` (som Spring
  Boot med `WRITE_DATES_AS_TIMESTAMPS=false`). `LocalDate.parse` är strikt
  (`ResolverStyle.STRICT`), så `2026-02-30` avvisas. `Instant` kräver en zon eller ett
  offset; `2026-10-02T11:18:00+02:00` accepteras och blir `09:18Z`.
- **`optionalField` utelämnas när tom** (som `@JsonInclude(NON_ABSENT)`): en saknad medlem
  och `null` betyder båda "inget värde" för ett valfritt fält, och den som läser behöver inte
  skilja dem åt. `optional(c)` är kombinatorn för andra platser (listelement) och skriver
  `null`. `orElse(v)` gäller bara en saknad medlem; `null` avkodas av kodeken och är ett fel
  om den inte är `nullable`. Värdet skrivs alltid vid kodning (att utelämna värden som är lika
  med standardvärdet vore tyst och beroende av `equals`).
- **Kodning stannar vid första felet.** Ett värde som inte går att skriva (`null` utan
  `nullable`, `NaN`, en sträng med ensamt surrogat, ett undantag från en getter eller från
  `from`) är ett programmeringsfel, inte indata att rapportera tillbaka, så det kastas direkt
  som `JsonMappingException` med ett problem och sökvägen. `NullPointerException` djupt inne
  i ramverket förekommer inte; varje kodek kontrollerar `null` först.
- **Sealed hierarkier via `switch`, inte `Class`-tokens.** `JsonCodec.sealed(namn, väljare,
  varianter...)`: väljaren är en `switch` över den sealed typen som returnerar
  `VARIANT.tag(värde)`. `Variant<T, S extends T>.tag(S)` gör att en `Circle` bara kan märkas
  av en variant för `Circle`, och utan `default` kontrollerar kompilatorn att alla tillåtna
  subtyper har en gren: en ny subtyp utan gren är ett kompileringsfel. `Tagged` fångar värdet
  i en lambda som kodar det med variantens kodek, så ingen cast behövs. Varianternas kodekar
  måste vara `ObjectCodec`, eftersom diskriminatorn läggs in som första medlem och
  kollisioner med fältnamn ska kunna upptäckas när kodeken skapas. Vid läsning slås
  diskriminatorn upp var som helst i objektet och hoppas över av variantens kodek (den är
  inte en okänd medlem). En variant som väljaren returnerar men som inte finns i listan (jämförs
  på identitet) är ett programmeringsfel och ger `JsonMappingException` vid kodning med
  sökvägen och de kända taggarna, liksom en väljare som returnerar `null` eller kastar.
- **Djup.** Objekt och arrayer räknas vid både avkodning och kodning, och över
  `JsonLimits.DEFAULT.maxDepth()` (512) blir det ett problem i stället för ett steg till ned.
  Samma räkning som parserns: allt som parsern accepterar med standardgränserna kan avkodas,
  och rekursionen i kodekarna är begränsad till 512 behållare. Det räcker med god marginal för
  standardstacken (1 MiB); testerna kör 100 000 nivåer på en plattformstråd, en virtuell
  tråd och en tråd med 512 KiB stack. `lazy` räknar dessutom hur många lata kodekar som gås
  igenom utan ett objekt eller en array emellan; över 64 är det en kodek som löser upp till
  sig själv (`lazy(() -> SAMMA)`), och det ger `IllegalStateException` i stället för
  `StackOverflowError`.
- **`lazy` hämtar kodeken en gång**, under ett `ReentrantLock` (som inte låser fast en virtuell
  tråd vid en bärartråd) och läser den sedan ur ett `volatile`-fält.
- **Mängder:** `set(c)` behåller arrayens ordning och avvisar dubbletter (enligt elementens
  `equals`) i stället för att tyst ta bort dem. **Mappar:** `map(c)` är bara för
  `Map<String, V>` och behåller medlemsordningen; andra nyckeltyper är utanför uppdraget.
- **Samlingar är oföränderliga.** `list`, `set` och `map` returnerar oföränderliga vyer av
  samlingar som inte delas med något annat; de får innehålla `null` när elementkodeken är
  `nullable` (vilket `List.copyOf` inte tillåter).
- **`JsonMappingException` har en paketprivat konstruktor**, så att varje sådan rapport kommer
  från en kodek och följer reglerna för säkra meddelanden. `JsonProblem` är ett publikt
  record så att uppdrag 08 och 09 kan använda och jämföra problemen.

## HTTP-server del 1: HTTP/1.1 på virtual threads (`spola.http`)

Uppdrag 07a. Rad 07 i `PLAN.md` (HTTP/1.1 på virtual threads, TLS, keep-alive, gränser mot
missbruk) är delad av planeraren i två uppdrag: **07a** (detta: HTTP/1.1 över TCP,
keep-alive, pipelining, chunked, `Expect: 100-continue`, gränser, tidsgränser, start och mjukt
stopp) och **07b** (TLS ovanpå samma kärna, certifikat som testerna skapar själva). Skälet är
att protokollkärnan och TLS var för sig är tillräckligt stora för ett eget uppdrag och båda är
säkerhetskritiska. Routing och request/response-modellen för applikationer är uppdrag 08.

### API (paket `spola.http`)

| Typ | Roll |
|---|---|
| `HttpServer` | `start(options, handler)`, `port()`, `address()`, `close()` (mjukt stopp) |
| `HttpServerOptions` | record: adress, port, `HttpLimits`, `HttpTimeouts`; `DEFAULT`, `withX` |
| `HttpLimits` | record: `maxRequestLineLength`, `maxHeaderSectionSize`, `maxHeaderCount`, `maxBodySize`, `maxConnections` |
| `HttpTimeouts` | record: `headerTimeout`, `idleTimeout`, `readTimeout`, `writeTimeout`, `shutdownTimeout` |
| `Handler` | funktionellt gränssnitt `Response handle(Request) throws Exception` |
| `Request` | metod, target som det stod, rå sökväg och query, `host()`, version, `Headers`, kropp (`body()`, `bytes()`) |
| `Headers`, `Headers.Field` | oföränderliga, ordnade, skiftlägesokänsliga namn, flera värden; validerade |
| `Response`, `ResponseBody` (`Empty`, `Bytes`, `Streaming`), `BodyWriter` | svaret; kroppen som sealed hierarki |
| `RequestBodyException` | kroppen kunde inte läsas; `status()` |
| `HttpVersion` | `HTTP_1_0`, `HTTP_1_1` |

Interna klasser: `Connection` (en anslutning, dess tillstånd och livscykel), `ConnectionInput`
(buffrad läsning med tidsgränser), `RequestParser` (request-rad, huvuden, ramning),
`BodyStream` (kroppen: Content-Length eller chunked), `ResponseWriter` (huvud, ramning,
strömmande kropp), `GuardedOutput` (skrivvakten), `Syntax` (teckenklasser och escaping),
`LoggedThrowable` (undantag som är säkra att logga), `ProtocolException`.

Avvikelser från namnen i uppdraget: tidsgränserna är ett eget record (`HttpTimeouts`) i
`HttpServerOptions`, eftersom de är fem värden av samma slag som ändras tillsammans och har
en egen `DEFAULT`; formen (värden i optionerna) är densamma. `Request.host()` finns eftersom
absolute-form går före `Host` (RFC 9112 §3.2.2) och någon måste avgöra det.

### Beslut

- **Egen implementation på `ServerSocket`/`Socket`, inte `jdk.httpserver`.**
  `com.sun.net.httpserver` är tillåten enligt REGLER §1, men dess gränser ställs via globala
  systemegenskaper (`sun.net.httpserver.*`), dess parsning är tolerant och går inte att göra
  strikt (ensamt LF, obs-fold, dubbla `Content-Length`), och den saknar tidsgräns för
  långsamma klienter per förfrågan. En egen parser på JDK:ns socket-primitiver ger full
  kontroll, gränser som värden och testbarhet mot RFC:erna. Modulen nämner inte
  `com.sun.net.httpserver` någonstans i koden (testat).
- **Blockerande I/O på en virtuell tråd per anslutning, inte NIO-selektorer.** Koden läser som
  protokollet: läs request-raden, läs huvudena, anropa handlern, skriv svaret, upprepa. En
  virtuell tråd som blockerar i en socketläsning frigör sin bärartråd, så modellen skalar till
  tiotusentals anslutningar utan återanrop eller tillståndsmaskiner. Acceptor-tråden är en
  plattformstråd (daemon); den blockerar bara i `accept` och i väntan på en ledig anslutningsplats, aldrig på en
  enskild klient. Handlern körs på anslutningens tråd och får blockera.
- **Inga `requires` på andra ramverksmoduler.** Loggning via `System.Logger` (namnet
  `spola.http`), som `spola.util` formaterar när loggning är konfigurerad.
  `module-info.java` har `requires static java.net.http`: modulens tester kompileras som en
  del av modulen (Build.java) och kan bara läsa det modulen läser, och kriterium 3 kräver ett
  test med `java.net.http.HttpClient`. `static` gör beroendet valfritt vid körning; ingen
  huvudklass använder paketet (testat). Alternativet, en mekanism i `Build.java` och
  guardrails för JDK-moduler som bara tester läser, hade ändrat två andra moduler.
- **Strikt CRLF.** Varje rad (request-raden, huvuden, chunk-rader, trailers) måste sluta med
  CRLF. Ett ensamt LF eller ett ensamt CR ger 400. RFC 9112 §2.2 tillåter men kräver inte att
  ett ensamt LF accepteras; olika tolkningar av radslut mellan en proxy och en server är en
  känd smugglingsväg. Upp till 8 tomma rader före request-raden hoppas över (§2.2: minst en).
- **Request-target.** Origin-form, absolute-form (`http`/`https`, ingen userinfo, värden går
  före `Host`) och asterisk-form (bara `OPTIONS`). `CONNECT` ger 501. Utöver uppdragets krav
  (styrtecken, blanktecken och byte över 0x7E ger 400) kontrolleras sökväg och query mot
  RFC 3986 (`pchar`, `/`, `?`, korrekt `%XX`); `#`, `{`, `|`, `\` och liknande ger 400, som i
  Tomcat utan `relaxedQueryChars`. Det stänger av target som tolkas olika av olika parsrar.
  Version: `HTTP/1.1` eller `HTTP/1.0`; annan korrekt formad version ger 505, felformad 400
  (namnet är skiftlägeskänsligt, §2.3).
- **Fält.** Namnet är `token`; blanktecken före kolon ger 400 (§5.1); obs-fold och
  blanktecken före första fältet ger 400 (§5.2, §2.2). I värdet är bara HTAB, SP, VCHAR och
  obs-text tillåtna (RFC 9110 §5.5); NUL, CR, LF och övriga styrtecken (ESC, DEL) ger 400.
  Inledande och avslutande blanktecken tas bort. Värden avkodas som ISO-8859-1, en byte per
  tecken, så att obs-text bevaras utan tolkning. `Host`: HTTP/1.1 kräver exakt ett, HTTP/1.0
  högst ett, och värdet måste vara `uri-host [":" port]`.
- **Kroppens längd och smuggling (RFC 9112 §6).** `Transfer-Encoding` i HTTP/1.0 ger 400;
  `Transfer-Encoding` och `Content-Length` i samma förfrågan ger 400; `Transfer-Encoding` där
  `chunked` inte är sista kodningen ger 400 (§6.3, MUST); `chunked` sist men med andra
  kodningar ger 501 (§6.1: ingen annan kodning är implementerad). Uppdraget angav 501 för allt
  som inte är exakt `chunked`; där §6.3 kräver 400 följer Spola RFC:n, och anslutningen stängs
  i båda fallen. Flera `Content-Length` (som fält eller lista) ger 400 även när värdena är
  lika; värdet är bara decimala siffror och ryms i `long` (63 bitar). Utan `Content-Length`
  och `Transfer-Encoding` har en förfrågan ingen kropp (§6.3 punkt 7). Chunk-storleken är
  hexadecimal utan tecken, prefix eller blanktecken, med spillkontroll; tillägg tillåts enligt
  grammatiken i §7.1.1 (med BWS) men läses inte; raden är högst 4 096 byte
  (`BodyStream.MAX_CHUNK_LINE`, Tomcats `maxExtensionSize` är 8 192; ingen känd klient skickar
  tillägg i närheten av det). Trailers läses med samma gränser som huvudena (egen budget) och
  kastas. Varje fel i ramningen stänger anslutningen, så inget av det som följer kan tolkas
  som en ny förfrågan.
- **Kroppen läses lat**, på handlerns tråd, genom `BodyStream`. Följden för felaktiga chunked
  kroppar: handlern anropas (huvudet var korrekt), men läsningen kastar `RequestBodyException`
  och servern svarar med dess status (400/408/413/431) om handlern låter undantaget gå
  vidare. Fångar handlern det och svarar själv skickas svaret, men anslutningen stängs ändå.
  Det är priset för att `100 Continue` ska kunna vänta på handlern; tabelltestet visar för
  dessa fall att handlern aldrig får kroppen, och för alla fel i huvudet att handlern aldrig
  anropas.
- **Oläst kropp läses före svaret.** Returnerar handlern utan att ha läst hela kroppen läser
  servern resten (inom `maxBodySize` och `readTimeout`) innan svarets huvud skrivs. Är
  ramningen trasig, kroppen för stor eller för långsam skickas kroppens status (400/413/431/408)
  med `Connection: close` i stället för handlerns svar, så att servern aldrig skickar ett svar
  som ser lyckat ut för en förfrågan den inte kunnat läsa färdigt (gauntlet-runda 2, BRK-1).
  Tre fall behåller handlerns svar och stänger anslutningen efter det: ett strömmande svar
  (dess `BodyWriter` körs efter handlern och får läsa kroppen, så servern kan inte läsa den
  först; ett ramningsfel upptäcks efter svaret och stänger anslutningen), `Expect: 100-continue`
  där kroppen aldrig begärdes (klienten kan ha avstått från att skicka den, RFC 9110 §10.1.1),
  och en handler som själv fångat `RequestBodyException` och svarat. Priset är att en handler
  som svarar tidigt (t.ex. 401 på en stor uppladdning) får sitt svar skickat först när kroppen
  lästs; den läses ändå för att anslutningen ska kunna bära nästa förfrågan.
- **`Expect: 100-continue`** (RFC 9110 §10.1.1). `100 Continue` skickas när kroppen läses
  första gången och svaret inte har börjat. Svarar handlern utan att läsa kroppen skickas inget
  `100`, och anslutningen stängs efter svaret, eftersom klienten kan skicka kroppen eller låta
  bli. `Content-Length` över `maxBodySize` ger 413 innan handlern anropas, alltså inget `100`.
  En förväntan som inte är `100-continue` ger 417; i HTTP/1.0 ignoreras `Expect`.
- **Standardgränserna** (`HttpLimits.DEFAULT`), jämförda med Tomcat och nginx:
  - `maxRequestLineLength` 8 192 byte: som nginx `large_client_header_buffers` (8 KB per rad)
    och Tomcats 8 KB för hela huvudet. Längre URL:er är i praktiken fel eller attacker; 414.
  - `maxHeaderSectionSize` 16 384 byte: dubbelt Tomcats 8 KB, eftersom stora cookies och
    JWT-token i `Authorization` är vanliga och Spola räknar request-raden för sig; nginx
    tillåter 4 × 8 KB. 431.
  - `maxHeaderCount` 100: som Tomcats `maxHeaderCount`. 431.
  - `maxBodySize` 10 MiB: som Spring Boots `spring.servlet.multipart.max-request-size`; en
    gräns för hela kroppen, läst eller oläst (Tomcats `maxSwallowSize` är 2 MB men gäller bara
    det som sväljs). nginx har 1 MB (`client_max_body_size`). 413.
  - `maxConnections` 10 000: Tomcat har 8 192 med NIO. Med virtuella trådar kostar en ledig
    anslutning ungefär 20 KB (8 KiB läsbuffert, 8 KiB skrivbuffert, tråd och socket), alltså
    cirka 200 MB vid gränsen; gränsen skyddar framför allt mot att filbeskrivarna tar slut.
- **Standardtidsgränserna** (`HttpTimeouts.DEFAULT`):
  - `headerTimeout` 10 s, total tid från första byte till sista huvudet (och längsta väntan
    på första byten i en ny anslutning). Total, inte per läsning: en klient som skickar en byte
    var nionde sekund kan annars hålla en anslutning i evighet (slowloris). Implementeras
    genom att varje läsning får tidsgränsen kvar till en absolut deadline. Tomcat har 20–60 s
    per läsning; nginx `client_header_timeout` 60 s. 10 s räcker för alla riktiga klienter,
    även över långsamma mobilnät, eftersom huvudet är litet. Svar 408.
  - `idleTimeout` 60 s mellan förfrågningar: som Tomcats `keepAliveTimeout` (standard lika
    med `connectionTimeout`, 60 s) och nära nginx `keepalive_timeout` 75 s. Stängs utan svar.
  - `readTimeout` 30 s utan framsteg när kroppen läses: en uppladdning som kommer långsamt men
    stadigt tillåts (tidsgränsen gäller per läsning); en som står still stängs. nginx
    `client_body_timeout` 60 s; Tomcat använder `connectionTimeout`. Svar 408.
  - `writeTimeout` 30 s utan framsteg när svaret skrivs. nginx `send_timeout` 60 s; Tomcat har
    ingen motsvarighet för blockerande skrivningar.
  - `shutdownTimeout` 30 s: som Spring Boots `spring.lifecycle.timeout-per-shutdown-phase`.
- **Skrivvakten.** En blockerande `Socket`-skrivning har ingen tidsgräns. Svarets ström
  (`GuardedOutput`) skriver i bitar om högst 16 KiB och noterar när den pågående biten
  började; en virtuell vakttråd per server går igenom anslutningarna var fjärdedel av
  `writeTimeout` (mellan 5 ms och 1 s) och stänger socketen för en skrivning som stått still
  längre än gränsen. Skrivningen kastar då och handlerns tråd blir fri. Varje bit startar om
  klockan, så tidsgränsen mäter tid utan framsteg, inte hela svaret: en långsam men stadig
  läsare klipps inte av (testat). Alternativet, en tråd eller en timer per skrivning, kostar
  mer och ger samma upplösning.
- **`maxConnections`: vänta med accept.** Acceptor-tråden tar en plats i en `Semaphore` innan
  den anropar `accept`; vid gränsen tar den inte emot fler, och nya anslutningar väntar i
  operativsystemets kö (backlog 1 024, begränsas av `somaxconn`) tills en anslutning stängs.
  Klienten ser en lyckad TCP-anslutning som inte får svar förrän det finns plats, eller en
  vägrad anslutning om kön är full. Som Tomcats `maxConnections` + `acceptCount`. Att i stället
  ta emot och stänga direkt hade gett klienten ett fel som inte går att skilja från ett avbrott
  och kostat en accept per försök.
- **Svaret.** Servern sätter `Date` (IMF-fixdate, RFC 9110 §5.6.7, alltid två siffror för
  dagen), `Content-Length` (tom kropp och byte-array) eller `Transfer-Encoding: chunked`
  (strömmande till HTTP/1.1), och `Connection: close` när den stänger. **Reserverade huvuden:**
  `Content-Length`, `Transfer-Encoding`, `Connection` och `Date` i en `Response` ger
  `IllegalArgumentException` (skiftlägesokänsligt), eftersom ramningen och anslutningen måste
  stämma med vad servern faktiskt gör; ett felaktigt `Content-Length` från en handler vore en
  smugglingsväg åt andra hållet. Namn är `token` och värden får bara innehålla HTAB, SP, VCHAR
  och obs-text utan inledande eller avslutande blanktecken; CR, LF och NUL kan därför aldrig nå
  tråden (response splitting). Status 100–199 avvisas (`100 Continue` är serverns sak, ingen
  upgrade), liksom status utanför 200–599. 204, 205 och 304 kräver tom kropp; 204 och 304
  skickas utan `Content-Length`, 205 med `Content-Length: 0` (RFC 9110 §15.3.6). Inget
  `Server`-huvud. `HEAD` går till handlern; servern skickar huvudena (med samma
  `Content-Length` som för `GET` när kroppen är en byte-array) men ingen kropp, och anropar inte
  en `BodyWriter`. Svaret skrivs alltid som `HTTP/1.1` (RFC 9110 §6.2), även till en
  HTTP/1.0-klient.
- **Strömmande svar.** `StreamingBody` buffrar 8 KiB; varje `flush()` och varje full buffert
  blir en chunk (aldrig en tom chunk, som skulle avsluta kroppen). Huvudet skrivs med de första
  byten, så en `BodyWriter` som kastar innan den skrivit något ger ett rent 500. Kastar den
  efter det stängs socketen utan sista chunken: klienten ser ett avbrutet svar, aldrig ett som
  ser komplett ut. Till HTTP/1.0 skickas kroppen rå och avgränsas av att anslutningen stängs.
- **Fel.** Ett protokollfel ger ett kort svar i klartext (`Bad Request\n` och liknande) med
  `Connection: close`; anslutningen stängs. Ett undantag eller `Error` från handlern ger 500
  utan meddelande eller stackspår, loggas på `ERROR` och stänger anslutningen (kroppens
  tillstånd är okänt). Loggningen använder `LoggedThrowable`: en kopia av undantagskedjan där
  klassnamn och meddelanden är escapade (`Syntax.escape`: allt utanför utskrivbar ASCII blir
  `\xHH` eller `\uHHHH`, `\` blir `\\`, högst 200 tecken) och stackspåret är kvar. Protokollfel
  loggas på `DEBUG` med en fast beskrivning, aldrig indata. Ingen loggrad och ingen felkropp
  innehåller alltså styrtecken från en förfrågan (testat med ESC, NUL, CR/LF och C1-tecken).
- **Keep-alive och pipelining.** HTTP/1.1 håller anslutningen öppen om inte klienten skickar
  `Connection: close` eller något gick fel; HTTP/1.0 stängs alltid (även med
  `Connection: keep-alive`). Pipelinade förfrågningar besvaras i ordning, eftersom en tråd
  läser och svarar en i taget från samma buffert. Läser handlern inte hela kroppen läser
  servern resten (inom `maxBodySize` och `readTimeout`) innan nästa förfrågan; går det inte
  stängs anslutningen.
- **Stängning med efterläsning.** Efter ett svar med `Connection: close` stänger servern sin
  skrivriktning (`shutdownOutput`) och läser och kastar det klienten fortfarande skickar, högst
  2 s och 1 MiB, innan socketen stängs. Att stänga en socket med oläst indata får TCP att
  skicka RST, vilket kan förstöra svaret innan klienten läst det (RFC 9112 §9.6); nginx gör
  samma sak (`lingering_close`).
- **Mjukt stopp.** Varje anslutning har ett tillstånd (`IDLE` i väntan på en förfrågan,
  `ACTIVE` under en förfrågan, `LINGERING` efter sista svaret, `CLOSED`) i en
  `AtomicReference`; övergångarna står i en tabell i `Connection.State`. `close()` sätter
  `closing`, stänger `ServerSocket` och väntar in acceptor-tråden (därefter tillkommer inga
  anslutningar), stänger alla anslutningar i `IDLE` (ett compare-and-set, så att en anslutning
  som just fått en förfrågan inte stängs mitt i), och väntar på resten i högst
  `shutdownTimeout`. Anslutningar med en pågående förfrågan ser `closing` och svarar med
  `Connection: close`; deras efterläsning (och den hos anslutningar som redan är i
  `LINGERING`) får pågå, men aldrig efter `shutdownTimeout`, så att svaret inte förstörs av en
  RST (gauntlet-runda 2, BRK-3). Det som återstår avbryts: socketen stängs och tråden avbryts
  (`interrupt`). `close()` returnerar när alla anslutningstrådar är klara, eller senast
  `ABORT_GRACE_MILLIS` (1 s) efter avbrottet: en handler som varken gör I/O eller reagerar på
  avbrott loggas (`WARNING`) och lämnas, i stället för att hålla `close()` kvar för alltid.
- **En keeper-tråd håller JVM:en igång.** En startad server håller JVM:en igång (som Tomcat)
  med en egen plattformstråd som inte är daemon och bara väntar: först på att `close()` är
  klart (spärren `closed`), sedan på de anslutningar som `close()` inte väntade på, alltså den
  vars handler anropade `close()`. Den väntan räcker till shutdown-deadline, men minst
  `ABORT_GRACE_MILLIS` efter att `close()` returnerat; det som då återstår avbryts och får
  `ABORT_GRACE_MILLIS` till. Alla andra servertrådar är daemon. Tidigare var acceptor-tråden
  den enda icke-daemon-tråden, och `close()` väntar in den först: när `main` redan returnerat
  och en handler (en virtuell tråd, alltid daemon) anropade `close()`, avslutades JVM:en innan
  pågående förfrågningar och handlerns eget svar hunnit ut (gauntlet-runda 2, BRK-1). Att låta
  acceptor-tråden vänta på samma sak skulle kräva att `close()` slutade vänta in den; en egen
  tråd håller de två uppgifterna isär. Testat i en egen JVM (`ReturningMainProbe`).
- **`close()` utan monitor.** `close()` är inte `synchronized`. Ett `AtomicBoolean` (`closing`)
  avgör med compare-and-set vem som stoppar servern, och en `CountDownLatch` släpps när det
  första anropet är klart. Ett senare anrop från en anslutnings tråd (en handler) returnerar
  direkt, så att dess svar kan gå ut med `Connection: close`; ett senare anrop från någon annan
  tråd väntar på spärren. Med en monitor fastnade en handler som anropade `close()` medan en
  shutdown hook redan var inne i `close()`: handlern väntade på monitorn (som ett avbrott inte
  når) och hooken väntade på handlerns tråd utan tidsgräns (gauntlet-runda 2, BRK-2). En anslutning som går från `ACTIVE` till `IDLE` läser `closing` efter bytet, och
  `close()` sätter `closing` före genomgången, så antingen ser anslutningen flaggan eller
  `close()` ser `IDLE`. En förfrågan som kommer i samma ögonblick som en ledig anslutning
  stängs kan gå förlorad; klienten ser en stängd keep-alive-anslutning, vilket RFC 9112 §9.3.1
  låter klienter hantera genom att försöka igen.
- **Delat tillstånd.** Servern delar bara räkningen av anslutningar (`Semaphore`), mängden
  anslutningar för mjukt stopp och vakten (`ConcurrentHashMap.newKeySet()`), och flaggan
  `closing`. Inget cachat datum, ingen pool. Allt annat hör till en anslutnings tråd.

## HTTP-server del 2: TLS (`spola.http`)

Uppdrag 07b. HTTPS ovanpå samma kärna som 07a. TLS-protokollet är JDK:ns (`javax.net.ssl`,
REGLER §4: JDK:ns primitiver); det Spola bygger är konfigurationen, inläsningen och
kontrollen av nycklar och certifikat, och kopplingen till anslutningens livscykel.

### API (paket `spola.http`)

| Typ | Roll |
|---|---|
| `TlsOptions` | record: `TlsIdentity identity`, `ClientAuth clientAuth`, `Optional<TlsTrust> clientTrust`, `List<String> protocols`, `List<String> cipherSuites`; fabrikerna `pkcs12`, `pem`, `of(SSLContext)`, `of(TlsIdentity)`; `withProtocols`, `withCipherSuites`, `withClientAuth` |
| `TlsIdentity` | serverns privata nyckel och certifikatkedja (PKCS#12 eller PEM), eller en färdig `SSLContext`; läst och kontrollerad när den skapas |
| `TlsTrust` | betrodda certifikat för klientcertifikat (PKCS#12 eller PEM) |
| `ClientAuth` | `NONE`, `WANT`, `NEED` |
| `TlsInfo` | record: förhandlat protokoll, chiffersvit, klientens certifikatkedja |
| `TlsMaterialException` | `IllegalArgumentException` för felaktigt material, med `file()` |
| `HttpServerOptions.tls`, `withTls`, `withoutTls` | TLS som valfritt värde |
| `HttpTimeouts.handshakeTimeout`, `withHandshakeTimeout` | total tid från accept till klart handslag |
| `Request.scheme()`, `Request.tls()` | `"https"`/`"http"`, och `Optional<TlsInfo>` |

Interna klasser: `TlsLayer` (serverns `SSLContext` och `SSLParameters`, lägger en
`SSLSocket` i serverläge över en accepterad socket), `TlsMaterial` (PKCS#12, certifikat,
nycklar, kontroller, JSSE-managers), `Pem` (strikt PEM-läsare).

### Beslut

- **TLS är ett valfritt värde i optionerna: `Optional<TlsOptions>`, inte en sealed
  `Transport`.** Det finns två fall, med och utan TLS, och det enda som skiljer dem är
  TLS-inställningarna; `Optional` uttrycker det utan en ny hierarki, och `HttpServerOptions`
  behåller sin form (`DEFAULT` utan TLS, `withTls`/`withoutTls`). En sealed `Transport` hade
  lönat sig först med fler transporter (t.ex. Unix-socketar eller HTTP/3), som inte finns. Den
  gamla konstruktorn med fyra komponenter finns kvar (utan TLS), så 07a:s API och beteende är
  oförändrade. En server lyssnar med eller utan TLS; den som vill ha båda startar två.
- **`HttpTimeouts` fick en sjätte komponent, `handshakeTimeout`, sist.** Den gamla
  konstruktorn med fem tidsgränser finns kvar och ger standardvärdet 10 s, så befintlig kod
  (och 07a:s tester) kompilerar och jämför lika som förut.
- **Material läses och kontrolleras när `TlsOptions` (dvs. `TlsIdentity`/`TlsTrust`)
  skapas**, inte vid start och inte vid första anslutningen: saknad fil, fel lösenord, okänt
  alias, keystore utan nyckel eller med flera utan alias, tom kedja, kedja i fel ordning (varje
  certifikats utfärdare ska vara nästa certifikats subjekt), nyckel som inte hör till
  certifikatet. Nyckeln kontrolleras mot certifikatet genom att signera 32 slumpbytes med
  nyckeln och verifiera med certifikatets publika nyckel; det fungerar lika för RSA, EC och
  EdDSA och fångar också EC-nycklar på samma kurva som inte hör ihop. Felen är
  `TlsMaterialException` (en `IllegalArgumentException`) med filens sökväg och en
  förklaring. Meddelandena innehåller aldrig lösenord eller nyckelbytes; undantag från JDK:n
  hängs bara på som orsak för I/O, keystore-inläsning och certifikat (publik data), aldrig för
  nyckelparsning eller Base64 (JDK:ns meddelande för felaktig Base64 visar ett tecken ur
  blocket). Ett tabelltest med 32 fall kontrollerar meddelande, orsakskedja, stackspår och
  `toString()` mot lösenorden och mot nycklarnas Base64, hex och råa bytes.
- **Lösenord.** `char[]` kopieras in, används och nollställs i `finally`; anroparens array
  rörs inte. Nyckeln plockas ut ur keystoren och läggs i en ny keystore i minnet under ett
  slumpat lösenord (nollställt efteråt), som en `SunX509`-`KeyManagerFactory` läser en gång vid
  skapandet; varken användarens lösenord eller keystoren behålls. `SunX509` (JDK:ns
  standardalgoritm) i stället för `PKIX` eftersom den kopierar ut nyckeln vid `init`, så inget
  handslag behöver dekryptera den igen. Utan alias krävs exakt en privat nyckel i keystoren;
  med flera ger felet en lista över alias (escapade). Separata nyckellösenord stöds inte
  (keytool och openssl skriver PKCS#12 med samma lösenord).
- **PEM utan preview-API.** JDK 25:s PEM-API (JEP 470) är preview och får inte användas
  (REGLER §3). `Pem` läser ramarna strikt enligt RFC 7468: bara ASCII-text, rader som slutar
  med LF eller CRLF, `-----BEGIN label-----` och `-----END label-----` med samma etikett,
  inget nästlat BEGIN, inget END utan BEGIN, bara Base64-tecken inne i ett block (avslutande
  blanktecken tas bort, tomma rader och `Proc-Type`-huvuden avvisas), strikt avkodning med
  `Base64.getDecoder()`. Blockets Base64 måste vara utfylld (`=`), alltså en längd som är en
  multipel av fyra (RFC 7468 §3, strikt grammatik): `getDecoder()` godtar annars Base64 utan
  utfyllnad, och ett block som tappat ett tecken kunde då avkodas till skräp i stället för att
  avvisas som ogiltig Base64. Text utanför blocken tillåts (RFC 7468 §5.2; openssl skriver t.ex.
  `Bag Attributes`). Certifikat läses med `CertificateFactory` (X.509), nyckeln som okrypterad
  PKCS#8 (`PRIVATE KEY`) med `KeyFactory` och `PKCS8EncodedKeySpec`; algoritmen avgörs av
  nyckeln genom att `RSA`, `EC` och `EdDSA` prövas i tur och ordning. `ENCRYPTED PRIVATE KEY`,
  `RSA PRIVATE KEY`, `EC PRIVATE KEY` och `DSA PRIVATE KEY` avvisas med kommandot för
  konvertering (`openssl pkcs8 -topk8 -nocrypt`). Filer över 1 MiB avvisas. Avkodade bytes och
  Base64-buffertar nollställs efter användning (så långt det går: texten som `String` kan inte
  nollställas).
- **Protokoll och chiffersviter.** Standard `TLSv1.3` och `TLSv1.2`, och bara de är tillåtna:
  äldre namn (`TLSv1.1`, `TLSv1`, `SSLv3`, `SSLv2Hello`) ger `IllegalArgumentException` med
  "older than TLS 1.2", okända namn (också fel skiftläge, `DTLSv1.2`) ger samma undantagstyp.
  Chiffersviter: standard är JDK:ns standardsviter för de valda protokollen (ingen egen
  lista; JDK:n uppdaterar den och `jdk.tls.disabledAlgorithms` gäller). En uttrycklig lista
  kontrolleras mot de sviter kontexten stöder, svaga sviter (anon, NULL, EXPORT, RC4, DES,
  3DES, MD5, SCSV) avvisas, och minst en svit måste passa något av protokollen (TLS 1.3-sviter
  saknar `_WITH_`), annars skulle varje handslag misslyckas. Serverns ordning gäller
  (`setUseCipherSuitesOrder(true)`).
- **ALPN: bara `http/1.1`.** `SSLParameters.setApplicationProtocols({"http/1.1"})`. JDK:ns
  server väljer `http/1.1` när klienten erbjuder det (också tillsammans med `h2`), svarar med
  `no_application_protocol` när klienten bara erbjuder annat (ingen tyst HTTP/1.1 till en
  klient som inte talar det), och förhandlar utan ALPN när klienten inte skickar något.
  `HttpClient` med standardversion (HTTP/2 föredras) får därför HTTP/1.1 (testat).
- **Handslaget på anslutningens tråd med en total tidsgräns.** Acceptor-tråden gör bara
  `accept()` som förut (plattformstråd, vanlig `ServerSocket`); anslutningens virtuella tråd
  lägger en `SSLSocket` över den accepterade socketen
  (`SSLSocketFactory.createSocket(socket, null, true)`, serverläge) och anropar
  `startHandshake()` innan första förfrågan läses. `handshakeTimeout` (standard 10 s, som
  `headerTimeout`) räknas från accept: varje läsning får högst tiden som är kvar
  (`setSoTimeout`), och vakttråden (samma som för `writeTimeout`) stänger TCP-socketen när
  deadline passerat, så att en klient som skickar handslaget en byte i taget kopplas ned
  (testat med en riktig ClientHello en byte var 10 ms). Vaktens period är en fjärdedel av den
  kortaste av `writeTimeout` och `handshakeTimeout` (5 ms–1 s); över TLS ingår också läsningarnas
  tidsgränser (se nästa punkt). Anslutningen finns i
  serverns mängd och har sin plats i `maxConnections` från accept, så en anslutning i handslag
  räknas (testat). Ett misslyckat handslag (vanlig HTTP, skräp, fel version, okänt
  certifikat, avbrutet, tidsgräns) stänger bara den anslutningen och loggas på `DEBUG` med
  en fast text och JDK-undantagets escapade klass och meddelande; JDK:ns meddelanden innehåller
  inte indata. Ingen HTTP-felkod skickas till en klient som talar HTTP till TLS-porten (Tomcat
  gör det inte heller som standard).
- **TLS ändrar inte HTTP-semantiken.** Efter handslaget får `ConnectionInput` och
  `GuardedOutput` TLS-socketens strömmar; allt annat är 07a:s kod. `setSoTimeout` på en
  `SSLSocket` i lager går till TCP-socketen, och en `SocketTimeoutException` lämnar
  TLS-socketen användbar (testat i JDK 25). Men `soTimeout` räcker inte ensam över TLS: en
  läsning ur `SSLSocket` blockerar tills en hel TLS-post har kommit och kan vänta på TCP-data
  många gånger, varje gång med hela `soTimeout`, så en klient som skickar en post en byte i
  taget skulle aldrig kopplas ned. Därför publicerar `ConnectionInput` vid varje läsning när
  den senast ska ha returnerat (deadline för huvudet, annars nu + tidsgränsen per läsning:
  `idleTimeout`, `readTimeout`, efterläsningens tid), och för TLS-anslutningar stänger
  vakttråden TCP-socketen under en läsning som passerat den (`Connection.checkRead`).
  Läsningen kastar då `SocketTimeoutException` som en vanlig sockets läsning, så resten av
  koden är 07a:s; men socketen är stängd, så en klient som kopplas ned mitt i huvudet får
  inget `408` över TLS (vanlig HTTP får det). Vaktens period är för TLS en fjärdedel av den
  kortaste av alla tidsgränser (5 ms–1 s). En följd: över TLS måste en hel post (högst
  16 KiB) komma inom `readTimeout`, medan vanlig HTTP bara kräver någon byte inom den; med
  standardvärdet 30 s motsvarar det under 5 kbit/s. Testat: en förfrågan en byte var 20 ms
  kopplas ned vid `headerTimeout`, `idleTimeout` och efterläsningens 2 s. Ett urval av 07a:s tester körs över TLS genom att samma testfall tar en
  `Transport` (vanlig eller TLS): anslutningar (keep-alive, pipelining, HTTP/1.0 m.m.),
  chunked, `100-continue`, var tredje fall ur båda tabellerna med felaktiga förfrågningar,
  smuggelförsöken och mjukt stopp.
- **Stängning över TLS.** Avbrott (skrivvakten, handslagets deadline, `shutdownTimeout`)
  stänger TCP-socketen under TLS-lagret: det blockerar aldrig och väcker den blockerade
  tråden. En ordnad stängning går genom `SSLSocket` och skickar `close_notify`: efter
  `Connection: close` med `shutdownOutput()` (sedan efterläsning som förut) och när
  anslutningen slutar med `close()`. Båda kan blockera på en klient som inte läser, så de körs
  som en bevakad skrivning (`GuardedOutput.guard`): vakten stänger socketen efter
  `writeTimeout`. Mjukt stopp av en ledig TLS-anslutning stänger TCP-socketens läsriktning
  (`shutdownInput`) i stället för socketen: anslutningens egen tråd vaknar, ser att den ska
  sluta och stänger med `close_notify`; `close()` gör alltså ingen skrivning som kan blockera
  (en anslutning i handslag stängs på samma sätt).
- **`Request.tls()` och `scheme()`.** `TlsInfo` (protokoll, svit, klientens kedja som
  `List<X509Certificate>`, tom utan klientcertifikat) skapas från `SSLSocket.getSession()` för
  varje förfrågan, så den visar läget efter en eventuell omförhandling. `scheme()` är
  `"https"` över TLS. En absolute-form-target med `http://` över TLS avvisas inte (RFC 9112
  §3.2.2 säger inget om det), och `scheme()` beskriver anslutningen, inte targeten (testat).
- **mTLS.** `NONE` (standard) begär inget certifikat; `WANT` begär men kräver inte; `NEED`
  kräver. `WANT` och `NEED` kräver en `TlsTrust` (PKIX `TrustManagerFactory` över de betrodda
  certifikaten, ingen spärrkontroll utöver JDK:ns standard), utom för `TlsOptions.of(SSLContext)`
  där kontextens egna trust managers används; en `TlsTrust` till en sådan, eller till `NONE`,
  ger `IllegalArgumentException` i stället för att tyst ignoreras. Med `WANT` ger ett skickat
  men ej betrott certifikat ett misslyckat handslag (JDK:ns beteende, testat). Testerna
  skickar det ej betrodda certifikatet med en egen `X509ExtendedKeyManager`, eftersom JDK:ns
  key managers på klientsidan inte skickar ett certifikat vars utfärdare servern inte nämnt
  (annars hade testet bara visat en klient utan certifikat).
- **Modulen sätter inga globala egenskaper.** Inga system- eller säkerhetsegenskaper sätts
  (testat i källkoden). Klientinitierad omförhandling i TLS 1.2 styrs bara av JDK:ns globala
  `jdk.tls.rejectClientInitiatedRenegotiation` (standard `false`); README rekommenderar
  `-Djdk.tls.rejectClientInitiatedRenegotiation=true` eller enbart TLS 1.3. Ingen omladdning av
  certifikat i drift och ingen SNI med flera certifikat (ett per server).
- **Testernas certifikat skapas av testerna.** JDK:n har inget publikt API för att utfärda
  X.509-certifikat och `sun.security.x509` exporteras inte. `TestPki` kör `keytool` från
  `java.home` med `ProcessBuilder` i en temporär katalog, i två omgångar parallella processer:
  först en CA (RSA, `bc:c`) och en självsignerad "rogue"-klient (EC), sedan certifikat som
  CA:n utfärdar med `-genkeypair -signer ca` (i kopior av CA:ns keystore): server RSA, EC och
  Ed25519 med SAN `IP:127.0.0.1,DNS:localhost`, en klient, och en server för ett annat namn
  (för att visa att `HttpClient` kontrollerar namnet). `KeyStore`-API:t delar sedan upp
  keystorerna och skriver PEM-filerna (`getEncoded()` + Base64). Materialet skapas en gång
  per testkörning (fixture på TLS-sviten) och tas bort efteråt. Klienterna får en egen
  `SSLContext` som bara litar på testets CA (inga `javax.net.ssl.*`-egenskaper), med
  värdnamnskontroll påslagen. Inga nycklar eller certifikat finns i repot (guardrails tillåter
  ändå bara `.java` och `.md`).

## Web del 1: routing som kod, parametrar, filter och felhantering (`spola.web`)

Uppdrag 08a. Rad 08 i `PLAN.md` (routing som kod, request/response-modell, felhantering,
JSON-integration) är delad av planeraren i två uppdrag: **08a** (detta: routing,
procentavkodning, sökvägs- och queryparametrar, filter, attribut, felhantering och
problem-svar) och **08b** (JSON-kroppar via `spola.json.codec`, `Content-Type`/`Accept` med
415/406, cookies och formulärkroppar). Skälet är att routing med felhantering redan är ett
fullt uppdrag och att sökvägsavkodningen är säkerhetskritisk.

### API (paket `spola.web`)

| Typ | Roll |
|---|---|
| `Router` | `spola.http.Handler`, oföränderlig och trådsäker; `Router.builder()` |
| `RouterBuilder` | `get`/`post`/`put`/`patch`/`delete`/`route(method, pattern, handler)`, `filter`, `group(prefix, Consumer<RouteGroup>)`, `onException(Class<E>, ExceptionMapper<? super E>)`, `build()` |
| `RouteGroup` | det en grupp kan registrera: routes, filter, nästlade grupper (inga mappers) |
| `WebHandler` | `Response handle(WebRequest) throws Exception` |
| `WebRequest` | oföränderlig: `http()`, `method()`, avkodad `path()`, `pathSegments()`, `pattern()`, `pathParam(name[, Param])`, `pathParamSegments(name)`, `query(name[, Param])`, `queryAll`, `requiredQuery`, `queryParams()`, `headers()`, `body()`/`bytes()`, `attribute(key)`, `withAttribute(key, value)` |
| `Param<T>` | `STRING`, `INT`, `LONG`, `BOOLEAN`, `UUID`, `LOCAL_DATE`, `BIG_DECIMAL`, `enumOf(Class)`, `of(typnamn, Function)` |
| `AttributeKey<T>` | `of(name, Class<T>)`, identitetsbaserad |
| `Filter`, `Filter.Chain` | `Response filter(WebRequest, Chain next)`, `next.proceed(request)` |
| `ExceptionMapper<E>` | `Response map(E, WebRequest) throws Exception` |
| `WebException` | okontrollerad, inte `final`: status 400–599, titel, valfri detalj, huvuden (`Allow` på 405); `problem(instance)`, `toResponse(request)` |
| `Problem` | record för RFC 9457: `type`, `title`, `status`, `detail`, `instance`, `extensions` (`JsonObject`); `of`, `withX`, `toJson()`, `toResponse()` |
| `RouterDefinitionException` | `IllegalArgumentException` från `build()` med `problems()` |

Interna klasser: `PathPattern` (mönstret och dess segment som sealed hierarki), `Route`,
`RouteTable` (trädet och uppslaget), `Decoding` (procentavkodning, sökvägskontroll,
queryparsning), `QueryState` (lat query per förfrågan), `Text` (escaping och kortning),
`LoggedThrowable` (undantag som är säkra att logga).

Avvikelser från namnen i uppdraget: gruppens lambda får en `RouteGroup`, inte en
`RouterBuilder`, så att `onException` och `build()` inuti en grupp är kompileringsfel i
stället för körfel (REGLER §4). `pathParamSegments` är catch-allens lista (se nedan) och
`pattern()` ger den matchade routens mönster (för loggning och mått i senare uppdrag).

### Beslut

- **Beroenden.** `requires transitive spola.http` (API:t använder `Request`, `Response` och
  `Headers`) och `requires transitive spola.json`. Uppdraget anger `requires spola.json`;
  beroendet är transitivt eftersom `Problem` (publikt API) bär sina extra medlemmar som
  `JsonObject` och javac:s lint `exports` (en del av `-Xlint:all -Werror`) annars ger rött
  bygge; en applikation som bygger ett `Problem` med extra medlemmar behöver ändå läsa
  `spola.json`. Inga andra ramverksmoduler. `requires static java.net.http` bara för
  testerna (som i `spola.http`). Ingen reflektion: huvudkoden importerar inget från
  `java.lang.reflect` eller `java.lang.invoke` (testat). Det enda anropet i närheten är
  `Class.getEnumConstants()` i `Param.enumOf`, en gång när parametern skapas (formen
  `enumOf(Class)` står i uppdraget); varje förfrågan slår upp i en färdig `Map`.
- **Mönstersyntax.** Ett mönster börjar med `/`; segmenten är literal, variabel `{namn}`
  (ett helt segment, namnet en Java-identifierare, unikt i mönstret) eller, bara sist,
  catch-all `{namn...}`. `/` är roten. Ett mönster som slutar med `/` matchar bara sökvägar
  som slutar med `/` (det sista segmentet är då en tom literal). Inga reguljära uttryck,
  jokertecken (`*`, `?` avvisas med ett tydligt meddelande, eftersom en Spring-van läsare
  annars tror att de fungerar), delsegment, `%` eller `.`/`..`-segment. En literal jämförs
  med det avkodade segmentet, så `/café` matchar `/caf%C3%A9`; tecken som ingen sökväg kan
  innehålla efter kontrollen (styrtecken, `\`, ensamma surrogat) avvisas vid `build()`.
- **Grupper.** Prefixet är ett mönster utan avslutande `/`; routens mönster läggs till
  efter prefixet och `/` i en grupp betyder prefixet självt (`group("/users", g ->
  g.get("/", ...))` är `/users`). Variabler i prefixet räknas med vid kontrollen av unika
  namn och form; en catch-all i prefixet är fel om gruppen har routes under den.
- **Validering vid `build()`.** Alla problem samlas och kastas i en
  `RouterDefinitionException` (en `IllegalArgumentException`), en rad per problem med
  metod och mönster, i registreringsordning (samma princip som `Container.create`).
  Problem: felaktiga mönster och gruppprefix (ett felaktigt prefix rapporteras en gång, och
  routes i gruppen kontrolleras då bara mot sina egna mönster), metod som inte är `token`
  (RFC 9110 §5.6.2), två routes med samma metod och form (formen är mönstret med variabler
  och catch-all utan namn), två mappers för samma klass, och en mapper för en `Error`-klass
  (fel mappas aldrig, se nedan). Null-argument ger `NullPointerException` direkt, och en
  grupp som används efter sin lambda ger `IllegalStateException` (programmeringsfel).
- **Trie-uppslag.** `RouteTable` är ett träd med en nivå per segment: varje nod har
  literal-barn i en `HashMap` (nyckel: avkodad text), högst ett variabel-barn, routes som
  slutar i noden och catch-all-routes under noden, båda per metod. Allt är oföränderligt
  efter `build()` (`Map.copyOf`). Uppslaget går från vänster och provar literal, sedan
  variabel, sedan catch-all, och backtrackar när en gren inte har någon route för metoden.
  Den första route som hittas i den ordningen är den mest specifika för sökvägen och metoden
  (`/a/b/{y}` före `/a/{x}/c` för `/a/b/c`; `GET /m/lit` når `/m/{v}` om literalen bara har
  `POST`). Arbetet begränsas av trädets noder längs sökvägen (en nod besöks högst en gång,
  rekursionsdjupet är högst det längsta mönstrets längd), inte av antalet routes, och
  resultatet beror inte på registreringsordningen eftersom trädet inte gör det och samma form
  två gånger är förbjudet. Hittas ingen route samlar samma genomgång metoderna för alla
  routes som matchar sökvägen: tom mängd ger 404, annars 405 (eller automatiskt `OPTIONS`).
  En variabel matchar aldrig ett tomt segment och en catch-all aldrig ett tomt segment
  (avslutande `/`), så avslutande snedstreck är strikt även där.
- **Catch-all.** `pathParam(namn)` ger de avkodade segmenten med `/` emellan (entydigt,
  eftersom ett avkodat segment aldrig innehåller `/`), `pathParamSegments(namn)` ger dem som
  lista. En catch-all kräver minst ett segment och inga tomma.
- **`HEAD`, `OPTIONS`, 404, 405.** I varje träffad nod: exakt metod, annars för `HEAD`
  `GET`-routen i samma nod (servern skickar ingen kropp). `Allow` är unionen av metoderna,
  plus `HEAD` när `GET` finns och alltid `OPTIONS`, sorterad och skriven med `", "`.
  `OPTIONS` utan route för metoden ger 204 med `Allow`; en egen `OPTIONS`- eller
  `HEAD`-route går före. `OPTIONS *` ger 404. Metoder jämförs skiftlägeskänsligt (`get` är
  en annan metod än `GET`).
- **Strikt sökväg, 400 före routing.** Den råa sökvägen (redan kontrollerad mot RFC 3986 av
  `spola.http`) delas på `/`, sedan avkodas varje segment en gång: `%XX` till byte, sedan
  JDK:ns UTF-8-avkodare med `CodingErrorAction.REPORT` (ofullständiga sekvenser, överlånga
  kodningar, kodade surrogat och allt över U+10FFFF ger fel; testat mot gränserna i RFC 3629
  §4). Ett avkodat segment med `/`, `\` eller styrtecken (U+0000–U+001F, U+007F), segmenten
  `.` och `..` (även kodade) och tomma segment utom ett sista (avslutande `/`) ger 400.
  Ingen dubbelavkodning: `%252F` är texten `%2F`. C1-tecken och andra Unicode-tecken är
  tillåtna i sökvägen men escapas i loggar. Den 400 som routern ger är en `WebException`
  som kastas innanför routerns filter, så att filter (CORS, säkerhet) också ser den; för en
  sådan förfrågan är `path()` den råa sökvägen och `pathSegments()` tom.
- **Queryparametrar.** `application/x-www-form-urlencoded` enligt WHATWG URL Standard §5.1:
  `&` skiljer par, tomma par hoppas över, första `=` skiljer namn och värde (inget `=` ger
  tomt värde), `+` blir mellanslag före procentavkodningen (så `%2B` är `+`), strikt UTF-8
  som för sökvägen. Namnen behåller ordningen de först förekommer i, värdena sin ordning.
  Avkodade styrtecken är data och tillåts i värden; de escapas i loggar och problem-detaljer.
  Högst 1 000 par (Tomcats `maxParameterCount` i Spring Boot 3.x), tomma par räknas inte;
  fler ger 400. **Lat:** parsningen görs första gången något läser queryn och högst en gång
  per förfrågan (`QueryState`, delad av alla `WebRequest` som härleds med `withAttribute`,
  `synchronized` eftersom en `WebRequest` får lämnas till andra trådar). Ett fel sparas och
  ges igen vid varje senare läsning. Följden är att en ogiltig query som ingen läser inte ger
  400.
- **Typade parametrar.** `Param` är en klass med typnamn och funktion. De inbyggda är
  strikta och skrivna för hand i stället för att lita på JDK:ns tolerans:
  `Integer.parseInt` godtar `+5` och icke-ASCII-siffror, `UUID.fromString` godtar
  `1-1-1-1-1`, `new BigDecimal` godtar exponenter (och därmed `1e999999999`). `INT`/`LONG`:
  `-?[0-9]+` inom intervallet; `BOOLEAN`: `true`/`false`; `UUID`: 36 tecken `8-4-4-4-12`;
  `LOCAL_DATE`: `yyyy-MM-dd` och ett riktigt datum; `BIG_DECIMAL`: `-?[0-9]+(\.[0-9]+)?`,
  högst 100 tecken; enum: exakt namn. Ett undantag från funktionen blir `WebException` 400
  med parameterns namn, typnamnet och värdet (citerat, högst 40 tecken, escapat); funktionens
  eget meddelande visas inte. En funktion som returnerar `null` är ett programmeringsfel
  (500). `requiredQuery` utan värde ger 400. `pathParam` med ett namn som inte finns i den
  matchade routens mönster ger `IllegalArgumentException` (500).
- **Attribut som typade nycklar på en oföränderlig request.** `AttributeKey<T>` jämförs på
  identitet (två nycklar med samma namn är olika) och bär en `Class<T>` som värdet
  kontrolleras mot i `withAttribute` (primitiva typer avvisas, eftersom deras `Class` inte
  kan kontrollera ett värde). `withAttribute` ger en ny `WebRequest` med en kopierad
  `Map.copyOf`; originalet ändras aldrig, så inget tillstånd delas mellan förfrågningar eller
  mellan filter som får samma request.
- **Filtrens omfång och ordning.** Kedjan för en förfrågan är: routerns filter (i
  registreringsordning, det första ytterst), sedan filtren för routens grupper (yttersta
  gruppen först, inom en grupp i registreringsordning, oavsett om filtret lades till före
  eller efter gruppens routes), sedan handlern. Routerns filter körs för varje förfrågan,
  även 400 från sökvägskontrollen, 404, 405, automatiskt `OPTIONS` och `OPTIONS *`; en grupps
  filter bara för gruppens routes (en 405 eller 404 under gruppens prefix når dem inte,
  eftersom ingen route i gruppen matchade). **Routingen avgörs före filtren:** ett filter kan
  inte byta route (`WebRequest` har ingen `withPath`), men det ser den matchade routens
  variabler och `pattern()`.
- **Undantag mappas där de uppstår.** Varje steg i kedjan (filter eller handler) körs i ett
  `try`; ett undantag blir ett svar i samma steg, och filtren utanför ser ett vanligt svar
  (som Spring, där `@ControllerAdvice` svarar innanför servlet-filtren). Därför kan ett
  yttre filter lägga till huvuden (CORS) även på felsvar, och ett undantag från ett filter
  går till samma mappers. Ett `null`-svar från filter, handler eller mapper är ett
  programmeringsfel och behandlas som ett undantag.
- **Valet av mapper.** Den mapper vars klass ligger närmast undantagets klass i
  superklasskedjan vinner, oberoende av registreringsordningen. Utan mapper: en
  `WebException` blir `toResponse(request)` (problem-svaret med dess huvuden, t.ex. `Allow`);
  en `RequestBodyException`, eller ett undantag med en sådan i orsakskedjan, kastas vidare
  till `spola.http`, som svarar med kroppens status och stänger anslutningen; allt annat blir
  500 med ett problem-svar utan meddelande, loggat på `ERROR` (logger `spola.web`) med metod
  och sökväg, escapat och kortat. En `RequestBodyException` går bara till en mapper för
  exakt `RequestBodyException`, aldrig till en för `IOException` eller `Exception`: en
  allmän mapper ska inte göra en 413 till en 500 (servern stänger anslutningen ändå). Kastar
  mappern (eller `toResponse` i en subklass) blir det 500 utan att någon mapper anropas igen,
  och felet loggas. `Error` fångas inte: det går till `spola.http` (500 utan detaljer), och
  mappers för `Error`-klasser avvisas vid `build()`.
- **Problem-formatet (RFC 9457).** `Content-Type: application/problem+json`, skrivet med
  `spola.json`. Medlemmarna i ordning: `type` (standard `about:blank`), `title` (statusens
  fras enligt RFC 9110 §15 när ingen annan anges; okända koder får `Client Error`/`Server
  Error`), `status`, `detail` (bara när den finns), `instance` (routern sätter den avkodade
  sökvägen, som JSON-skrivaren escapar), sedan extra medlemmar. Status 400–599 (en problem
  är ett fel; 1xx–3xx avvisas). `title`, `detail`, `instance` och varje sträng i de extra
  medlemmarna (på alla nivåer) kortas till högst 2 000 tecken (de sista tre `...`, aldrig mitt
  i ett surrogatpar), och ensamma surrogat blir U+FFFD så att varje `Problem` kan skrivas som
  JSON. Extra medlemmar med standardnamn avvisas.
- **Loggning.** Bara 500 loggas (`ERROR`, `System.Logger` med namnet `spola.web`). Raden
  innehåller metod och avkodad sökväg via `Text.escape` (allt utanför utskrivbar ASCII blir
  `\xHH`/`\uHHHH`, `\` och `"` escapas, högst 200 tecken), och undantaget loggas som en
  `LoggedThrowable`: en kopia av orsakskedjan med escapade klassnamn och meddelanden och
  kvarvarande stackspår (samma teknik som i `spola.http`, egen kopia eftersom `spola.http`
  inte exporterar sin). Ingen loggrad innehåller alltså styrtecken från förfrågan, även när
  sökvägen eller ett parametervärde innehåller `%0A`, `%0D`, `%1B` eller C1-tecken (testat).
  Testerna fångar loggen via `RouterBuilder.log(System.Logger)` (paketprivat i 08a, publik
  sedan 10b så att också andra modulers tester kan fånga routerns loggrader).
- **Inga ändringar i `spola.http`.** Allt uppdraget behöver finns i dess publika API.
- **Tester** går genom en riktig `HttpServer` (127.0.0.1, port 0) med `HttpClient` eller en
  rå socket (för sökvägar som `HttpClient` inte skickar oförändrade), eftersom
  `spola.http.Request` bara skapas av servern.

## Web del 2: JSON-kroppar, mediatyper och innehållsförhandling (`spola.web`)

Uppdrag 08b. Planeraren delade det tidigare 08b i två: **08b** (detta: `MediaType`,
`Accept`, innehållsförhandling, JSON-kroppar in och ut, 415, 406, 413 och problem-svar för
kroppsfel) och **08c** (cookies in och `Set-Cookie` ut, formulärkroppar
`application/x-www-form-urlencoded`). Multipart ligger utanför båda. Skälet är att varje
uppdrag ska bli lagom stort: tolkningen av mediatyper och `Accept` är egen parsning av extern
data och behöver sina egna tester.

### API (paket `spola.web`)

| Typ | Roll |
|---|---|
| `MediaType` | record (`type`, `subtype`, ordnade `parameters`): `parse`, `of`, `APPLICATION_JSON`, `APPLICATION_PROBLEM_JSON`, `TEXT_PLAIN`, `ALL`, `includes`, `isJson`, `isWildcard`, `charset`, kanonisk `toString` |
| `AcceptHeader` | `parse(String)`, `parse(List<String>)` (flera fält), `ANY`, `ranges()` (`Range(range, quality)` i tusendelar), `quality(MediaType)`, `negotiate(List<MediaType>)` |
| `WebRequest` (nytt) | `contentType()`, `accept()`, `negotiate(MediaType...)`, `readJson(codec)`, `readJson(codec, limits)`; `body()`/`bytes()` markerar kroppen som läst |
| `RouterBuilder` (nytt) | `jsonLimits(JsonLimits)` |
| `Responses` | `json(request, status, codec, value)`, `json(request, status, JsonValue)`, `created(request, location, codec, value)`, `noContent()` |
| `Problem` (nytt) | `FieldError(path, message)`, `withErrors(List<FieldError>)`, `MAX_ERRORS` |
| `WebException` (nytt) | `errors()`, skyddad konstruktor med `errors` |

Interna klasser: `MediaTypeParser` (den strikta parsern, delad av `MediaType` och
`AcceptHeader`), `JsonBody` (reglerna i `readJson` och den begränsade strömmen
`LimitedInput`), `BodyState` (vem som läst kroppen, routerns `JsonLimits`; delas av alla
`WebRequest` från samma förfrågan som `QueryState`).

### Beslut

- **Inga nya beroenden.** `module-info.java` är oförändrad: allt finns i `spola.http` och
  `spola.json` (redan `requires transitive`). Ingen reflektion. `Accept`-huvudets namn blev
  `AcceptHeader` (uppdraget tillåter ett motsvarande namn) så att det inte förväxlas med
  huvudets namn som sträng.
- **Ett litet tillägg i `spola.json`:** `JsonMappingException.problemCount()` ger antalet
  hittade problem, även de som inte listas. Utan det kan `readJson` inte ange antalet i
  detaljen när det är fler än 100 (`problems()` har då 100 poster plus en sammanfattning vars
  antal bara finns i texten, och att tolka den vore skört). Den befintliga konstruktorn finns
  kvar och ger `problems.size()`; `decode` skickar med räknaren från `Decoding`. Inga
  befintliga tester är ändrade.
- **`MediaType`-syntaxen (RFC 9110 §8.3.1) strikt.** `type "/" subtype *( OWS ";" OWS
  parameter )`; typ, subtyp och parameternamn är `token` (§5.6.2), värdet `token` eller
  `quoted-string` (§5.6.4) med `\`-escape; OWS är mellanslag och tab. Typ, subtyp och
  parameternamn görs till gemener. Parametervärden behåller skiftläget, utom `charset`, vars
  värde görs till gemener redan vid parsningen så att `equals` och `charset()` blir
  okänsliga för skiftläge. Fel (`IllegalArgumentException`, kort meddelande i utskrivbar
  ASCII som citerar högst 40 tecken av indata, escapat): tom indata, saknad typ, `/` eller
  subtyp, `*` som typ med en konkret subtyp, en tom parameter (`;;`, avslutande `;`; RFC
  9110 tillåter tomma parametrar men uppdraget kräver `parameter` efter varje `;`), en
  parameter utan `=` eller utan värde, samma namn två gånger (utan hänsyn till skiftläge),
  styrtecken (allt under U+0020 utom tab, och U+007F), icke-ASCII (även obs-text, som RFC
  9110 tillåter i citerade värden), ett oavslutat citat, blanktecken först eller sist, fler än
  **64 parametrar** och fler än **1 024 tecken**. Längden kontrolleras först, sedan alla tecken,
  sedan grammatiken; parsningen är linjär. Den kanoniska formen (`toString`) skriver
  `;namn=värde` utan mellanslag och citerar värden som inte är `token`, och parsar tillbaka
  till samma värde. Den kanoniska konstruktorn kontrollerar och normaliserar på samma sätt
  (record-invarianten gäller även för `new MediaType(...)`).
- **`includes` med parametrar.** Ett intervall innehåller en typ när typ och subtyp är `*` eller
  lika och varje parameter i intervallet finns med samma värde i typen; typens extra
  parametrar spelar ingen roll. Det behövs för regeln "med parametrar före utan" i
  `Accept` (`text/html;level=1;q=0` får inte utesluta `text/html`). En konkret typ innehåller
  aldrig ett jokertecken.
- **`charset=utf-8` i ett intervall godtar JSON utan `charset` (08b runda 2).** Ett intervall
  med `charset=utf-8` (efter gemener, alltså även `UTF-8` och citerat `"UTF-8"`) innehåller
  en JSON-typ (`isJson()`) som saknar `charset`. JSON är UTF-8 per definition (RFC 8259 §8.1)
  och Spola skriver alltid JSON som UTF-8, så `Accept: application/json; charset=utf-8`
  (OkHttp/Retrofit, Springs `APPLICATION_JSON_UTF8_VALUE` i Feign/RestTemplate) ska ge 200,
  inte 406. Intervallet behåller sin högre specificitet. Undantaget ligger i `includes` (inte
  bara i `AcceptHeader`) så att den publika metoden och förhandlingen aldrig säger olika saker.
  Alla andra `charset`-värden utesluter fortfarande (`charset=iso-8859-1` innehåller inte
  `application/json`, men ett `*/*` i samma huvud kan ta den). Regeln gäller bara JSON: för
  `text/plain` vet `includes` inte vilken kodning svaret får, så `text/plain;charset=utf-8`
  innehåller inte `text/plain`; den som vill erbjuda det får erbjuda
  `text/plain;charset=utf-8` uttryckligen.
- **`isJson`:** `application/json` eller `application/<något>+json` (strukturerat suffix,
  RFC 6839), oavsett parametrar. `application/+json`, `text/json` och `application/json-patch`
  är inte JSON.
- **`Accept` (RFC 9110 §12.5.1).** Huvudet delas på komma utanför citerade strängar (en
  `quoted-pair` avslutar aldrig citatet; ett oavslutat citat går till slutet och rapporteras
  av elementets parser). Varje element parsas med samma parser som `MediaType`, med samma
  gränser per element, plus vikten: den första parametern som heter `q` (utan hänsyn till
  skiftläge) är vikten, `qvalue = "0" [ "." 0*3DIGIT ] / "1" [ "." 0*3("0") ]` exakt (så
  `q=1.`, `q=0.` och `q=1.000` godtas; `q=1.5`, `q=-1`, `q=0.0001`, `q=1.0000`, `q=abc` och
  ett citerat värde är fel). Parametrar före `q` hör till intervallet; parametrar efter `q`
  (accept-ext från RFC 7231, `token [ "=" värde ]`) kontrolleras och ignoreras, även ett andra
  `q`. Tomma element (`a, ,b`) hoppas över (§5.6.1.2). Flera `Accept`-fält slås ihop i ordning
  (§5.3). Högst **100 intervall** sammanlagt; det 101:a ger fel innan det parsas. Vikten lagras
  som heltal i tusendelar så att jämförelser blir exakta.
- **Saknat eller tomt `Accept`.** Inget `Accept`-fält, eller bara tomma element, betyder att
  allt godtas med kvalitet 1 (§12.5.1; Spring gör samma sak med ett tomt huvud).
- **Specificitetsregeln.** Kvaliteten för en erbjuden typ är vikten hos det mest specifika
  intervallet som innehåller den. Specificiteten är först nivån (`*/*` lägst, sedan `type/*`,
  sedan `type/subtype`), sedan antalet parametrar. Ordningen i huvudet spelar ingen roll.
  Inget intervall som innehåller typen ger kvalitet 0, och `q=0` betyder "inte godtagbar"
  även när `*/*` skulle godta typen.
- **Oavgjorda fall.** (1) Två lika specifika intervall som båda innehåller typen
  (`text/plain;q=0.4, text/plain;q=0.6`, eller `text/html;a=1` och `text/html;b=2` för
  `text/html;a=1;b=2`): den högsta vikten gäller, så att resultatet inte beror på ordningen i
  huvudet. (2) Två erbjudna typer med samma kvalitet: den som står först i
  `negotiate(...)` vinner (serverns ordning, som Springs `produces`). (3) Inget `Accept`: den
  första erbjudna typen. Erbjudna typer får inte vara jokertecken (programmeringsfel,
  `IllegalArgumentException`).
- **Felaktigt `Accept` ger 400, inte 406.** Ett huvud som inte går att tolka är klientens
  fel, inte en fråga om vad servern kan leverera (avvikelse från Spring, i README:s tabell).
  `WebRequest.accept()` och `negotiate` översätter parserns fel till `WebException` 400 med
  detaljen `Invalid Accept header: ...`. Inget godtagbart ger 406 med detaljen
  `Acceptable representations: ...`.
- **`contentType()`** ger `Optional.empty()` utan huvud och 415 för ett felaktigt eller
  upprepat `Content-Type` (två fält är tvetydiga).
- **Ordningen i `readJson` och dess statuskoder** (motsvarar `@RequestBody`):
  0. Kroppen tas i anspråk (`BodyState`, atomiskt). Ett andra `readJson`, eller `readJson`
     efter `body()`/`bytes()` (även via en kopia från `withAttribute`), ger
     `IllegalStateException` (programmeringsfel, 500). `body()`/`bytes()` efter `readJson` ger
     också `IllegalStateException`; `body()` och `bytes()` sinsemellan fungerar som förut.
     Kroppen tas i anspråk före kontrollerna, så även ett andra anrop efter en 415 är ett fel.
  1. `Content-Encoding` som inte är tomt eller bara `identity` (utan hänsyn till skiftläge,
     kommaseparerat, flera fält) ger **415** med `Accept: application/json` och
     `Accept-Encoding: identity` (RFC 9110 §15.5.16 föreslår `Accept-Encoding`).
  2. Saknat, upprepat eller felaktigt `Content-Type`, en typ där `isJson()` är falskt, eller
     en `charset` som inte är `utf-8` (efter gemener; citerat `"utf-8"` godtas, `utf8` är inte
     ett registrerat namn och godtas inte) ger **415** med `Accept: application/json`.
  3.–4. Kroppen läses genom `LimitedInput`, som ger högst `maxInputSize + 1` byte och räknar.
     `Json.parse(InputStream, limits)` parsar medan kroppen strömmar in. Misslyckas parsningen
     och har strömmen gett mer än gränsen blir det **413** (`Request body is larger than N
     bytes.`); har den inte gett någon byte blir det **400** `Request body is required` (en
     tom kropp är tom oavsett ramning); annars **400** `Request body is not valid JSON: line L,
     column C: <beskrivning>`. Det gäller också djup, antal poster, stränglängd och tallängd.
     Beskrivningen kommer från `JsonParseException.description()`, men citerade utdrag ur
     kroppen (parsern citerar ett dubblerat medlemsnamn, upp till 40 tecken) ersätts med
     `"..."`. Enstaka tecken (`found 'x'`) står kvar, eftersom ett tecken inte kan bära data.
     Gränsen kontrolleras på två ställen (strömmen och parsern), så en kropp över gränsen ger
     413 även om parsern skulle ha hittat ett syntaxfel tidigare i samma buffert.
  5. `codec.decode`: `JsonMappingException` ger **400** med detaljen `Request body has N
     problem(s).` (singular för 1; över 100 dessutom `The first 100 are listed.`) och
     `errors`.
  En `RequestBodyException` (över `HttpLimits.maxBodySize`, trasig ramning, timeout) passerar
  `Json.parse` och `readJson` oförändrad (därför deklarerar `readJson` `IOException`), så
  routern skickar den vidare till `spola.http`, som svarar med kroppens status och stänger
  anslutningen; en allmän mapper för `Exception` når den inte (08a:s regel).
  Alla fel i 1–5 är `WebException` och går genom routerns mappers.
- **Strömmande parsning och 413.** Hela kroppen läses aldrig in före parsningen: minnet
  begränsas av parserns buffert och trädet, inte av kroppens storlek. Läsningen avbryts en
  byte efter gränsen (testat med en oändlig ström: exakt `maxInputSize + 1` byte läses). När
  handlern sedan svarar 413 läser och kastar `spola.http` resten av kroppen (upp till
  `maxBodySize`) innan svaret skickas, enligt 07a:s regel för olästa kroppar; inget av det
  buffras. Är kroppen större än `maxBodySize` ersätter serverns 413 handlerns svar och
  anslutningen stängs. Routerns standard är `JsonLimits.DEFAULT` (4 MiB) och ändras med
  `RouterBuilder.jsonLimits`; `readJson(codec, limits)` går före routerns gränser.
- **`errors` är det gemensamma felformatet inför 09.** Ett problem-svar för fel i
  förfrågans innehåll har den extra medlemmen `errors`: en array av
  `{"path": ..., "message": ...}` i dokumentordning, högst 100 poster (samma gräns som
  kodeken), och detaljen anger antalet. `path` använder kodekens notation (`$.items[3].qty`).
  Formatet finns som `Problem.FieldError` och `Problem.withErrors`, och en `WebException`
  bär listan i `errors()` (skyddad konstruktor), så att uppdrag 09 (validering) kan ge samma
  format och en mapper kan läsa felen utan att tolka JSON. `path` och `message` från kodeken
  är redan utskrivbar ASCII; `Problem` kortar dem som andra strängar (2 000 tecken).
- **`Responses` buffrar.** `json` förhandlar först (`negotiate(APPLICATION_JSON)`: 406, eller
  400 för ett felaktigt `Accept`), kodar sedan värdet till ett `JsonValue` och skriver det
  med `Json.writeUtf8` till en byte-array. Svaret är `Response.bytes` med
  `Content-Type: application/json` utan `charset` (RFC 8259 §11 definierar ingen) och
  `Content-Length` från servern. `HEAD` får samma huvuden utan kropp (servern utelämnar
  kroppen, 07a). `created` lägger till `Location`; `noContent` är 204.
- **`encode`-fel ger 500.** Ett `JsonMappingException` från `encode` är serverns fel (värdet
  kommer från servern) och kastas vidare som `IllegalStateException` med undantaget som
  orsak. Routern svarar då 500 utan detaljer och loggar det escapat. Omslaget gör också att
  en mapper som en applikation registrerat för `JsonMappingException` (tänkt för indata) inte
  kan göra det till en 400 (testat).
- **Problem-svar förhandlas inte.** `Problem.toResponse()` och `WebException` utan mapper ger
  alltid `application/problem+json`, även när `Accept` inte godtar det (RFC 9457 §3, och som
  Spring Boot gör när inget annat format är möjligt). Ett 406-svar skulle annars själv kunna
  bli 406.
- **`produces`/`consumes` är inte en del av routingen.** Handlern förhandlar (`readJson` ger
  415, `negotiate`/`Responses.json` 406). Det håller routingen oberoende av huvuden och gör
  förhandlingen synlig i koden (REGLER §4).

## Web del 3: cookies, `Set-Cookie` och formulärkroppar (`spola.web`)

Uppdrag 08c, sista delen av rad 08 i `PLAN.md` (efter 08a och 08b). Cookies in, `Set-Cookie`
ut och `application/x-www-form-urlencoded`-kroppar. Multipart, filuppladdning, sessioner,
signerade cookies och CSRF ligger utanför (uppdrag 11); mappning av formulär till records med
fel per fält kommer med validering i uppdrag 09.

### API (paket `spola.web`)

| Typ | Roll |
|---|---|
| `WebRequest` (nytt) | `cookie(name)`, `cookieAll(name)`, `cookie(name, Param)`, `cookies()`, `readForm()`, `readForm(maxBytes)` |
| `ResponseCookie` | oföränderlig: `of(name, value)`, `expire(name)` ger en oföränderlig `Builder` (`path`, `domain`, `maxAge`, `secure`, `httpOnly`, `sameSite`, `build()`); `SameSite { STRICT, LAX, NONE }`; `headerValue()`/`toString()` |
| `Responses` (nytt) | `withCookie(Response, ResponseCookie)` |
| `FormData` | oföränderlig: `first`, `all`, `get(name, Param)`, `required(name, Param)`, `asMap`, `isEmpty` |
| `RouterBuilder` (nytt) | `maxFormSize(int)` |
| `MediaType` (nytt) | `APPLICATION_FORM_URLENCODED` |

Interna klasser: `CookieState` (cookieparsningen, lat och högst en gång per förfrågan, delad
av alla `WebRequest` från samma förfrågan som `QueryState`) och `FormBody` (reglerna i
`readForm`). `BodyState` har fått routerns `maxFormSize` och `claimForForm`.
`JsonBody.checkEncoding(headers, accept)` och `JsonBody.single` delas med `FormBody`, och
`Decoding.parseQuery(raw, subject)` ger detaljen `The form body ...` i stället för
`The query ...`. Inga ändringar i `spola.http`, inga nya `requires`, ingen reflektion.

### Beslut

- **Så tolkas `Cookie` (RFC 6265 §4.2.1 och §5.4).** Alla `Cookie`-fält läses i ordning och
  behandlas som om de skilts med `"; "`. Varje fält delas på `;`; varje bit trimmas på
  mellanslag och tab; en tom bit (`;;`, avslutande `;`) och en bit utan `=` hoppas över.
  Namnet är texten före första `=` och måste vara en `token` (RFC 9110 §5.6.2), så ett tomt
  namn, ett namn med mellanslag (även `a =1`), citattecken eller andra avgränsare hoppas över.
  Värdet är resten och måste vara `*cookie-octet` (`%x21 / %x23-2B / %x2D-3A / %x3C-5B /
  %x5D-7E`) eller detsamma inom `"`. Ett värde med mellanslag, tab, komma, `\`, `"` mitt i,
  styrtecken eller icke-ASCII (obs-text som HTTP-parsern släpper igenom som U+0080–U+00FF)
  hoppas över. Namn jämförs skiftlägeskänsligt. Resultatet är en oföränderlig, ordnad
  `Map<String, List<String>>` (namn i den ordning de först förekommer, värden i ordning).
- **Felaktiga par hoppas över, de ger inte 400.** Andra applikationer på samma webbplats
  (en annan sökväg, en underdomän, ett analysskript) kan sätta cookies som webbläsaren
  skickar med till Spola. En 400 för en cookie som applikationen inte ens läser skulle låsa
  ute användaren tills cookien går ut, och användaren kan inte göra något åt det. Tomcats
  `Rfc6265CookieProcessor` gör likadant. Överhoppade par loggas inte per förfrågan (det vore
  en loggflod som en klient styr).
- **Högst 200 cookies.** Fler än 200 giltiga par (efter överhoppning, alla `Cookie`-fält
  tillsammans) ger 400 `The request has more than 200 cookies.`, utan namn eller värden.
  Gränsen begränsar mappens storlek; huvudets storlek begränsas redan av
  `HttpLimits.maxHeaderSectionSize` (16 KiB). 200 räcker långt över webbläsarnas gräns per
  domän (180 i Chrome). Precis som för queryn ger en för stor `Cookie` 400 bara om något
  läser cookies, och felet behålls för senare anrop.
- **Citattecken behålls, ingen avkodning.** Enligt RFC 6265 §4.1.1 är citattecknen en del av
  `cookie-value`, och servern kan inte veta om applikationen som satte cookien menade dem.
  Värdet är opakt för ramverket: ingen procentavkodning, ingen Base64. `cookie(name,
  Param.INT)` för `x="5"` ger därför 400. En applikation som kodar sina värden avkodar dem
  själv.
- **Typade cookies som queryn.** `cookie(name, Param)` använder `Param.convert` med typen
  `cookie`: `Invalid cookie "x": expected int, got "abc".`, namn och värde escapade och
  kortade till 40 tecken.
- **`ResponseCookie` har säkra standardvärden.** `Path=/`, `HttpOnly` och `SameSite=Lax`;
  utan `maxAge` en sessionscookie. `Path=/` gör att cookien gäller hela applikationen (utan
  `Path` sätter webbläsaren sökvägen till förfrågans katalog, vilket sällan är avsikten).
  `HttpOnly` skyddar mot att skript läser cookien (XSS), och den som vill att skript ska läsa
  den slår av det uttryckligen. `Lax` är webbläsarnas standard och skyddar mot de flesta
  CSRF-angrepp. `Secure` är inte standard, eftersom utveckling över `http://localhost` annars
  inte fungerar (webbläsaren sparar inte en `Secure`-cookie från `http`); den anges med
  `secure(true)`. Spring har inga standardvärden; avvikelsen står i README.
- **Validering när cookien byggs.** `build()` ger `IllegalArgumentException` med ett kort
  meddelande där namn och värden är escapade och kortade (`Text.quote`), så meddelandet är
  utskrivbar ASCII. Namnet ska vara en `token`; värdet `*cookie-octet` eller citerat (så att
  inget `;`, `,`, mellanslag eller CR/LF kan injicera attribut eller fält); `path` ska börja
  med `/` och bestå av US-ASCII utan styrtecken och `;` (RFC 6265 `path-value`); `domain`
  ska vara LDH-etiketter (bokstäver, siffror, bindestreck, inte först eller sist, 1–63 tecken)
  med punkter, högst 253 tecken, och en inledande punkt tas bort (RFC 6265 §5.2.3 ignorerar
  den ändå); `maxAge` får inte vara negativ och ska vara hela sekunder (`Max-Age` har inga
  bråkdelar, och en avrundning nedåt kunde göra 500 ms till `Max-Age=0`, alltså en borttagen
  cookie). Kontrollerna görs i `build()`, inte i varje setter, så att ordningen mellan
  setters inte spelar roll (`__Host-` med `secure(true)` sist är giltigt).
- **Prefixreglerna (RFC 6265bis §4.1.3).** `SameSite=None` kräver `Secure` (webbläsare
  avvisar annars cookien). Ett namn som börjar med `__Secure-` kräver `Secure`. Ett namn som
  börjar med `__Host-` kräver `Secure`, `Path=/` och ingen `domain`. Prefixen jämförs
  skiftlägeskänsligt, som uppdraget anger; `__host-x` är ett vanligt namn. Felet kommer när
  cookien byggs, inte först när webbläsaren tyst kastar den.
- **Oföränderlig builder.** `ResponseCookie.Builder` returnerar en ny builder från varje
  metod, så en bas (`ResponseCookie.of("s", v).secure(true)`) kan delas mellan trådar och
  återanvändas. `expire(name)` ger tomt värde och `Max-Age=0` med samma builder (så en cookie
  med annan `path`/`domain` går att ta bort); `maxAge(...)` på en sådan builder ger
  `IllegalStateException`, eftersom en borttagning med en livslängd är en motsägelse.
- **Inget `Expires`, bara `Max-Age`.** `Expires` är ett datum i serverns klocka och kräver
  datumformatering; `Max-Age` är relativt, går före `Expires` enligt RFC 6265 §5.3 och
  förstås av alla webbläsare i bruk. Spring skriver båda. En känd följd: JDK:ns
  `java.net.HttpCookie` gissar RFC 2965 när `Max-Age` finns utan `Expires`, och
  `CookieManager` skickar då tillbaka `$Version="1"; namn="värde";$Path=...`. Värdet kommer
  med citattecken, som Spola behåller, så en Java-klient med `CookieManager` ser en annan
  cookie än den som sattes. Webbläsare följer RFC 6265 och berörs inte, och
  sessionscookies (utan `maxAge`) fungerar med `CookieManager`; ett test fäster beteendet och
  README-exemplet använder en sessionscookie. Står i PROGRESS under kända problem.
- **Fast ordning i `Set-Cookie`.** `name=value; Path=...; Domain=...; Max-Age=...; Secure;
  HttpOnly; SameSite=...`: samma cookie ger alltid samma text, så tester kan jämföra exakt och
  `equals` jämför texten.
- **Ett `Set-Cookie`-fält per cookie.** `Responses.withCookie` lägger till ett fält med
  `Response.withHeader`, och `spola.http` skriver varje fält på en egen rad (testat med rå
  socket). Cookies slås aldrig ihop med komma (RFC 6265 §3, RFC 9110 §5.3: `Set-Cookie` är
  undantaget från kommaregeln, eftersom `Expires` innehåller komma). Inget behövde ändras i
  `spola.http`: `Headers` tillåter redan flera fält med samma namn.
- **Ordningen i `readForm` och statuskoderna.** Samma ordning som `readJson`, med samma
  hjälpare:
  1. `Content-Encoding` som inte är `identity`: 415 med
     `Accept: application/x-www-form-urlencoded` och `Accept-Encoding: identity`.
  2. Saknat, felaktigt, upprepat eller annat `Content-Type` än
     `application/x-www-form-urlencoded`, eller en `charset` som inte är UTF-8 (utan hänsyn
     till skiftläge): 415 med `Accept: application/x-www-form-urlencoded`. Andra parametrar
     ignoreras.
  3. Kroppen läses strömmande genom `JsonBody.LimitedInput` till högst `maxBytes + 1` byte.
     Över gränsen: 413 `Request body is larger than N bytes.`, och läsningen avbryts (testat
     med en oändlig ström: exakt `max + 1` byte läses). Minnet begränsas alltså av gränsen,
     inte av kroppen. Standard är 2 MiB (Tomcats `maxPostSize`), routerns standard ändras med
     `RouterBuilder.maxFormSize` och `readForm(maxBytes)` går före den. En
     `RequestBodyException` från `spola.http` (över `maxBodySize`, trasig ramning) passerar
     oförändrad, så servern svarar med kroppens status.
  4. En byte över 0x7F: 400 (en formulärkropp är procentkodad US-ASCII; en rå byte skulle
     annars tolkas i fel teckenkodning). Därefter `Decoding.parseQuery` med samma regler som
     queryn (WHATWG URL §5.1, `+` som mellanslag, strikt UTF-8, högst 1 000 par). Fel ger 400
     med en fast detalj (`The form body is not valid percent-encoded UTF-8.`,
     `The form body has more than 1000 parameters.`) utan text ur kroppen. Eftersom
     `Decoding.decode` bara godtar synlig ASCII ger också råa mellanslag och radbrytningar 400.

  Alla fel är `WebException` och går genom routerns mappers; `FormData.required`/`get` ger
  400 med `form field` i detaljen (`Missing form field "age".`).
- **En tom kropp är ett tomt formulär.** Ett HTML-formulär utan fält (eller med bara
  avmarkerade kryssrutor) skickar en tom kropp, och det är giltigt. `readJson` ger 400 för en
  tom kropp eftersom tom text inte är JSON; för formulär är tom text en giltig serialisering
  av noll fält.
- **Formulär och query slås inte ihop.** Springs `@RequestParam` läser servletens "request
  parameters", där queryn och formuläret är sammanslagna; vilket värde som kommer först beror
  på containern. I Spola väljer handlern uttryckligen `request.query(...)` eller
  `request.readForm()`, och samma namn i båda ger olika värden (testat). Det gör källan till
  varje värde synlig (REGLER §4) och hindrar att en query-parameter i en länk skuggar ett
  formulärfält.
- **`BodyState` delas med `readJson`.** Kroppen kan läsas en gång: `readForm` och `readJson`
  gör anspråk på den med `compareAndExchange`, så ett andra `readForm`, `readForm` efter
  `readJson`, `body()` eller `bytes()`, och `readJson`/`body()`/`bytes()` efter `readForm`,
  ger `IllegalStateException` (500, loggat) oavsett vilken `withAttribute`-kopia som används.
  Anspråket görs före kontrollerna, så ett misslyckat `readForm` (415) räknas också som
  läsning.
- **Trådsäkerhet.** `ResponseCookie`, dess `Builder` och `FormData` är oföränderliga.
  `CookieState` synkroniserar som `QueryState`; inget tillstånd delas mellan förfrågningar
  (`CookieState` och `BodyState` skapas per förfrågan i `Router.handle`).

## Validering del 1: komponerbara validatorer och felrapporter (`spola.validation`)

Uppdrag 09a, första delen av rad 09 i `PLAN.md`. Validatorer som vanlig Java-kod i stället för
Jakarta Bean Validation. Kopplingen till `spola.web` (400-svar med `errors`, `readJson`/
`readForm` med validering, formulär till records) är 09b.

### API (paket `spola.validation`)

| Typ | Roll |
|---|---|
| `Validator<T>` | funktionellt gränssnitt `validate(T, Errors)`; `check` (hela rapporten), `require` (värdet eller `ValidationException`), `of(Constraint...)`, `lazy(Supplier)`, `builder()` |
| `Validator.Builder<T>` | `field`, `optional`, `nested`, `each`, `eachNested`, `rule(test, code, message)`, `rule(name, test, code, message)`, `include`, `when`, `build()` |
| `Errors` | insamlaren för en validering: `at`, `atIndex`, `atKey`, `reject`, `path` |
| `Violation` | record `(path, code, message)`, inget värde |
| `Validated<T>` | sealed: `Valid<T>(value)`, `Invalid<T>(violations, count)`; `MAX_VIOLATIONS = 100` |
| `ValidationException` | `violations()`, `count()`, kort `getMessage()` (högst 2 000 tecken) |
| `Constraint<T>` | oföränderlig klass: `of(code, test, message)`, `withMessage`, `withCode`, `code`, `message`, `accepts` |
| `Constraints` | de inbyggda constraints, med fasta koder och engelska standardtexter |

Interna klasser: `Path` (sökvägskedjan, renderas bara när en överträdelse behålls), `Text`
(escaping och kortning), `Email` (definitionen bakom `email()`) och `LazyValidator`.

### Beslut

- **Fristående modul.** `spola.validation` har inga `requires` på andra Spola-moduler (REGLER
  §4: varje modul ska kunna användas fristående). Validering behövs i webblagret, i
  konfiguration och i vanlig affärslogik; ett beroende på `spola.json` eller `spola.web` skulle
  tvinga in dem överallt. Kopplingen åt andra hållet görs i 09b, där `spola.web` får
  `requires spola.validation`. `requires static java.compiler` finns bara för testet som
  kompilerar källtexter i minnet (mönstret från `spola.web` och `java.net.http`); ingen
  huvudkod använder den, och ett test kontrollerar det.
- **`Validator`/`Errors` är kärnan och byggaren ligger ovanpå.** Kärnan är samma form som
  Springs `org.springframework.validation.Validator` (`validate(target, errors)`): en lambda
  som rapporterar till en insamlare. Allt annat är kombinationer av den: byggarens steg är
  små `Validator<? super T>` som körs i ordning, `Validator.of` kör constraints på roten och
  `lazy` skjuter upp uppslagningen. En validator som inte passar byggaren (en `Map`, en regel
  över flera nivåer) skrivs därför direkt som lambda med `Errors.at/atIndex/atKey/reject`
  och kan sedan nästlas med `nested`, `eachNested` eller `include`. `Errors` lever bara under
  ett `check`/`require` och är inte trådsäker; används den efteråt ger `reject`
  `IllegalStateException`.
- **`Constraint` är en klass, inte ett funktionellt gränssnitt.** Byggaren har metoder som
  tar constraints (`field`) och metoder som tar validatorer (`nested`). Om båda vore
  funktionella gränssnitt skulle en lambda kunna passa båda, och överlagringar (även
  framtida) skulle bli tvetydiga eller tyst välja fel. En klass med fabriken `of(code, test,
  message)` gör också att varje constraint alltid har en kod och ett meddelande. Typparametern
  ger kompileringsfel för fel typ: `field(name, getter, Constraint<? super V>...)` med
  `Function<? super T, ? extends V>` kräver att `V` både är getterns typ och en subtyp av
  constraintens typ, så `notBlank()` (`Constraint<CharSequence>`) på en `int` och `min(1)`
  (`Constraint<Number>`) på en `String` kompilerar inte, medan `Constraint<Object>` passar
  allt. Varargs-metoderna har `@SafeVarargs` (tillåtet enligt REGLER §2) och är `final`
  (krav för `@SafeVarargs` på instansmetoder); de kopierar arrayen med en loop, eftersom
  `-Xlint:varargs` varnar när arrayen skickas vidare.
- **`null` som i Jakarta.** Varje constraint utom `notNull`, `notBlank` och `notEmpty`
  godtar `null`, och predikatet i `Constraint.of` anropas aldrig med `null`, så egna
  constraints slipper null-kontroller. `nested`, `eachNested` och `optional` hoppar över
  `null` (och tomt `Optional`), `each` ger ett `null`-element till elementets constraints så
  att `each(..., notNull())` fångar det, och `eachNested` hoppar över `null`-element (som
  `@Valid`). En byggd validator godtar `null` som värde och kör inga steg för det; därför
  anropas regel- och `when`-predikat aldrig med `null`. Krav på närvaro uttrycks alltid med
  `notNull()` på fältet, aldrig implicit.
- **Ordningen är deklarationsordning, djupet först.** Stegen körs i den ordning de lades
  till, och ett nästlat värdes överträdelser kommer direkt efter fältets egna (ett `field`
  på `lines` före `eachNested` på `lines` ger `$.lines` före `$.lines[0].sku`). Alla
  constraints för ett fält körs; ingen avbryter de andra. Ordningen är alltså bestämd av koden
  och testad, inte av hashning eller reflektion.
- **Högst 100 överträdelser behålls, alla räknas.** Samma gräns som kodeken och
  `Problem.MAX_ERRORS`. Gränsen kontrolleras i `reject` innan något skapas: efter 100 räknas
  bara `count` upp, och sökvägen renderas inte, så en lista med en miljon fel kostar ingen
  minnestillväxt. Valideringen fortsätter ändå, så `count` är exakt (mättat vid
  `Integer.MAX_VALUE`). `Invalid` kräver `1 <= violations.size() <= 100` och att `count` är
  lika med storleken om färre än 100 behölls. `ValidationException.getMessage()` är antalet
  och högst de tre första som `path: message`, varje del escapad till utskrivbar ASCII och
  kortad till 600 tecken, totalt högst 2 000 tecken (som `JsonMappingException.MAX_MESSAGE`).
- **Värdet bärs inte.** `Violation` har bara `path`, `code` och `message`. Jakarta bär värdet
  (`getInvalidValue`), men värden kan vara lösenord eller personuppgifter och ska inte kunna
  hamna i en logg eller ett svar av misstag. Ett test kontrollerar komponenterna och att ett
  avvisat lösenord inte finns i resultatet eller undantaget.
- **Meddelanden är säkra att använda som de är.** `Errors.reject` escapar styrtecken (C0, DEL,
  C1), U+2028/U+2029, bidi-tecken och ensamma surrogat som `\uXXXX` och kortar meddelanden över
  500 tecken (följt av längden), men behåller bokstäver i alla skript, så att 09b kan lägga
  `message` i ett problem-svar och en loggrad utan att tolka den. Koder måste vara utskrivbar
  ASCII utan mellanslag (kontrolleras när constrainten eller regeln skapas, och i `reject`).
- **Sökvägsnotationen är kodekens.** `$` är roten, `.namn` en medlem vars namn är en
  identifierare (`[A-Za-z_$][A-Za-z0-9_$]*`), `["annat namn"]` andra namn escapade som en
  JSON-sträng med allt utanför utskrivbar ASCII som `\uXXXX` (gemena hexsiffror, som i
  kodeken), `[2]` ett element. `Errors.at` är för namn som koden deklarerar och skrivs i sin
  helhet; `atKey` är för namn från data och kortas efter 40 tecken, följt av längden. `Path`
  är en kopia av `spola.json.codec.Path` (samma algoritm, icke-rekursiv rendering), eftersom
  modulen inte får bero på `spola.json`. Testet `PathTests` anger för varje sökväg vilket
  kodektest som ger samma text. 09b kan därför lägga en `JsonMappingException` och en
  valideringsrapport i samma `errors`-lista.
- **Tid mot en uttrycklig `Supplier`.** `past(now)` och de andra tar en `Supplier<C>` för
  `C extends Comparable<? super C>`, till exempel `() -> LocalDate.now(clock)`. Det finns
  ingen dold systemklocka (Jakartas `ClockProvider`), så tester blir deterministiska och typen
  kontrolleras vid kompilering. Leverantören frågas en gång per kontrollerat värde (inte för
  `null`); returnerar den `null` blir det `NullPointerException`.
- **Tal jämförs exakt.** `Byte`/`Short`/`Integer`/`Long`/`BigInteger`/`BigDecimal` som de är;
  `Double`/`Float` via `BigDecimal` av sin kortaste decimaltext (`Double.toString`, så `0.1`
  är `0.1`), och NaN/oändligt är aldrig giltiga; andra `Number` via `new
  BigDecimal(toString())`, ogiltigt om texten inte är ett tal. `digits` räknar som Hibernate
  (på värdet utan avslutande nollor).
- **`email()` är avsiktligt enkel och bestämd.** Exakt ett `@`; en lokal del på 1–64 tecken
  i RFC 5322 `dot-atom` (`atext` och punkter, ingen punkt först, sist eller dubbel); en domän
  på 1–253 tecken med LDH-etiketter på 1–63 tecken, utan bindestreck först eller sist; inga
  citerade lokala delar, inga IP-literaler, bara ASCII. `a@b` godtas som i Hibernate
  Validator. Implementerad med loopar (linjär tid, ingen regex), och text längre än 318
  tecken avvisas utan genomsökning.
- **Undantag från användarkod släpps igenom.** Getters, predikat och egna validatorer kan
  kasta; undantaget (och `Error`) går oförändrat ut ur `check`/`require`, och ingen halv
  rapport returneras. Att slå in det skulle dölja buggar och kunna läcka värden via
  meddelandet.
- **Trådsäkerhet.** Byggda validatorer och constraints är oföränderliga (stegen kopieras i
  `build()`, constraints i `List.copyOf`) och kan delas som `static final`. Varje `check`
  skapar en egen `Errors`. `lazy` sparar den levererade validatorn i ett `volatile`-fält; två
  trådar kan båda fråga leverantören första gången, vilket är ofarligt när den returnerar ett
  `static final`-fält.
- **Ingen cykeldetektering.** Objektgrafer som innehåller sig själva ligger utanför (som i
  uppdraget); valideringen går då tills stacken tar slut och `StackOverflowError` släpps
  igenom. Rekursion sker på Java-stacken, så mycket djupa strukturer har samma gräns.
- **Vad 09b bygger vidare på.** `Violation.path()` och `Violation.message()` går direkt in i
  `Problem.FieldError(path, message)`, och `Validated.Invalid.violations()` håller högst 100,
  samma som `Problem.withErrors`. `code` finns för klienter och senare översättning.
  `ValidationException` kan mappas till 400 med `errors` med en `ExceptionMapper`, och
  `readJson(codec, validator)` kan köra `validator.check` efter avkodningen. `count()` kan
  användas för en sammanfattning när fler än 100 rapporterats.

## Validering del 2: validering och formulärbindning i webblagret (`spola.web` + `spola.validation`)

Uppdrag 09b, andra och sista delen av rad 09 i `PLAN.md`. `spola.web` kan validera det som
kommer in i en förfrågan och svara 400 med ett fel per fält, som Spring Boot gör med
`@Valid @RequestBody`, `@Valid @ModelAttribute`, `BindingResult` och
`MethodArgumentNotValidException`, utan annoteringar och utan reflektion.

### API (paket `spola.web`)

| Typ/metod | Roll |
|---|---|
| `Problem.FieldError(path, message, Optional<String> code)` | posten i `errors`; `FieldError(path, message)` ger tom `code`; `MAX_CODE = 100` |
| `WebException.invalid(Validated.Invalid<?>)`, `invalid(ValidationException)` | 400 med `The request has N validation errors.` och `errors` med `code` |
| `WebRequest.readJson(codec, validator)`, `readJson(codec, limits, validator)` | `@Valid @RequestBody` |
| `FormBinder<T>` | funktionellt gränssnitt `bind(FormFields)`; standardmetoden `bind(FormData)` binder utan förfrågan |
| `FormFields` | insamlaren för en bindning: `required`, `optional`, `all`, `flag` |
| `WebRequest.readForm(binder)`, `readForm(binder, validator)` | `@Valid @ModelAttribute` på en formulärkropp |
| `WebRequest.bindQuery(binder)`, `bindQuery(binder, validator)` | `@ModelAttribute` på queryn |

Internt: `Param.convertOrNull` (omvandling utan undantag och utan värdet i något meddelande)
och `FormFields.path` (sökvägsrenderingen).

### Beslut

- **Beroendets riktning: `spola.web` → `spola.validation`, ingen bryggmodul.** `spola.web` har
  `requires transitive spola.validation`, transitivt eftersom `Validator`, `Validated` och
  `ValidationException` finns i webbens publika API. `spola.validation` är oförändrad och
  fristående (inga `requires` på Spola-moduler; den kan användas i konfiguration och
  affärslogik utan webben). En separat modul `spola.web.validation` skulle bara bli en modul
  till att lägga till i `module-info.java` utan att någon vinner något: valideringsmodulen är
  liten och har inga beroenden, så den kostar inget för den som bara vill ha webben. Inga
  andra `requires` har lagts till. `SourceRulesTests` fäster den nya listan (testets förväntade
  `requires` uppdaterades; se nedan).
- **`FieldError.code`.** `Problem.FieldError` fick komponenten `Optional<String> code`, och
  den gamla konstruktorn `FieldError(path, message)` finns kvar och ger en tom `code`.
  `toJson()` skriver `path`, `message` och sist `code`, och `code` bara när den finns, så
  mappningsfelen från `readJson` (som inte har någon kod) ger exakt samma JSON som i 08b. En
  kod är 1–100 tecken utskrivbar ASCII utan mellanslag (samma regel som `spola.validation`
  plus en längdgräns); annars `IllegalArgumentException`. Valideringsfel får
  `Violation.code()`, bindningsfel koderna `required`, `type` och `single`. En `Violation`
  med en kod som inte uppfyller regeln (bara möjligt med en egen kod på över 100 tecken eller
  en `Violation` byggd för hand) ger `IllegalArgumentException` i `WebException.invalid`,
  alltså 500: det är ett programmeringsfel.
- **Mappning och bindning före validering.** `readJson(codec, limits, validator)` kör först
  hela `readJson(codec, limits)` med samma ordning och samma svar (415 → 400 tom kropp →
  413/400 parsning → 400 mappning), och anropar sedan `validator.check(value)`. `Valid` ger
  värdet, `Invalid` ger `WebException.invalid(invalid)`. Validatorn anropas aldrig för en
  kropp som inte gick att läsa, tolka eller mappa, och för formulär och query bara när
  bindningen lyckades. Som i Spring (där `HttpMessageNotReadableException` kommer före
  valideringen) blandas de två listorna aldrig: ett svar har antingen mappnings-/bindningsfel
  (`Request body has N problems.`, `Query has N problems.`) eller valideringsfel (`The request
  has N validation errors.`). Det finns inget `BindingResult`; en handler får aldrig ett
  ogiltigt värde. Ett undantag från validatorn släpps igenom oförändrat (samma instans) till
  routerns mappers eller 500. Argumenten kontrolleras (`NullPointerException`) innan kroppen
  läses.
- **Valideringsfel blir 400 på ett enda sätt.** Båda `WebException.invalid` bygger samma
  `WebException` av `violations` och `count`: status 400, standardtiteln, detaljen `The request
  has N validation errors.` (`1 validation error.` i singular) och ` The first 100 are
  listed.` när `count` är större än antalet listade. `errors` är överträdelserna i ordning
  med `path`, `message` och `code` oförändrade. Ingen ny underklass: `WebException` med
  `errors()` räcker, och en mapper för `WebException` fångar valideringsfelen också.
- **`FormBinder`/`FormFields` samlar alla fel.** Bindningen är vanlig kod: en lambda som
  läser fälten ur en `FormFields` och anropar en konstruktor, utan reflektion över
  record-komponenter. `FormFields` kastar inte vid första felet utan registrerar felet och
  ger en platshållare (`null`, `Optional.empty()`, `false` eller en kortare lista), så att
  bindern går vidare och klienten får alla fel på en gång. Det är vad `BindingResult` ger i
  Spring, och det som en användare av ett formulär förväntar sig (alla fält markerade, inte
  ett i taget). När bindern har returnerat och fel finns kastas värdet bort och svaret blir
  400. `FormFields` lever bara under ett bindningsanrop (som `Errors` i 09a), är inte
  trådsäker och ger `IllegalStateException` om den används efteråt.
- **Tomma värden räknas som saknade, utom för `Param.STRING`.** Ett HTML-formulär skickar
  `age=` för ett tomt fält, och ett tomt valfritt tal ska inte ge fel (Spring omvandlar tom
  text till `null` för `Integer`). Regeln jämför med `param == Param.STRING`: för den är `""`
  ett värde, så att `notBlank()` i valideringen avgör. `required` ger då felet `required`,
  `optional` tomt, `all` hoppar över elementet (senare element behåller sin position i
  formuläret som index, så att sökvägen pekar på rätt värde) och `flag` ger `false` (en tom
  kryssruta är inte ikryssad).
- **Flera värden är ett fel.** Fler än ett värde för `required`, `optional` eller `flag` ger
  felet `single` (`must have a single value`) i stället för att tyst ta det första. Det är ett
  skydd mot parameter pollution (`role=user&role=admin`), där olika lager annars kan välja olika
  värden. Bara `all` tar emot flera.
- **Värden ekas aldrig.** Ett omvandlingsfel ger `type` med `expected <typeName>` (typens namn,
  escapat och kortat som i `Param`), aldrig värdet, eftersom fältet kan vara ett lösenord.
  `Param.convert` citerar värdet i sin detalj för sökvägs-, query- och cookieparametrar;
  `FormFields` använder därför `Param.convertOrNull`, som kastar bort parserns undantag (och
  dess meddelande) och bara säger att omvandlingen misslyckades. Valideringsfelen bär aldrig
  värdet (`Violation` har inget). Bindnings- och valideringsfel är `WebException`s med 4xx och
  loggas inte av routern. Tester skickar en markörsträng som lösenord och kontrollerar kropp,
  huvuden och loggrader.
- **Undantagsregeln för bindern.** Kastar bindern ett `RuntimeException` (oftast en
  record-konstruktor som får `null` efter ett saknat `required`) och fel redan har samlats,
  blir svaret 400 med de insamlade felen och undantaget kastas bort: det är en följd av felen,
  inte en egen bugg. Har inga fel samlats släpps undantaget igenom oförändrat (samma instans),
  så att en bugg i bindern blir 500 eller når en mapper. Ett `Error` släpps alltid igenom.
  En parser i en egen `Param` som returnerar `null` är ett programmeringsfel
  (`IllegalStateException`) och följer samma regel.
- **Gränsen och ordningen.** Felen behålls i den ordning bindern anropar metoderna (inte
  formulärets ordning), högst 100 (`Validated.MAX_VIOLATIONS`, samma som `Problem.MAX_ERRORS`),
  och alla räknas (mättat vid `Integer.MAX_VALUE`). Gränsen kontrolleras innan sökvägen
  renderas, så tusen fel kostar hundra strängar. Detaljen är `Request body has N problems.`
  eller `Query has N problems.`, följd av ` The first 100 are listed.` när fler räknades.
- **Den kopierade sökvägsrenderingen.** Sökvägarna skrivs i kodekens och valideringens
  notation (`$.email`, `$["first name"]`, `$.interests[2]`), så att bindningsfel och
  valideringsfel läses likadant och en klient kan koppla dem till samma fält. Namnen kommer
  från koden och skrivs i sin helhet. Algoritmen för medlemsnamn och index i
  `spola.validation.Path` är paketprivat där, och 09b får inte lägga till publikt API i andra
  moduler, så `FormFields.path` är en privat kopia (identifierare
  `[A-Za-z_$][A-Za-z0-9_$]*` som `.namn`, annars `["..."]` med `"` och `\` escapade och allt
  utanför utskrivbar ASCII som `\uXXXX` med gemena hexsiffror). Ett test jämför de två för
  identifierare, mellanslag, `"` och `\`, styrtecken, tecken utanför ASCII, namn längre än 40
  tecken, tomt namn och index.
- **`ValidationException` mappas inte automatiskt.** Utan mapper ger den 500, som andra
  undantag. En `ValidationException` från tjänstekod betyder att tjänsten själv har byggt
  eller fått ett ogiltigt värde från något annat än förfrågan (en databas, en annan tjänst),
  och det är en bugg, inte klientens fel. Spring gör likadant för
  `ConstraintViolationException` från en `@Validated`-tjänst. Den som vill ha 400 skriver en
  rad: `onException(ValidationException.class, (e, r) -> WebException.invalid(e).toResponse(r))`,
  som ger exakt samma svar som `readJson(codec, validator)`.
- **Trådsäkerhet.** `FormBinder`s som bara anropar `FormFields` och en konstruktor är
  tillståndslösa och kan delas som `static final`, liksom kodekar och validatorer; varje
  bindning skapar en egen `FormFields`. Ett test med 200 samtidiga klienter kontrollerar att
  varje klient får exakt sitt eget svar.
- **Ett befintligt test ändrades.** `SourceRulesTests` fäster `spola.web`s exakta `requires`
  och kan inte vara grönt med det nya beroendet; det enda som ändrades är den förväntade
  listan (`transitive spola.validation` tillagd) och testets namn. Alla andra befintliga
  testfiler är oförändrade utom registreringen av de nya sviterna i `WebTests`.

## Säkerhet del 1: lösenordshashning och autentisering (`spola.security`)

Uppdrag 10a, första delen av rad 10 i `PLAN.md`. Lösenordshashning, användare och
autentisering utan HTTP. Filterkedjan i `spola.web`, HTTP Basic, formulärinloggning,
auktoriseringsregler och 401/403 är 10b. Koden är säkerhetskritisk och bör granskas av en
människa.

### API (paket `spola.security`)

| Typ | Roll |
|---|---|
| `Pbkdf2` | `deriveSha256(password, salt, iterations, lengthBytes)`: PBKDF2-HMAC-SHA256 på `Mac` |
| `PasswordHasher` | gränssnitt `hash`, `matches`, `needsRehash` (Springs `PasswordEncoder`) |
| `Pbkdf2PasswordHasher` | `defaults()`, `of(iterations, saltBytes, hashBytes)`, PHC-format, gränserna som konstanter |
| `User` | record `(username, passwordHash, roles, enabled)`, `toString()` utan hash |
| `UserStore` | funktionellt gränssnitt `Optional<User> find(String)` |
| `InMemoryUserStore` | trådsäker: `add`, `find`, `updatePassword`, `upgrader(hasher)` |
| `Authenticator` | `of(store, hasher)`, `of(store, hasher, upgrade)`, `authenticate(username, password)` |
| `PasswordUpgrade` | funktionellt gränssnitt `upgrade(username, newHash)` |
| `AuthResult` | sealed: `Authenticated(Identity)`, `Failed(Failure)` |
| `Failure` | `BAD_CREDENTIALS`, `DISABLED` |
| `Identity` | record `(name, roles)` med `hasRole` |

Intern klass: `Names` (regler för användarnamn och roller, escaping i `toString()`).

### Beslut

- **Fristående modul på `System.Logger`.** `spola.security` har inga `requires` alls. I 10b ska
  `spola.web` och `spola.security` kopplas ihop, och riktningen bestäms då; 10a får därför inte
  bero på `spola.web`, `spola.http` eller någon annan Spola-modul. Hashning och autentisering
  behövs också utan HTTP (ett CLI, en batch, en annan protokollserver). Loggningen går direkt
  till `System.Logger` med namnet `spola.security`, som i `spola.http` och `spola.web`, i
  stället för via `spola.util`, så att modulen förblir fristående. Det enda som loggas är
  `WARNING` när en uppgradering misslyckas, med undantagets klassnamn (escapat) och aldrig dess
  meddelande, som kan innehålla hashen eller data från en databas. En paketprivat fabrik tar en
  egen logger, så att testerna kan se loggraden utan `java.logging`.
- **PBKDF2 på `Mac`, lösenordet som UTF-8.** JDK:ns `SecretKeyFactory`
  (`PBKDF2WithHmacSHA256`) tar ett `char[]` och kodar det själv; hur är en detalj i
  leverantören. `Pbkdf2` tar bytes, och `Pbkdf2PasswordHasher` gör dem på ett enda uttryckligt
  sätt: UTF-8 med `CodingErrorAction.REPORT`, så att ett ensamt surrogat är ett fel och inte
  ett tyst `?`. Implementationen följer RFC 8018 §5.2 rakt av: `Mac` initieras en gång per
  anrop och varje block kostar `iterations` HMAC-anrop (`doFinal` skriver i en återanvänd
  buffert). Den testas mot RFC 7914 §11:s två vektorer och mot `SecretKeyFactory` för 60
  slumpade fall (ASCII, Latin-1, CJK, kombinerande tecken och surrogatpar; 1–3 000 iterationer;
  1–100 byte, så både ett och flera block). Nyckeln ges till `Mac` som en egen privat
  `SecretKey` som kan nollställas (`SecretKeySpec.destroy()` stöds inte); de kodade
  lösenordsbyten och de mellanliggande blocken nollställs efter användning, och kodningen
  skriver i en buffert på `3 * längd` byte, så den aldrig växer och lämnar kopior. Lösenordet
  som `CharSequence` och `Mac`-instansens inre tillstånd kan modulen inte rensa. Bara
  HMAC-SHA-256 stöds: det finns officiella vektorer för den, och fler algoritmer utan sådana
  läggs inte till.
- **Standardparametrar och två uppsättningar gränser.** `defaults()` är 600 000 iterationer,
  16 byte salt och 32 byte hash (OWASP:s rekommendation för PBKDF2-HMAC-SHA256). Vid skapande
  (`of`) gäller 10 000–10 000 000 iterationer, 16–64 byte salt och 16–64 byte hash: färre
  iterationer eller kortare salt är för svagt för nya hashar. Vid tolkning gäller 1–10 000 000
  iterationer och 8–64 byte salt: äldre hashar med färre iterationer eller kortare salt kan
  verifieras och sedan uppgraderas, medan taket gör att en korrupt rad med miljarder
  iterationer inte kan låsa en tråd. Lösenordet är 1–1 024 tecken; längden kontrolleras före
  kodningen och före PBKDF2, så ett jättelikt lösenord inte kostar något. `Pbkdf2` själv gör
  inga policybedömningar (från 1 iteration) men begränsar utdata till 1 024 byte.
- **PHC-format med strikt, fail-closed tolkning.** `$pbkdf2-sha256$i=<n>$<salt>$<hash>` med
  standard-Base64 utan utfyllnad. Strängen bär iterationer, saltet och (genom sin längd)
  hashlängden, så en hash kan verifieras med sina egna inställningar och `needsRehash` kan
  jämföra dem med hasharens. Tolkningen godtar exakt en stavning per värde: längden (högst 256
  tecken) kontrolleras först, sedan utskrivbar ASCII, fem delar med tom första del, id exakt
  `pbkdf2-sha256`, `i=` följt av högst åtta decimala siffror utan inledande nolla (så inget kan
  spilla över) och högst 10 000 000, och kanonisk Base64 (avkodning och ny kodning ger samma
  text; det fångar utfyllnad, `-_` och satta oanvända bitar, som JDK:ns avkodare annars
  godtar). Ingen alternativ stavning betyder att två olika strängar aldrig är samma hash.
- **En felaktig hash ger undantag, inte `false`.** En lagrad hash som inte går att tolka är ett
  datafel (en trasig migrering, en annan algoritm, en korrupt rad), inte ett fel lösenord. Som
  `false` skulle den se ut som ett misslyckat inloggningsförsök och kunna gå obemärkt förbi.
  `matches` och `needsRehash` tolkar hashen först och kastar `IllegalArgumentException`
  oavsett lösenord, med ett kort meddelande som bara säger vad som är fel, aldrig strängen
  eller delar av den (den kan vara en hash från databasen), och utan orsak (JDK:ns
  Base64-fel namnger tecknet). `Authenticator` släpper undantaget igenom.
- **Okända användare kostar lika mycket.** När `Authenticator` skapas hashar den ett fast
  dummylösenord med samma hasher. För en okänd användare anropas `matches` exakt en gång mot
  dummyhashen, så arbetet blir detsamma som för en känd användare med fel lösenord (Springs
  `userNotFoundEncodedPassword`). Ett användarnamn som är `null`, tomt eller längre än 256
  tecken och ett `null`-lösenord ger `BAD_CREDENTIALS` utan undantag och utan uppslag i
  lagringen, men dummyhashen kontrolleras ändå en gång (för `null`-lösenordet med
  dummylösenordet, eftersom `matches` kräver ett lösenord). Uppslagningen i `UserStore` och
  skillnaden mellan en träff och en miss där ligger utanför skyddet.
- **`DISABLED` bara efter rätt lösenord.** Spring kontrollerar avstängda konton före
  lösenordet, så den som prövar ett konto utan att kunna lösenordet får veta att det är
  avstängt. Här kontrolleras lösenordet först; är det fel blir svaret `BAD_CREDENTIALS` oavsett
  konto. Bara den som kan lösenordet får veta att kontot är avstängt, och det behöver hon för
  att förstå varför inloggningen nekas.
- **Uppgradering som inte stoppar inloggningen.** När lösenordet stämt, kontot är aktivt och
  `needsRehash` är `true` anropas `PasswordUpgrade` med en ny hash av samma lösenord (Springs
  `UserDetailsPasswordService`). För `InMemoryUserStore` är det `store.upgrader(hasher)`.
  Användaren har bevisat sitt lösenord; att den nya hashen inte
  kunde sparas (databasen nere, en konflikt) är ett driftproblem som ska synas i loggen men inte
  låsa ute användaren, och den gamla hashen fungerar fortfarande. Därför fångas ett
  `RuntimeException` från hashningen eller uppgraderingen och loggas på `WARNING` med bara
  klassnamnet. Ett `Error` fångas inte (det är inte ett fel i uppgraderingen utan i JVM:en
  eller i ett test). Utan `PasswordUpgrade` anropas varken `needsRehash` eller `hash`.
- **Villkorad uppgradering.** Den nya hashen räknas fram efter att lösenordet verifierats, och
  det tar lika lång tid som en hashning (cirka 125 ms med `defaults()`). Byts lösenordet under
  tiden skulle en ovillkorad skrivning lägga en hash av det *gamla* lösenordet över det nya, så
  att bytet tyst ångras och det gamla (kanske läckta) lösenordet fungerar igen. Därför byter
  `InMemoryUserStore.upgrader(hasher)` hashen bara om den lagrade hashen fortfarande
  `needsRehash`, kontrollerat och bytt i samma `computeIfPresent` (atomärt). Ett lösenordsbyte
  via `updatePassword` med hasharens aktuella inställningar skrivs därför aldrig över;
  `updatePassword` själv är ovillkorad eftersom den är till för lösenordsbyten. Signaturen
  `upgrade(username, newHash)` ger inte den lästa hashen, så villkoret är "behöver fortfarande
  uppgraderas" och inte en exakt jämförelse; ett lösenordsbyte som sparas med gamla
  inställningar kan fortfarande skrivas över, vilket Javadoc för `upgrader` säger. Egna
  lagringar måste göra samma villkorade, atomära uppdatering (Javadoc för `PasswordUpgrade`).
  Ett test håller uppgraderingen med en spärr, byter lösenordet, släpper spärren och visar att
  det nya lösenordet fungerar och det gamla inte.
- **Resultat som värde.** `authenticate` ger det förseglade `AuthResult` i stället för att
  kasta som Springs `AuthenticationProvider`: ett misslyckat försök är ett vanligt utfall, och
  en `switch` över `Authenticated`/`Failed` är fullständig. Undantag är reserverade för datafel
  (lagringens undantag, en felaktig hash, en lagring som ger `null`).
- **Användare och roller.** `User` och `Identity` gör en oföränderlig, sorterad kopia av rollerna
  (förutsägbar `toString()`); en roll är 1–100 tecken utskrivbar ASCII utan mellanslag, så den
  kan stå i en loggrad eller ett huvud utan escaping. Användarnamn har bara längdgränsen
  1–256 tecken och jämförs exakt; `toString()` escapar styrtecken, radavgränsare,
  bidi-tecken och ensamma surrogat så att ett namn inte kan förfalska en loggrad, och visar
  aldrig hashen. `Identity` får namnet och rollerna från den `User` som lagringen gav.

## Säkerhet del 2: säkerhetsfilter, HTTP Basic och åtkomstregler (`spola.security.web`)

Uppdrag 10b, sista delen av rad 10 i `PLAN.md`. Bryggmodulen mellan `spola.security` och
`spola.web`: ett säkerhetsfilter med utbytbara autentiseringsmekanismer, HTTP Basic (RFC 7617),
segmentvisa matchers, åtkomstregler, identiteten per förfrågan, gruppskydd och fasta 401/403.
Formulärinloggning och utloggning kräver sessioner och flyttades till uppdrag 11. Koden är
säkerhetskritisk och bör granskas av en människa.

### API (paket `spola.security.web`)

| Typ | Roll |
|---|---|
| `WebSecurity` | `builder()`, `filter()`, `require(rule)`, statiska `identity(request)` och `requireIdentity(request)` |
| `WebSecurity.Builder` | `authentication(mechanism)` (flera gånger), `rule(matcher, access)`, `build()` |
| `RequestAuthentication` | funktionellt gränssnitt `AuthOutcome authenticate(WebRequest)` |
| `AuthOutcome` | sealed: `None()`, `Success(Identity)`, `Rejected()` |
| `HttpBasic` | `of(authenticator, realm)`, `realm()`, `challenge()`; `MAX_TOKEN_CHARS` (8 192), `MAX_REALM_CHARS` (100) |
| `RequestMatcher` | funktionellt gränssnitt `matches(WebRequest)`; `under`, `exactly`, `method`, `anyRequest` |
| `AccessRule` | funktionellt gränssnitt `allows(Optional<Identity>, WebRequest)`; `permitAll`, `denyAll`, `authenticated`, `hasRole`, `hasAnyRole` |
| `SecurityContext` | record `(Optional<Identity> identity)`, attributet som filtret lägger på förfrågan |

Interna klasser: `Matchers` och `Rules` (implementationerna bakom fabrikerna), `Text`
(korta, escapade värden i felmeddelanden).

### Beslut

- **Bryggmodul i stället för ett beroende mellan `spola.web` och `spola.security`.**
  `spola.security` ska förbli fristående: hashning och autentisering behövs också utan HTTP (ett
  CLI, en batch, en annan protokollserver), och modulen har inga `requires` alls. `spola.web` ska
  gå att använda utan säkerhet. Till skillnad från valideringen i 09b, där integrationen var
  liten och lades i `spola.web`, är säkerhetsdelen stor och har egna begrepp (mekanismer, regler,
  401/403), så den får en egen modul, som `spring-security-web` bredvid `spring-security-core`.
  `spola.security.web` har `requires transitive spola.web` och `requires transitive
  spola.security`, eftersom båda modulernas typer (`Filter`, `WebRequest`, `Authenticator`,
  `Identity`) finns i dess API, och `requires static java.net.http` bara för testerna. Ett test
  fäster listan.
- **Ett litet tillägg i `spola.web`: `RouterBuilder.log(System.Logger)` är publik.** Metoden
  fanns som paketprivat för `spola.web`s egna tester. Kriterium 11 kräver att testerna läser
  varje loggrad som routern skriver, och den här modulens tester ligger i ett annat paket och kan
  inte läsa `java.logging` (modulens `requires` är fastlagda). Tillägget ändrar inget beteende:
  standardloggern är fortfarande `System.getLogger("spola.web")`; metoden fick bara Javadoc och
  blev publik. Inga andra ändringar under andra modulers `src/` eller `test/`, och i `Build.java`
  bara den nya raden i `MODULES`.
- **Mekanismer och `AuthOutcome`.** En `RequestAuthentication` säger om förfrågan har uppgifter
  för den (`None`), om de var rätt (`Success(identity)`) eller om de fanns men var fel eller
  trasiga (`Rejected`). Filtret provar mekanismerna i registreringsordning och den första som
  inte ger `None` avgör; ingen mekanism eller bara `None` betyder anonym. Tre utfall i stället för
  `Optional<Identity>` behövs för att skilja "inga uppgifter" (nästa mekanism får försöka, eller
  anonym) från "fel uppgifter". **`Rejected` ger alltid 401, även på en `permitAll`-väg**, innan
  någon regel läses: en klient som skickar fel lösenord ska få veta det i stället för att tyst
  behandlas som anonym (som Spring, där `BasicAuthenticationFilter` svarar 401 på fel uppgifter
  oavsett väg). Annars kan en klient med ett utgånget lösenord se ut att fungera mot öppna vägar
  och få obegripliga fel längre fram, och ett fel i en mekanism skulle kunna ge anonym tillgång i
  stället för ett tydligt nej. Uppdrag 11 skulle lägga till JWT och sessioner som fler
  mekanismer; sessionen blev i 11b i stället ett eget steg före mekanismerna (se "Inloggning
  med session"). Ett undantag (och ett `null`) från en mekanism går till routerns mappers, 500
  utan mapper.
- **Basic-tolkningen och dess gränser.** `Authorization` är ett singelfält (RFC 9110 §11.6.2):
  fler än ett fält ger 400 i filtret innan någon mekanism körs (och i `HttpBasic` själv, om den
  anropas utanför filtret). Schemat är den inledande följden tokentecken och jämförs
  skiftlägesokänsligt; ett annat schema (`Bearer`, `Digest`, `Basicx`) ger `None`. Efter
  `Basic` måste ett eller flera mellanslag (SP) och en token68 följa; tab, ingen token eller ett
  andra ord ger `Rejected`. En token längre än 8 192 tecken avvisas innan den avkodas (huvudets
  egen gräns i `spola.http` är 16 KiB för alla fält). Base64 är strikt standard-Base64 med
  utfyllnad och kanonisk: längden måste vara delbar med fyra (JDK:ns avkodare godtar saknad
  utfyllnad), `Base64.getDecoder()` avvisar `-_` och felplacerad utfyllnad, och en ny kodning
  måste ge samma text (det fångar satta oanvända bitar, som JDK:n godtar). Byten avkodas som
  UTF-8 med `REPORT` (felaktiga, överlånga och kodade surrogat avvisas), delas vid första `:`
  (lösenordet får innehålla `:`), och styrtecken (U+0000–U+001F, U+007F) i namn eller lösenord
  och ett tomt namn ger `Rejected`. Ingen normalisering. Först därefter anropas
  `Authenticator.authenticate`, exakt en gång; båda `Failed`-orsakerna ger `Rejected`. Ett
  användarnamn över 256 tecken avvisas inte här utan av `Authenticator` (med dummyhashen, så
  tiden blir densamma). Avkodningen skriver i en `char[]` lika lång som byten (UTF-8 ger aldrig
  fler tecken), och lösenordet ges till `Authenticator` som en skrivskyddad `CharBuffer` över den;
  byten och tecknen nollställs i `finally`. Användarnamnet är en `String` och huvudets värde
  finns som `String` i förfrågan; de, och kopior som hasharen eller lagringen gör, kan inte
  rensas. Varje Basic-förfrågan kostar en PBKDF2-beräkning; lyckade inloggningar cachas inte och
  det finns ingen hastighetsbegränsning (utanför uppdraget).
- **Matchning på avkodade segment, `HEAD`-regeln och ingen Ant-syntax.** `under` och `exactly`
  jämför hela segment med `WebRequest.pathSegments()`, alltså samma avkodade segment som routern
  routar på, skiftlägeskänsligt. Därför matchar `under("/admin")` också `/%61dmin` (som routern
  routar till `/admin`) men inte `/administrator` eller `/Admin`, och det finns inget sätt att
  nå en route under `/admin` som regeln inte ser. En sökväg som routern avvisar med 400 har tomma
  `pathSegments()` och matchar inget `under`/`exactly` (roten `/` har också tomma segment men
  känns igen på `path()`), så den nekas om ingen `anyRequest()`-regel finns. Prefixet kontrolleras
  när matchern skapas: `/` först, inga tomma segment, inga `.`/`..`, inga `{ } * ? % \` och inga
  styrtecken (`\` finns inte i listan i uppdraget men kan aldrig matcha, eftersom routern avvisar
  avkodade `\`; ett sådant prefix är alltså alltid ett misstag). Ant-mönster (`/admin/**`,
  `*.json`) finns inte: de har egna regler för vad `*` och `**` täcker, som skiljer sig från hur
  routern matchar, och skillnader mellan regelns och routerns tolkning är precis det som ger
  förbikopplingar. Segmentvisa prefix täcker det vanliga fallet, och allt annat är en lambda.
  `method("GET", ...)` matchar även `HEAD`, eftersom routern svarar på `HEAD` med `GET`-routen;
  annars skulle en regel som bara gäller `GET` gå att kringgå med `HEAD`. Metoden jämförs
  exakt (routern är skiftlägeskänslig, så `get` är en annan metod).
- **Första matchande regel och nekat som standard.** Reglerna läses i registreringsordning och
  den första vars matcher matchar avgör, som i Spring. **Matchar ingen regel nekas förfrågan**
  (401 anonym, 403 inloggad). Spring kräver i stället `anyRequest()` sist och låter annars
  förfrågan passera; en glömd regel öppnar då en väg. Här stänger en glömd regel den, vilket
  märks direkt. `build()` samlar problemen och kastar ett `IllegalArgumentException` med en rad
  per problem; i dag är det enda problemet en regel efter `anyRequest()` (den kan aldrig matcha),
  igenkänd på instansen som `anyRequest()` returnerar. Roller kontrolleras när regeln skapas med
  samma regler som `Identity` (1–100 tecken utskrivbar ASCII utan mellanslag; ett test visar att
  båda avvisar samma värden), eftersom `spola.security` inte exporterar sin kontroll. Ett undantag
  från en matcher eller regel fångas inte: routern svarar 500, och förfrågan släpps aldrig igenom
  av ett fel.
- **Identiteten som attribut i stället för `ThreadLocal`, och varför ett saknat filter ger 500.**
  Filtret lägger alltid ett `SecurityContext` på förfrågan (`withAttribute`) under en nyckel som
  bara modulen har, och skickar den nya förfrågan vidare. Identiteten följer alltså förfrågan som
  ett värde: den syns i "Go to definition", kan inte läcka mellan förfrågningar på samma
  (virtuella) tråd och behöver ingen städning. `ThreadLocal` (Springs `SecurityContextHolder`)
  och `ScopedValue` används inte i 10b; identitet utanför webblagret är ett senare uppdrag.
  `WebSecurity.identity`, `requireIdentity` och filtret från `require` kastar
  `IllegalStateException` (500) när attributet saknas: att filtret saknas eller ligger fel är
  ett programmeringsfel, och om det i stället tolkades som anonym skulle en glömd registrering
  tyst ge fel beteende (och en regel som `permitAll` för anonyma skulle se ut att fungera). En
  andra nyckel bär den `WebSecurity` vars filter körde, så att `requireIdentity` för en anonym
  förfrågan kan kasta en `WebException` 401 som ger exakt filtrets 401-svar (med samma
  `WWW-Authenticate`); en mapper för `WebException` ser status 401 och huvudena i `headers()`.
  Når filtret samma förfrågan två gånger (registrerat både på routern och på en grupp) kastar det
  `IllegalStateException`, så att `Authenticator` aldrig anropas två gånger för en förfrågan.
- **Filtret ytterst.** Filtret registreras först på routern. Routerns filter körs för varje
  förfrågan, också 400, 404, 405 och automatiskt `OPTIONS`, så en anonym förfrågan till en okänd
  väg under en skyddad regel får 401 och inte 404 (svaret avslöjar inte vilka vägar som finns),
  och inga filter innanför ser en nekad förfrågan. Det kan inte kontrolleras av modulen (routern
  visar inte sin filterlista); det står i Javadoc, README och module-info.
- **401 kontra 403, och svar som inte skiljer på orsaker.** 401 betyder "vem är du?": anonym
  förfrågan som nekas, eller uppgifter som en mekanism avvisat. Svaret är `Problem.of(401)` utan
  `detail` och utan `instance`, med ett `WWW-Authenticate: Basic realm="<realm>",
  charset="UTF-8"` per `HttpBasic` i registreringsordning (RFC 7617 §2.1; utan `HttpBasic`
  inget fält). 403 betyder "jag vet vem du är, och du får inte": `Problem.of(403)` utan
  `WWW-Authenticate`, så att en webbläsare inte frågar efter nya uppgifter i onödan. Båda har
  `Cache-Control: no-store`. Svaren byggs en gång i `build()` och är byte för byte lika oavsett
  orsak (fel lösenord, okänd användare, avstängt konto, trasig Base64, saknad roll). Ett svar som
  skiljer på orsaker hjälper den som gissar konton (finns användaren, är kontot avstängt) och
  kan läcka indata; `instance` utelämnas eftersom svaret är detsamma för alla vägar och inte
  behöver eka sökvägen. Modulen skriver inga loggrader alls (auditloggning hör till uppdrag 18),
  och inget svar eller undantagsmeddelande innehåller användarnamn, lösenord eller huvudets värde
  (testat med markörsträngar mot varje svar och varje routerloggrad i sviten).
- **Tester** går genom en riktig `HttpServer` (127.0.0.1, port 0) med rå socket (för exakta
  huvuden och felaktiga värden) och `HttpClient`, en `InMemoryUserStore` med
  `Pbkdf2PasswordHasher.of(10_000, 16, 32)` och en räknande lagring och hasher. Varje svar och
  varje skickat `Authorization`-värde sparas, och `LeakTests` kör sist och letar efter markörer
  (`zUsr`, `zPwd`, RFC-exemplen, varje skickat värde) i alla svar och loggrader.

## Sessioner (`spola.web`)

Uppdrag 11a, första delen av behovet "Inloggning med sessioner och CSRF-skydd för ett JSON-API"
(appen psykologen). HTTP-sessioner i minnet, motsvarigheten till servlet-API:ts `HttpSession`
med Spring Boots `server.servlet.session.*`. **11b** (inloggning och utloggning med sessionen i
`spola.security.web`) och **11c** (CSRF-skydd med `XSRF-TOKEN`) bygger på det här. Sessions-id:t
ska bära inloggningen i 11b, så koden är säkerhetskritisk och bör granskas av en människa.

### API (paket `spola.web`)

| Typ | Roll |
|---|---|
| `Sessions` | final, oföränderlig konfiguration, trådsäker: `inMemory(SessionOptions)`, `inMemory(SessionOptions, InstantSource)`, `filter()`, `size()`; statiska `find(request)` → `Optional<Session>`, `getOrCreate(request)`, `renew(request)` → `Session`, `invalidate(request)` |
| `Session` | final, trådsäker: `get(AttributeKey<T>)` → `Optional<T>`, `put(AttributeKey<T>, T)`, `remove(AttributeKey<?>)`; ingen `id()` |
| `SessionOptions` | record `(idleTimeout, maxSessions, cookieName, cookie)` med `DEFAULT` (30 minuter, 10 000, `SESSION`, oförändrad builder) och `withX` |

Interna klasser: `SessionEntry` (en session i lagringen: attribut, nyckel, senaste användning,
generation), `SessionState` (förfrågans sessionstillstånd, attributet som filtret lägger på
förfrågan), `Sessions.Key` (SHA-256 av ett id som fyra `long`) och `Sessions.Issued` (en ny
session med sitt id, bara mellan skapandet och förfrågans tillstånd). Inga nya `requires`
(`SecureRandom`, `MessageDigest` och `Base64` finns i `java.base`), inga ändringar i
`spola.http` eller någon annan modul, ingen reflektion och inga loggrader.

### Beslut

- **I `spola.web`, inte i en ny modul eller i `spola.security.web`.** Sessioner hör till
  webblagret i Spring (servlet-API:ts `HttpSession`, Tomcats sessionshantering), inte till Spring
  Security, och de används också utan inloggning (en varukorg, ett formulär i flera steg).
  `MONSTER.md` säger att en befintlig modul byggs ut före en ny, och allt som behövs finns i
  `spola.web` (`Filter`, attribut, `cookieAll`, `ResponseCookie`, `WebException`) och i
  `java.base`. En egen modul hade krävt en brygga till `spola.web` för samma typer. 11b kopplar
  sessionen till `spola.security.web`, som redan kräver `spola.web`.
- **API:ts form: statiska metoder som tar förfrågan.** Att skapa, förnya och ogiltigförklara
  ändrar svaret (`Set-Cookie`), och tillståndet för det hör till förfrågan, inte till sessionen:
  samma `Session` kan användas av flera samtidiga förfrågningar, men bara förfrågan vet vilken
  cookie dess svar ska ha. Därför är `find`, `getOrCreate`, `renew` och `invalidate` statiska
  metoder på `Sessions` som läser förfrågans tillstånd, på samma sätt som
  `WebSecurity.identity(request)` i 10b, och `Session` har bara attributen. Tillståndet ligger
  som attribut under en nyckel som bara `Sessions` har (`withAttribute`), inte i en
  `ThreadLocal`: det följer förfrågan som ett värde och kan inte läcka mellan förfrågningar på
  samma tråd. Utan filtret saknas attributet och metoderna kastar `IllegalStateException` (500)
  med ett meddelande om att registrera `filter()`, så att en glömd registrering märks i stället
  för att alla förfrågningar ser ut att sakna session (samma skäl som i 10b). Når filtret en
  förfrågan som redan har tillståndet (registrerat två gånger, på routern och i en grupp, eller
  två `Sessions` på samma router; nyckeln är gemensam för alla `Sessions`) kastar det
  `IllegalStateException`.
- **`Session` är en vy per förfrågan, och `renew` gäller direkt också för förfrågningar som
  redan hanteras.** Lagringen håller en `SessionEntry` med en generation som `renew` räknar upp.
  Filtret ger förfrågan en `Session` med generationen den hittade, och varje `get`, `put` och
  `remove` kontrollerar under postens lås att posten lever och att generationen stämmer. När
  en förfrågan förnyar sessionen följer dess egen vy med (samma objekt fungerar efter `renew`),
  medan andra förfrågningars vyer, som kom med det gamla id:t, ser en avslutad session: `get`
  ger tomt och `put` kastar. Uppdraget säger att det gamla id:t ska sluta gälla direkt. Utan
  generationen skulle en förfrågan som kom med det gamla id:t strax före en inloggning (en
  angripare som planterat id:t och håller en långsam förfrågan öppen) kunna läsa eller skriva i
  sessionen efter inloggningen. Uppslagningen kontrollerar dessutom att posten fortfarande ligger
  under nyckeln den hittades under, så en uppslagning som tävlar med `renew` inte får sessionen.
  Följden (en känd begränsning): en samtidig förfrågan med det gamla id:t som sedan anropar
  `getOrCreate` får en ny session och ett eget `Set-Cookie`, och det som webbläsaren tar emot
  sist gäller. Tomcat låter en förfrågan som redan har `HttpSession`-objektet fortsätta;
  avvikelsen står i README.
- **Id:t och slumpkällan.** 32 byte från en `SecureRandom` per `Sessions` (`new
  SecureRandom()`, alltså plattformens icke-blockerande standard, inte `getInstanceStrong()`
  som kan blockera på `/dev/random`), kodade som Base64url utan utfyllnad: exakt 43 tecken ur
  `[A-Za-z0-9_-]`, 256 bitar (behovet kräver minst 128). Bara servern skapar id; ett id som
  klienten hittat på blir aldrig en session (ingen "adoption"). Byte-arrayen nollställs efter
  kodningen. En ny nyckel läggs in med `putIfAbsent`, och vid en (i praktiken omöjlig)
  krock dras ett nytt id.
- **Uppslagningen på SHA-256.** Lagringen är en `ConcurrentHashMap<Key, SessionEntry>` där
  nyckeln är SHA-256 av id:ts ASCII-byte (fyra `long`, så `equals` och `hashCode` är billiga).
  Själva id:t sparas inte i lagringen, i `SessionEntry`, i `Session` eller i något annat fält som
  lever efter att svaret byggts: förfrågans tillstånd håller ett nytt id bara tills filtret har
  lagt `Set-Cookie` på svaret och nollar fältet då. Tiden för en uppslagning beror därför bara
  på hashvärdet av klientens text, inte på hur stor del av ett giltigt id den har gissat rätt
  (ingen jämförelse tecken för tecken med ett hemligt värde), och en minnesdump innehåller inga
  giltiga id. Ett värde som inte har exakt formatet (längd 43, alfabetet, alltså inga
  citattecken, ingen utfyllnad, inga `+` eller `/`) hashas aldrig. Id:t syns aldrig i
  `toString()` (`Session` visar antalet attribut, `Sessions` antal, gräns, tid och cookienamn,
  `Key` och de interna klasserna bara sitt namn), i undantag, loggrader eller problem-svar
  (testat med alla id som testerna sett, se nedan).
- **Cookien in.** `request.cookieAll(cookieName)`: alla värden provas i ordning och det första som
  pekar på en levande session används, som i Tomcat; det kan finnas flera när en gammal cookie
  med en annan `Path` eller `Domain` ligger kvar. Ett felaktigt, okänt eller utgånget värde ger
  ingen session, inget 400 och inget `Set-Cookie`. En förfrågan med fler än 200 cookies får
  `cookies()`-tolkningens 400 redan i filtret (fail-closed; gränsen är långt över webbläsarnas).
- **Cookien ut, och funktionen körs en gång.** `Sessions.inMemory` tillämpar `cookie`-funktionen
  en gång på `ResponseCookie.of(cookieName, <43 A>)` och bygger resultatet. `null`, ett fel från
  `build()` (`SameSite=None` utan `Secure`, `__Host-` med `Domain` eller utan `Secure`,
  felaktig `path`), en builder för ett annat namn eller värde, och `Max-Age=0` (som skulle ta
  bort cookien direkt) ger `IllegalArgumentException`; ett annat undantag från funktionen släpps
  igenom oförändrat. Varje sessionscookie byggs sedan som `ResponseCookie.of(cookieName, id)` med
  mallens attribut (`Path`, `Domain`, `Max-Age`, `Secure`, `HttpOnly`, `SameSite`), och
  borttagningen som `ResponseCookie.of(cookieName, "")` med samma attribut och sist
  `maxAge(Duration.ZERO)`, så att `Path`, `Domain` och `Secure` stämmer med cookien som sattes.
  Uppdraget beskriver funktionen som något som körs på `ResponseCookie.of(cookieName, id)`; för
  en ren funktion (som `cookie -> cookie.secure(secure)`) är resultatet detsamma, men att köra
  den en gång gör att fel märks vid uppstart och inte vid första inloggningen, att en funktion
  med sidoeffekter eller som läser föränderlig konfiguration inte kan ge olika cookies (eller ett
  undantag) mitt i drift, och att ingen användarkod körs per svar. Standard är `Path=/`,
  `HttpOnly`, `SameSite=Lax`, ingen `Max-Age` (en sessionscookie). `Secure` sätts bara när
  funktionen säger det, aldrig efter schemat, eftersom schemat är `http` bakom en TLS-proxy
  (automatiskt `Secure` efter schemat och `X-Forwarded-Proto` är ett eget behov).
- **Ett `Set-Cookie` med det slutliga utfallet, och `Cache-Control`.** Förfrågans tillstånd
  håller ett nytt id (skapad eller förnyad) eller en markering för borttagning, och varje steg
  ersätter det förra: skapad och sedan ogiltigförklarad ger bara borttagningen, ogiltigförklarad
  och sedan skapad bara det nya id:t, skapad och förnyad bara det sista id:t. Filtret lägger till
  fältet på svaret som kommer tillbaka från `next.proceed`, också ett svar från ett mappat
  undantag (en handler som skapat en session och sedan kastat ger 500 med cookien, eftersom
  sessionen finns). Ett svar som sätter eller tar bort cookien får `Cache-Control: no-store` om
  det saknar `Cache-Control`, så att en delad cache inte sparar ett svar med ett sessions-id; ett
  `Cache-Control` som handlern satt behålls.
- **Undantag som går förbi filtret, och anrop efter svaret.** Ett undantag som routern inte
  mappar (en `RequestBodyException`, som servern besvarar, eller ett `Error`) passerar filtret,
  och då kan inget svar bära ett nytt id. En session som förfrågan skapat eller förnyat kan då
  aldrig nås och tas bort direkt i stället för att ligga kvar till utgången. När filtret har
  byggt svaret ger `getOrCreate` (som behöver skapa) och `renew` `IllegalStateException`: id:t
  skulle aldrig nå webbläsaren (en tråd som lever kvar efter förfrågan). `find` och
  `invalidate` fungerar fortfarande (`invalidate` utan `Set-Cookie`).
- **Livscykel.** `find` skapar aldrig en session. `getOrCreate` ger förfrågans levande session
  eller skapar en. `renew` (`changeSessionId`) ger en levande session ett nytt id under postens
  lås: den nya nyckeln läggs in, den gamla tas bort, generationen räknas upp, så det gamla id:t
  slutar gälla när anropet returnerar; attributen behålls och antalet sessioner är oförändrat.
  Utan session skapar `renew` en ny. `invalidate` avslutar posten (tar bort den ur lagringen och
  tömmer attributen) och svaret tar bort cookien; utan session gör den ingenting. En avslutad
  post kommer aldrig tillbaka, och ett `Session`-objekt för den ger tomt från `get`, gör
  ingenting i `remove` och kastar `IllegalStateException` i `put` (som `HttpSession`). `put`
  kontrollerar värdet mot nyckelns typ (`ClassCastException` genom en rå nyckel, utan värdet i
  meddelandet) och avvisar `null` (`NullPointerException`; ta bort med `remove`). Värdena sparas
  som referenser, utan kopiering eller serialisering.
- **Utgång och städning utan bakgrundstråd.** En session har gått ut när `nu >= senast använd +
  idleTimeout`. Att filtret hittar sessionen räknas som användning (även om handlern aldrig
  läser den), och det gör också att skapa och förnya den; `get` och `put` gör det inte. Tiden
  kommer från en `InstantSource` (systemklockan om ingen anges; testerna styr den). En utgången
  post avslutas när den slås upp (av filtret eller av en vy) och tas då bort. Filtret städar
  dessutom bort alla utgångna poster högst en gång per minut enligt klockan, på en förfrågan som
  passerar (den som vinner `compareAndSet` på nästa tidpunkt gör jobbet; går klockan bakåt förbi
  förra städningen städas det direkt). Det finns ingen bakgrundstråd, så `Sessions` behöver ingen
  `close()` och inga trådar läcker från tester eller omstarter av routern. Kostnaden är en
  genomgång av alla sessioner på en förfrågans tråd en gång per minut (10 000 som standard).
- **Gränsen med 503 i stället för att ta bort sessioner.** En `AtomicInteger` räknar sessionerna
  och reserveras med `compareAndSet` innan en post skapas, så antalet aldrig går över
  `maxSessions` ens när många förfrågningar skapar samtidigt (testat med 200 virtuella trådar mot
  gränsen 100). Är gränsen nådd städas först, och är den fortfarande nådd ger `getOrCreate` (och
  `renew` utan session) `WebException` 503 med den fasta detaljen `The server cannot take more
  sessions right now.`, som blir ett problem-svar utan `Set-Cookie`. Befintliga sessioner tas
  aldrig bort för att ge plats (ingen LRU): annars kunde den som kan skapa sessioner, alltså vem
  som helst, logga ut alla andra. Att förnya en befintlig session räknas inte som en ny. Varje
  städning noterar den tidigaste utgången bland sessionerna som finns kvar (eller `nu +
  idleTimeout` för dem som skapas därefter, och användning skjuter bara utgången framåt); vid
  gränsen hoppas städningen över så länge klockan inte har nått den tidpunkten, eftersom ingen
  session kan ha gått ut. Annars skulle en ström av nekade förfrågningar gå igenom hela
  lagringen var för sig. Den undre gränsen kan vara fel med den tid en städning tar när
  sessioner skapas samtidigt med den; en sådan session tas då bort så mycket senare vid gränsen
  (men aldrig senare än vid uppslagning eller minutstädning). Ingen loggrad när gränsen nås
  (utanför uppdraget; `size()` finns för mätning).
- **Kontroll av `SessionOptions`.** Den kanoniska konstruktorn (och därmed varje `withX`)
  kontrollerar sina värden och samlar alla problem i ett `IllegalArgumentException` med en rad
  per problem: `idleTimeout` minst 1 sekund och högst 365 dagar, `maxSessions` 1–10 000 000,
  `cookieName` en `token` (RFC 9110 §5.6.2, citerat och escapat i meddelandet). `null` ger
  `NullPointerException`. Övre gränsen för `maxSessions` håller minnet inom rimliga gränser även
  vid en felskrivning; 365 dagar är längre än någon rimlig inaktivitet.
- **Samtidighet.** Varje `SessionEntry` skyddas av sitt eget lås, och lagringens lås tas bara
  inuti det (aldrig en entry-metod inuti en `compute` på kartan), så det finns ingen
  låsordning att bryta. `SessionState` synkroniserar för sig (en `WebRequest` får lämnas till
  andra trådar) och tar postens lås inuti sitt. Två samtidiga `renew` på samma session ger två
  `Set-Cookie`, och det som webbläsaren tar emot sist gäller (känd begränsning).
- **Tester** går genom en riktig `HttpServer` med rå socket (exakta `Set-Cookie` och
  `Cache-Control`, konstruerade cookievärden) och `HttpClient` med `CookieManager`, och en styrd
  `InstantSource` för utgång och städning. Alla routrar i sessionstesterna loggar till en
  gemensam fångad logg, och varje id som setts i ett `Set-Cookie`, varje kropp, varje
  `toString()` och varje undantagsmeddelande sparas; `SessionLeakTests` körs sist och letar
  efter vart och ett av de över 10 000 id:na i allt det.

## Inloggning med session (`spola.security.web`)

Uppdrag 11b, andra delen av behovet "Inloggning med sessioner och CSRF-skydd för ett JSON-API"
(appen psykologen). Sessionerna från 11a bär inloggningen i `spola.security.web`: motsvarigheten
till Spring Securitys `HttpSessionSecurityContextRepository` med
`SessionCreationPolicy.IF_REQUIRED`, `request.changeSessionId()` vid inloggning och
`session.invalidate()` vid utloggning. En JSON-inloggning är en vanlig handler som anropar
`Authenticator` och `WebSecurity.login`. **11c** (CSRF-skydd med `XSRF-TOKEN`) bygger på det här.
Koden är säkerhetskritisk och bör granskas av en människa.

### API (paket `spola.security.web`)

| Typ | Roll |
|---|---|
| `WebSecurity.Builder` | ny: `sessions(Sessions)`; `build()` rapporterar fler än ett anrop |
| `WebSecurity` | nya statiska `login(WebRequest, Identity)` och `logout(WebRequest)`; `filter()` kör sessionsfiltret när sessioner är påslagna |

Inga nya typer och inga nya huvudkällfiler: allt ligger i `WebSecurity`. Identiteten sparas i
sessionen under en privat `AttributeKey<Identity>` som bara `WebSecurity` har. `requires` och
`exports` är oförändrade (`Sessions` finns i `spola.web`, som redan krävs transitivt), och inget
ändrades i `spola.web` eller någon annan modul: 11a:s publika API (`Sessions.filter()`, `find`,
`renew`, `invalidate`, `Session.get`/`put`) räckte, så det lilla tillägg i `spola.web` som
uppdraget tillät behövdes inte.

### Beslut

- **Säkerhetsfiltret kör sessionsfiltret självt, ytterst.** Med `sessions(sessions)` anropar
  `security.filter()` `sessions.filter()` med resten av sina steg som kedja, så applikationen
  registrerar bara `security.filter()`. Sessionen måste vara känd innan identiteten avgörs, och
  säkerhetsfiltret ska ligga ytterst (10b). Två filter som båda måste ligga först, i en viss
  ordning, är en fälla; ett filter som kör det andra går inte att registrera fel. Registrerar
  applikationen ändå `sessions.filter()` når sessionsfiltret samma förfrågan två gånger och
  kastar `IllegalStateException` (11a), alltså 500. Ligger `sessions.filter()` före
  säkerhetsfiltret gäller det varje förfrågan, och säkerhetsfiltret byter då ut 11a:s meddelande
  ("register one Sessions.filter(), once") mot ett som säger att bara `security.filter()` ska
  registreras: det känner igen fallet på att sessionsfiltret kastade innan det kallade vidare på
  kedjan (en flagga som kedjan sätter), och behåller det ursprungliga undantaget som orsak.
  Ligger `sessions.filter()` efter säkerhetsfiltret ger varje förfrågan som reglerna släpper
  igenom 500, med 11a:s meddelande (säkerhetsfiltret ser inte filtren innanför sig); nekade
  förfrågningar får sitt 401/403. Båda fallen märks vid första försöket att logga in.
  Säkerhetsfiltret kontrollerar först att det inte redan kört (`SecurityContext` på förfrågan,
  10b) och kör sedan sessionsfiltret, så att "registrerat två gånger" får säkerhetsfiltrets eget
  meddelande. `sessions(...)` två gånger (också samma instans) är en rad i `build()`s
  `IllegalArgumentException`, efter raderna om regler: två sessionslager kan inte båda gälla, och
  det sista anropet vinner inte tyst.
- **Ordningen i filtret.** Sessionsfiltret, sedan 400 för flera `Authorization`-fält (10b),
  sedan identiteten ur sessionen, sedan mekanismerna, sedan reglerna. 400-kontrollen gäller också
  en förfrågan med en inloggad session: en tvetydig förfrågan är fel oavsett vem som skickar den
  (fail-closed), och 11c lägger CSRF-kontrollen mellan 400 och identiteten. Ett undantag från
  stegen efter sessionsfiltret (400, en mekanism, en regel) passerar sessionsfiltret, som då
  överger sin förfrågan (11a); inget har skapats före handlern, så inget går förlorat. Undantag
  från handlern mappas av routerns kedja innan de når sessionsfiltret, så en handler som har
  loggat in och sedan kastar får ett felsvar med den nya cookien (sessionen finns).
- **Sessionen före mekanismerna, och aldrig `Rejected`.** Finns en identitet i förfrågans levande
  session är förfrågan autentiserad med den, och inga mekanismer provas (inte heller
  `HttpBasic`). En webbläsare som loggat in med JSON skickar inga Basic-uppgifter, och en klient
  som skickar båda har redan loggat in; att pröva Basic också skulle kosta en PBKDF2-beräkning
  per förfrågan och ge två svar på samma fråga. Utan session, med en session utan identitet
  eller med en okänd eller utgången cookie provas mekanismerna som i 10b, så Basic för
  tjänst-till-tjänst fungerar bredvid sessionerna. Sessionen är inte en `RequestAuthentication`:
  en mekanism kan svara `Rejected`, och en sessionscookie ska aldrig göra det. En gammal cookie
  (utgången, utloggad, från en omstartad server) är det normala, inte ett angrepp, och om den gav
  401 skulle inloggningen på en `permitAll`-väg aldrig gå att nå med den. Därför gör en okänd
  cookie bara förfrågan anonym. Det är också därför sessionen läses direkt av filtret i stället
  för att registreras som en mekanism i en viss ordning.
- **`login` med `renew` som skydd mot session fixation.** `WebSecurity.login(request, identity)`
  kontrollerar att förfrågan passerat filtret för en `WebSecurity` med sessioner, anropar
  `Sessions.renew(request)` och sparar identiteten i sessionen. `renew` ger en levande session
  ett nytt id atomärt (det gamla slutar gälla direkt, också för förfrågningar som redan
  hanteras) och behåller attributen, som `changeSessionId()`; utan session skapar den en, som
  `HttpSessionSecurityContextRepository` gör när den sparar. Ett id som fanns före inloggningen,
  och som en angripare kan ha planterat, bär alltså aldrig inloggningen, och ett påhittat id blir
  aldrig en session (11a). Identiteten sparas efter `renew`, så den finns aldrig under det gamla
  id:t. Vid sessionsgränsen ger `renew` utan session 503 (11a), och då sparas ingenting. Förfrågans
  eget `SecurityContext` ändras inte: det är ett oföränderligt värde på förfrågan som filtret
  satte och som reglerna redan prövat, och att skriva om det mitt i förfrågan vore en dold
  sidoeffekt som skulle kräva ett föränderligt tillstånd per förfrågan. Handlern vet redan vem
  som loggade in (den har `identity`), och nästa förfrågan ser identiteten. En `WebSecurity` utan
  sessioner, eller ingen säkerhet alls, ger `IllegalStateException` (500): en inloggning som
  tyst inte sparades vore värre. Det gäller också när applikationen själv registrerat
  `sessions.filter()` med en `WebSecurity` utan `sessions(...)`, eftersom den säkerheten aldrig
  skulle läsa identiteten ur sessionen.
- **`logout` med `invalidate`.** `WebSecurity.logout(request)` gör `Sessions.invalidate(request)`:
  hela sessionen tas bort med alla attribut, det gamla id:t slutar gälla direkt, och svaret tar
  bort cookien. Att bara ta bort identiteten och låta sessionen leva vore Springs
  `SecurityContextLogoutHandler` utan `invalidateHttpSession`; uppdraget vill ha
  `session.invalidate()`, och data från den inloggade tiden ska inte finnas kvar för nästa
  användare i samma webbläsare. Utan session gör den ingenting (inget `Set-Cookie`). Samma
  `IllegalStateException` som `login` utan sessioner.
- **Ögonblicksbild.** Sessionen bär den `Identity` som `login` fick (ett oföränderligt record), och
  filtret läser inte om användaren ur `UserStore` per förfrågan. Ändrade roller, ett avstängt
  konto eller en borttagen användare påverkar alltså inte en inloggad session förrän utloggning
  eller utgång, som i Springs standardinställning. Att läsa om användaren per förfrågan kostar en
  uppslagning per förfrågan och kräver ett `UserStore` i filtret; det är utanför uppdraget och
  står som begränsning i PROGRESS.md och Javadoc.
- **Sessioner skapas bara vid inloggning.** Säkerhetsfiltret anropar bara `Sessions.find`, aldrig
  `getOrCreate`, och sessionsfiltret skapar aldrig en session själv (11a). Anonyma förfrågningar,
  401, 403 och `/actuator/health` får därför inget `Set-Cookie`, som `IF_REQUIRED`.
- **Ingen `unauthenticated(...)`.** Behovets kod hade `.unauthenticated(Unauthenticated.status401())`
  för Springs `HttpStatusEntryPoint(UNAUTHORIZED)`. Filtrets 401 har redan bara
  `WWW-Authenticate` för `HttpBasic`-mekanismer (10b), så utan `HttpBasic` är svaret redan 401
  utan `WWW-Authenticate`, exakt det `HttpStatusEntryPoint` ger. En inställning som bara upprepar
  standardbeteendet tillför inget och vore ännu en sak att konfigurera fel (vad skulle den betyda
  med `HttpBasic` registrerad?). Testerna visar att svaret saknar `WWW-Authenticate` utan
  `HttpBasic` och har det med.
- **Ingen loggning**, som i 10b. Varken lyckade eller misslyckade inloggningar loggas
  (auditloggning är ett eget behov). Inga meddelanden eller `toString()` innehåller
  sessions-id eller lösenord; `WebSecurity.toString()` visar bara antal och om sessioner är
  påslagna.
- **Exemplet tar en `RouterBuilder`.** `LoginExample.router(routes, security, authenticator)` får
  routerns builder i stället för att anropa `Router.builder()` själv, så att testerna kan ge en
  som loggar till deras fångade logg (kriterium 9 gäller varje loggrad routern skriver), har en
  mapper som räknar om `/api/auth/me`-handlern kastade och har fler vägar. README säger att en
  app skriver `Router.builder()` direkt.
- **Tester** går genom en riktig `HttpServer` (127.0.0.1, port 0) med `HttpClient` och
  `CookieManager` (behovets flöde och 200 samtidiga klienter) och rå socket (exakta huvuden och
  cookievärden: gamla, påhittade och utgångna id), en `InMemoryUserStore` med
  `Pbkdf2PasswordHasher.of(10_000, 16, 32)` och en styrd `InstantSource` för utgången. Alla
  routrar loggar till `Support.LOG`, varje svar sparas i `Support.REPLIES` (så att 10b:s
  `LeakTests` också ser dem), och varje sessions-id i ett `Set-Cookie`, varje påhittat id, varje
  undantagsmeddelande och `toString()` sparas. `LoginLeakTests` körs sist bland 11b:s tester och
  letar efter varje id och lösenord i alla svar (utom id:t i svarets eget `Set-Cookie`), loggrader
  och texter. Mutationer av koden (ingen `renew`, mekanismerna före sessionen, ingen
  `invalidate`, ingen kontroll av sessioner, ett id eller lösenord i ett svar eller en loggrad)
  fångas av testerna.

## CSRF-skydd (`spola.security.web`)

Uppdrag 11c, sista delen av behovet "Inloggning med sessioner och CSRF-skydd för ett JSON-API"
(appen psykologen). Ändrande anrop skyddas mot CSRF med Angulars standardmönster, motsvarigheten
till Spring Securitys `csrf(...)` med `CookieCsrfTokenRepository.withHttpOnlyFalse()` och
`CsrfTokenRequestAttributeHandler` plus ett `CsrfCookieFilter` som sätter cookien på första
svaret. Med 11a och 11b fungerar nu behovets hela flöde. Koden är säkerhetskritisk och bör
granskas av en människa.

### API (paket `spola.security.web`)

| Typ | Roll |
|---|---|
| `Csrf` | ny, final, oföränderlig och trådsäker: `cookie()`, `withCookie(UnaryOperator<ResponseCookie.Builder>)`, konstanterna `COOKIE_NAME` (`XSRF-TOKEN`) och `HEADER_NAME` (`X-XSRF-TOKEN`) |
| `WebSecurity.Builder` | ny: `csrf(Csrf)`; `build()` rapporterar fler än ett anrop och en felaktig cookie-funktion |
| `WebSecurity` | `filter()` kontrollerar förfrågan och sätter cookien; `login` och `logout` ger en ny token; `toString()` visar `, csrf` |

Interna klasser: `Csrf.Protection` (en per `WebSecurity`: cookiens attribut och slumpkällan) och
`Csrf.Exchange` (förfrågans CSRF-tillstånd, ett attribut under en nyckel som bara `WebSecurity`
har). `requires` och `exports` är oförändrade (`SecureRandom`, `MessageDigest` och `Base64` finns
i `java.base`, `ResponseCookie` och `Responses` i `spola.web`), och inget ändrades i
`Build.java`, `spola.web` eller någon annan modul: 11a:s och 08c:s publika API räckte.

### Beslut

- **Dubbelinskickad cookie, och servern sparar inga token.** Svaret sätter cookien `XSRF-TOKEN`
  med en slumpad token, utan `HttpOnly`, så att appens JavaScript kan läsa den; Angular kopierar
  den till huvudet `X-XSRF-TOKEN` på ändrande anrop, och filtret jämför huvudet med cookien i
  samma förfrågan. Skyddet vilar på två saker som webbläsaren garanterar: en sida från en annan
  origin kan få webbläsaren att skicka cookien men kan inte läsa den (cookies är per domän och
  `Set-Cookie` syns aldrig för skript), och den kan inte sätta ett eget huvud på ett anrop till en
  annan origin utan en CORS-förfrågan som servern godkänner. En förfalskad förfrågan har alltså
  cookien men aldrig ett matchande huvud. Eftersom förfrågan bär båda kopiorna behöver servern
  inte spara något, som Springs `CookieCsrfTokenRepository`: ingen token i minnet eller i
  sessionen, inget att städa, dela mellan servrar eller förlora vid omstart, och skyddet fungerar
  likadant med och utan `sessions(...)` (också med bara `HttpBasic`). En token i sessionen
  (`HttpSessionCsrfTokenRepository`) hade krävt en session redan för anonyma besökare, alltså en
  session per `GET /actuator/health`, vilket behovet uttryckligen inte vill ha (11a).
- **Vad skyddet inte täcker.** Skript på appens egna sidor (XSS) kan läsa cookien och skicka
  huvudet; det går inte att skydda mot med en token som JavaScript måste kunna läsa. En origin som
  CORS släpper igenom med `X-XSRF-TOKEN` och cookies kan skicka huvudet; om den dessutom kan sätta
  cookien (en underdomän på samma webbplats kan sätta en cookie för huvuddomänen, "cookie
  tossing") kan den skicka ett matchande par. Utan CORS för andra origins ger en planterad cookie
  ingenting, eftersom huvudet inte går att skicka. CORS är utanför uppdraget; begränsningarna står
  i Javadoc, README och PROGRESS.md.
- **Token.** 32 byte från en `SecureRandom` per `WebSecurity` (`new SecureRandom()`, plattformens
  icke-blockerande standard, inte `getInstanceStrong()`), kodade som Base64url utan utfyllnad:
  exakt 43 tecken ur `[A-Za-z0-9_-]`, 256 bitar, samma form som sessions-id i 11a. Byte-arrayen
  nollställs efter kodningen. Ett cookie- eller huvudvärde med en annan form (citattecken,
  utfyllnad, `+` eller `/`, en annan längd) räknas som saknat: det jämförs aldrig och ger, för
  cookien, en ny token på svaret. Token finns inte i något fält som lever efter svaret
  (`Exchange` håller bara tre flaggor) och syns aldrig i `toString()` (`Csrf`,
  `Csrf.Protection`, `Csrf.Exchange` och `WebSecurity` visar bara namn och antal), undantag,
  loggrader eller problem-svar.
- **Jämförelsen i konstant tid.** Huvudets ASCII-byte jämförs med varje välformat
  `XSRF-TOKEN`-värde med `MessageDigest.isEqual`, som är konstanttid för lika långa arrayer (alla
  är 43 byte), och resultaten ELLER-as utan att avbryta vid första träff. Tiden avslöjar alltså
  inte hur stor del av en token som stämmer. Det finns ingen hemlighet på servern att läcka, men
  en angripare som kan mäta tiden för en förfrågan med offrets cookie ska inte kunna gissa token
  tecken för tecken. Exakt ett `X-XSRF-TOKEN`-fält krävs: två fält (också två lika) är tvetydiga
  och ger 403, som två `Authorization`-fält ger 400 i 10b. Flera `XSRF-TOKEN`-cookies godtas (det
  räcker att huvudet matchar en av dem), eftersom en webbläsare skickar två när en gammal cookie
  med en annan `Path` eller `Domain` ligger kvar (samma skäl som för sessionscookien i 11a).
- **Metoderna som inte kontrolleras.** `GET`, `HEAD`, `OPTIONS` och `TRACE`, de säkra metoderna i
  RFC 9110 §9.2.1 och Springs `CsrfFilter.DEFAULT_CSRF_MATCHER`. Metoden jämförs exakt, som i
  routern (där `get` inte är `GET`): annars skulle `get` med gemener slippa kontrollen men ändå
  kunna nå en route som registrerats för `get`. Alla andra metoder kontrolleras, också egna
  (`PURGE`). Säkra metoder får inte ändra något; det är applikationens ansvar, som i Spring.
- **Ordningen i filtret, och varför kontrollen kommer före autentiseringen.** Sessionsfiltret
  (ytterst, 11b), 400 för flera `Authorization`-fält (10b), CSRF-kontrollen, identiteten (sessionen
  och sedan mekanismerna), reglerna. Kontrollen kommer efter sessionsfiltret eftersom det ska ligga
  ytterst och cookien ska sättas på svaret innanför det, och efter 400-kontrollen eftersom en
  tvetydig förfrågan är fel oavsett token. Den kommer före identiteten av tre skäl: en förfalskad
  förfrågan ska aldrig nå `Authenticator` (ingen PBKDF2-beräkning per förfalskning, och inga
  inloggningsförsök med offrets Basic-uppgifter i webbläsaren), svaret ska vara detsamma oavsett
  vem som skickar (403 också anonymt på en skyddad väg, som i Spring där `CsrfFilter` ligger före
  autentiseringsfiltren) och kontrollen behöver ingen identitet. Svaret är filtrets befintliga
  403 (`Problem.of(403)`, ingen `detail`, `Cache-Control: no-store`, byggt en gång): en förfalskad
  förfrågan ska inte lära sig något mer än att den nekades, och en egen status eller detalj hade
  skilt "fel token" från "nekad av en regel" i onödan.
- **När cookien sätts.** Saknar förfrågan en välformad `XSRF-TOKEN`-cookie lägger filtret ett
  `Set-Cookie` med en ny token på svaret som kommer tillbaka, vilket svar det än är: handlerns,
  401, 403 (också kontrollens eget), 404, 405 och mappade fel, eftersom routern mappar undantag
  innan filtret ser svaret. Angular har då cookien före sitt första `POST`, oavsett vad appen
  hämtar först, och det behövs inget eget filter som Springs `CsrfCookieFilter` (Spring laddar
  token lat sedan 6.0 och sätter cookien bara när något läser den). En förfrågan som har en
  välformad cookie får ingen ny, så token byts inte vid varje förfrågan (det skulle bryta
  parallella anrop från samma sida). Undantag som routern mappar utanför säkerhetsfiltret (400
  för flera `Authorization`-fält eller över 200 cookies, ett undantag från en mekanism, en matcher
  eller en regel) och ett fel i förfrågans kropp går förbi filtret och får ingen cookie; att fånga
  dem i filtret hade gått förbi applikationens exception mappers.
- **När cookien byts.** `WebSecurity.login` och `WebSecurity.logout` markerar förfrågans
  `Exchange`, och svaret får en ny token (Springs `CsrfAuthenticationStrategy` och
  `CsrfLogoutHandler`). Webbläsaren ersätter cookien, och en token från före inloggningen matchar
  sedan ingenting: en token som en angripare lyckats lära sig eller plantera före inloggningen
  gäller inte efter den. `login` förnyar sessionen först och markerar sedan, så en inloggning som
  får 503 vid sessionsgränsen inte byter token; `logout` byter alltid, också utan session.
  Markeras förfrågan efter att svaret byggts (en tråd som lever kvar) kastar den
  `IllegalStateException`, som `Sessions.renew` i 11a. Ett svar får högst ett `Set-Cookie` för
  `XSRF-TOKEN`: första svaret och en inloggning i samma förfrågan (möjligt bara på en säker
  metod) ger ett.
- **`Cache-Control`.** Ett svar som sätter `XSRF-TOKEN` får `Cache-Control: no-store` om det
  saknar `Cache-Control`, som sessionscookien i 11a, så att en delad cache aldrig lämnar ut en
  annan besökares token. Ett `Cache-Control` som handlern satt behålls.
- **Cookien och cookie-funktionen.** Cookien är `ResponseCookie.of("XSRF-TOKEN", token)
  .httpOnly(false)` ändrad av `withCookie`-funktionen; standard är
  `XSRF-TOKEN=<token>; Path=/; SameSite=Lax`, utan `HttpOnly` och `Secure`. `Secure` sätts bara
  när funktionen säger det, aldrig efter schemat (som är `http` bakom en TLS-proxy). Som i 11a
  körs funktionen en gång, i `build()`, på en platshållare med samma form som en token, och dess
  attribut (`Path`, `Domain`, `Max-Age`, `Secure`, `SameSite`) används för varje ny token: fel
  märks vid uppstart, och ingen användarkod körs per svar. `build()` provbygger cookien och
  rapporterar, en rad per problem i samma `IllegalArgumentException` som övriga
  konfigurationsfel: `null`, `HttpOnly` påslaget (Angular skulle inte kunna läsa cookien och varje
  ändrande anrop skulle få 403), en builder för en annan cookie, `Max-Age=0` (cookien skulle tas
  bort direkt) och en cookie som `ResponseCookie.Builder.build()` avvisar (`SameSite=None` utan
  `Secure`, felaktig `Path` eller `Domain`). Ett annat undantag från funktionen släpps igenom
  oförändrat. `withCookie` ersätter funktionen (som `SessionOptions.withCookie`).
- **Fasta namn och inga tillval.** Namnen är Angulars och Springs standard och finns som
  konstanter. Egna namn, en `_csrf`-parameter i formulärkroppar, maskerade token (BREACH-skydd)
  och undantag per väg (`ignoringRequestMatchers`) är utanför uppdraget och byggs när ett behov
  kommer. Utan maskering är kontrollen exakt `CsrfTokenRequestAttributeHandler`, som behovet
  använder i Spring eftersom Angular skickar cookiens råa värde.
- **Var koden ligger.** Kontrollen och cookien ligger i `Csrf.java` (en ny huvudkällfil, eftersom
  `Csrf` är en publik klass i paketet enligt uppdragets beslut och Java kräver en egen fil för den)
  med de paketprivata `Csrf.Protection` och `Csrf.Exchange` som nästlade klasser, så att
  `WebSecurity` bara får några rader: `secure` kör `Protection.begin` efter 400-kontrollen, ger
  403 eller går vidare med `Exchange` som attribut, och lägger cookien på svaret med
  `Exchange.finish`. Tillståndet per förfrågan är ett attribut, inte en `ThreadLocal`, av samma
  skäl som `SecurityContext` (10b) och sessionstillståndet (11a). `Exchange` synkroniserar,
  eftersom en `WebRequest` får lämnas till andra trådar.
- **Ett tillägg i en befintlig testfil.** Uppdraget säger att befintliga testfiler är oförändrade
  utom registreringen i `SecurityWebTests`. `SourceRulesTests` (10b) räknar huvudkällfilerna och
  väntade sig 11; med `Csrf.java` är de 12, så siffran ändrades från 11 till 12. Testets kontroller
  (inga förbjudna API:er i någon huvudkällfil) är oförändrade och gäller nu också `Csrf.java`.
  Alternativet, att lägga `Csrf` som en nästlad klass i `WebSecurity`, hade brutit mot uppdragets
  beslut om en klass `Csrf` i `spola.security.web` och gett `WebSecurity.Csrf.cookie()` i appens
  kod.
- **Tester** går genom en riktig `HttpServer` (127.0.0.1, port 0) med `HttpClient` och en
  `CookieManager` per klient, där huvudet sätts från cookien i `CookieManager` som Angular gör
  (behovets flöde och 200 samtidiga klienter), och rå socket (exakta `Set-Cookie`- och
  `Cache-Control`-rader och 30 förfalskade eller felformade förfrågningar för `POST`, `PUT`,
  `PATCH` och `DELETE`, var och en med rätt Basic-uppgifter så att en förfrågan som slank igenom
  skulle nå både `Authenticator` och handlern). Alla routrar loggar till `Support.LOG`, och varje
  token och sessions-id i ett `Set-Cookie`, varje påhittad token, varje undantagsmeddelande och
  `toString()` sparas. Varje svar sparas i `Support.REPLIES` (så att 10b:s `LeakTests` också ser
  det) som en kopia där värdet i svarets egen `XSRF-TOKEN`- och `SESSION`-cookie är utbytt mot
  `<own value>`: värdena är slumpade, och bland flera hundra token per körning skulle någon gång en
  innehålla en av de fyrteckensmarkörer (`zPwd`, `zUsr`) som `LeakTests` letar efter, så att
  bygget blev rött av en slump (det hände en gång under utvecklingen). Testet som får svaret ser
  det oförändrat och kontrollerar värdets form; `CsrfLeakTests` körs sist bland
  11c:s tester och letar efter dem och efter lösenorden i alla svar (utom värdet i svarets eget
  `Set-Cookie`), loggrader och texter. Mutationer av koden (ingen kontroll, skiftlägesokänsliga
  metoder, första av flera huvuden godtas, autentisering före kontrollen, inget byte vid
  inloggning eller utloggning, cookie på varje svar eller aldrig, `HttpOnly`, standard-Base64,
  skiftlägesokänslig jämförelse, 401 i stället för 403, inget `Cache-Control`) fångas av
  testerna.

## Lösenord från Spring: BCrypt och delegerande hasher (`spola.security`)

Uppdrag 12, hela behovet "BCrypt-hashar och delegerande lösenordshasher" (appen psykologen).
`spola.security` verifierar och skapar BCrypt-hashar i Spring Securitys format och väljer
algoritm efter ett `{id}`-prefix, som Springs `BCryptPasswordEncoder` och
`DelegatingPasswordEncoder`. En app som går över från Spring behåller sina lagrade hashar
(`{bcrypt}$2a$10$...`), och en hash som Spola skriver godtas av Spring. Koden är
säkerhetskritisk och bör granskas av en människa.

### API (paket `spola.security`)

| Typ | Roll |
|---|---|
| `BCryptPasswordHasher` | ny, publik: `defaults()` (kostnad 10), `of(cost)` (4–31), konstanterna `MIN_COST`, `MAX_COST`, `DEFAULT_COST`, `MAX_PASSWORD_BYTES` |
| `PasswordHasher` | ny statisk metod `delegating(defaultId, Map<String, ? extends PasswordHasher>)` |

Interna klasser (paketprivata): `BCrypt` (primitiven: `hash(password, salt, cost)` på råa byte,
`format(version, cost, salt, password)` för hela strängen, bcrypts Base64 och `rounds(cost)`),
`Blowfish` (tillståndet, nyckelschemat, krypteringen och tabellerna härledda från π) och
`DelegatingPasswordHasher`. `requires` och `exports` är oförändrade (allt finns i `java.base`),
och inget ändrades i `Build.java`, `Authenticator`, `InMemoryUserStore` eller någon annan modul.
Fyra nya huvudkällfiler, så `SourceRulesTests` räknar 17 filer i stället för 13.

### Beslut

- **Varför bcrypt implementeras i Spola (REGLER §4).** JDK:n har ingen bcrypt, och dess `Cipher`
  för `Blowfish` går inte att använda: bcrypts dyra nyckelschema (`EksBlowfishSetup`) ändrar
  P-arrayen och S-boxarna direkt, 2^kostnad gånger, och det tillståndet går inte att ge en
  JDK-cipher. Algoritmen skrivs därför med heltalsaritmetik efter Provos och Mazières, "A
  Future-Adaptable Password Scheme" (USENIX 1999), med OpenBSD:s `bcrypt.c` som referens för
  formatet. Allt annat bygger på JDK:ns primitiver: `SecureRandom` för saltet,
  `MessageDigest.isEqual` för jämförelsen, `CharsetEncoder` för UTF-8 och `BigInteger` för π.
  Korrektheten visas på tre oberoende sätt: (1) de publicerade vektorerna ur jBCrypt (alla 20,
  också kostnad 12) och Openwall crypt_blowfish, också de med råa byte `a3` och `ff ff a3`, ger
  exakt rätt sträng; (2) Blowfish-kärnan med det vanliga nyckelschemat ger samma chiffertext som
  JDK:ns `Cipher.getInstance("Blowfish/ECB/NoPadding")` för 250 slumpade nycklar (4–56 byte)
  och block, och Eric Youngs fem vektorer stämmer; (3) vektorer från en oberoende implementation
  (libxcrypt) med UTF-8, 71–73 byte och användningsfallets salt ger exakt rätt sträng med `$2a$`,
  `$2b$` och `$2y$`. Vektorn för `"hemligt"` med kostnad 10 och saltet `0x10`–`0x1f` visar att
  Spolas hashar är byte för byte vad Springs `BCryptPasswordEncoder` skriver.
- **Konstanterna härleds från π.** Blowfishs initiala P-array (18 ord) och S-boxar (4 × 256 ord)
  är per definition de första 33 344 bitarna av π:s bråkdel. De räknas fram en gång per JVM, i en
  statisk hållare (`Blowfish.Pi`) och alltså vid första användningen, med Machins formel
  `π = 16·arctan(1/5) − 4·arctan(1/239)` i fixpunkt med `BigInteger` och 64 skyddsbitar (varje av
  de ungefär 9 300 termerna avrundas högst två enheter, så felet håller sig under 2^20 enheter).
  Det tar ungefär 50–300 ms en gång. En tabell inkopierad från en annan implementation vore
  inkopierad källkod (REGLER §1) och 1 042 ord som ingen kan granska för hand; formeln går att
  läsa, och testerna kontrollerar åtta kända värden (bland dem första och sista ordet) och att
  ingen huvudkällfil innehåller kända konstanter eller en tabell av tal. Varje hash arbetar på en
  egen kopia, och de härledda tabellerna ändras aldrig (testet läser dem före och efter hashar).
- **`$2a$`, `$2b$` och `$2y$` räknas likadant, som i jBCrypt (Springs `BCrypt`).** Nyckeln är
  lösenordets byte följda av en NUL-byte, upprepade cykliskt; varje expansion läser 72 byte,
  från början. Byte läses utan tecken (en byte ≥ 0x80 teckenutvidgas aldrig, vilket var felet i
  crypt_blowfish:s gamla `$2a$`, som `$2x$` bevarar). Två andra implementationer skiljer sig, men
  ingen av skillnaderna kan nå `BCryptPasswordHasher`, som bara tar 1–72 byte giltig UTF-8:
  OpenBSD:s gamla `$2a$` räknade fel bara för lösenord över 255 byte, och motåtgärden i
  crypt_blowfish:s och libxcrypt:s `$2a$` gäller bara byte-strängar som inte är giltig UTF-8 (för
  `ff ff a3` ger libxcrypt en annan hashdel med `$2a$`; jBCrypt och Spola ger samma som med
  `$2b$`). `$2x$` (crypt_blowfish:s buggkompatibla läge) och `$2$` (utan NUL) stöds inte: Spring
  skriver dem aldrig, och de skulle kräva en andra, avsiktligt felaktig variant av algoritmen.
  Nya hashar har alltid `$2a$`, som Springs standard.
- **Antalet varv räknas som `long`.** Kostnaden `c` ger `2^c` varv. I Java är `1 << 31`
  negativt, så en loop med `int` skulle för kostnad 31 ge noll varv, alltså en hash som kan
  räknas på en bråkdel av en sekund. `BCrypt.rounds(cost)` ger `1L << cost`, och loopen räknar med
  en `long`. Ett test kontrollerar värdena för kostnad 4 och 31, och ett annat att primitiven
  använder hjälpmetoden.
- **72 byte och U+0000.** bcrypt läser bara 72 byte av nyckeln, så ett längre lösenord räknas
  tyst som sina första 72 byte. Det är CVE-2025-22228 i Spring Security, där två olika långa
  lösenord med samma början godtogs som samma. Nyckeln är dessutom lösenordet följt av NUL,
  upprepat, så `"a"` och `"a\u0000a"` ger samma nyckelström (`a 00 a 00 ...`), och
  C-implementationer slutar läsa vid NUL. Därför godtar `BCryptPasswordHasher` bara lösenord på
  1–72 byte UTF-8 utan U+0000; med de reglerna ger två olika lösenord alltid olika nyckelströmmar.
  Ett lösenord över 72 `char` avvisas före kodningen (varje `char` blir minst en byte). Ett
  avvisat lösenord ger `IllegalArgumentException` i `hash` (utan lösenordet i meddelandet) och
  `false` i `matches`, och ingen bcrypt räknas (testerna visar det mot en lagrad hash med kostnad
  31). Förhashning av långa lösenord (SHA-512 före bcrypt) är uteslutet: Spring skulle inte kunna
  verifiera sådana hashar. Konsekvensen, som står i Javadoc, README och PROGRESS.md: en användare
  vars lösenord i Spring var längre än 72 byte (eller hade NUL) måste byta lösenord. Lösenordet
  kodas som UTF-8 med `CodingErrorAction.REPORT` och normaliseras inte, som i
  `Pbkdf2PasswordHasher`; Spring gör `getBytes(UTF_8)`, så varje godtaget lösenord blir samma
  byte i båda. De kodade byten, nyckelorden, Blowfish-tillståndet, mellanblocken och den räknade
  hashen nollställs efter användning.
- **Strikt tolkning med kanonisk Base64.** En lagrad hash godtas bara om den har exakt 60 tecken
  (kontrolleras först, så en sträng på 10 000 tecken kostar inget), börjar med exakt `$2a$`,
  `$2b$` eller `$2y$`, har kostnaden som två ASCII-siffror 04–31 följda av `$`, och har 53 tecken
  ur alfabetet `./A–Za–z0–9` där både saltet (22 tecken, 16 byte) och hashen (31 tecken, 23 byte)
  är kanoniska: de oanvända låga bitarna i sista tecknet är noll (saltet slutar på en av `.Oeu`,
  hashen på en av `.CGKOSWaeimquy26`). Allt annat ger `IllegalArgumentException` i `matches` och
  `needsRehash`, med ett fast meddelande utan strängen, och `matches` tolkar hashen före
  lösenordet, som `Pbkdf2PasswordHasher`. Varför kanonisk: Springs `BCrypt.checkpw` jämför hela
  strängen med en ny kodning av saltet och hashen, så en hash med satta oanvända bitar har aldrig
  kunnat verifieras i Spring; inget går förlorat, och sträng och byte motsvarar varandra ett till
  ett. Av samma skäl måste Spolas egna hashar vara kanoniska, annars godtar Spring dem inte. De 23
  avkodade byten jämförs med `MessageDigest.isEqual`.
- **Kostnaden och `needsRehash`.** `defaults()` har kostnad 10 som `new BCryptPasswordEncoder()`,
  och `of(cost)` tar 4–31 som Spring (Javadoc rekommenderar minst 10). Tolkningen godtar också
  4–31, som behovet kräver; en lagrad hash verifieras med sin egen kostnad. Kostnad 31 är ungefär
  2 miljoner gånger kostnad 10, alltså dygn, så den som kan skriva i användarlagringen kan låsa en
  tråd med en sådan rad (dokumenterat; ett lägre tak skulle avvisa hashar som Spring godtar).
  `needsRehash` är `true` bara när den lagrade kostnaden är **lägre** än hasharens, som Springs
  `upgradeEncoding`: versionen och en högre kostnad ger `false`, och ingen bcrypt räknas. Det
  skiljer sig medvetet från `Pbkdf2PasswordHasher`, som vill ha en ny hash vid varje skillnad; en
  högre bcrypt-kostnad är starkare och ska inte sänkas av en inloggning.
- **Hasharens kostnad ska vara minst den högsta lagrade.** Följden av att en högre kostnad aldrig
  sänks: `Authenticator` kontrollerar en okänd användare mot en dummyhash som hasharen gjort, alltså
  med hasharens kostnad, och en känd användare med den lagrade kostnaden. Med hashar från
  `new BCryptPasswordEncoder(12)` och `defaults()` (kostnad 10) tar en känd användare med fel
  lösenord ungefär fyra gånger så lång tid som en okänd, och eftersom hashen aldrig byts ut går
  det aldrig över; tiden visar vilka användarnamn som finns. Det löses med konfiguration, inte
  kod: Javadoc för `BCryptPasswordHasher` (`defaults()`, `needsRehash` och avsnittet om
  omhashning), `PasswordHasher.delegating`, README (en punkt vid exemplet och raden om
  `needsRehash` i avvikelsetabellen) och PROGRESS.md säger att hasharens kostnad ska vara minst
  den högsta lagrade, till exempel `of(12)`. Att sänka kostnaden vid inloggning hade försvagat
  hasharna och gått emot Springs `upgradeEncoding` och uppdragets beslut, och att göra dummyhashen
  med den högsta lagrade kostnaden hade krävt ändringar i `Authenticator`, som låg utanför
  uppdraget. Åt andra hållet, efter en höjning av kostnaden, är användare med lägre kostnad
  snabbare än okända tills de har loggat in och fått en ny hash, som vid flytten mellan
  algoritmer.
- **Formatet med `{id}`.** `PasswordHasher.delegating(defaultId, hashers)` lagrar
  `{id}<delegatens hash>`, som `DelegatingPasswordEncoder`. `hash` ger `"{" + defaultId + "}"`
  följt av standarddelegatens hash, och delegatens undantag släpps igenom. I `matches` och
  `needsRehash` måste strängen börja med `{`, och id:t är texten fram till första `}`, som söks
  högst 101 tecken in (ett id är högst 100 tecken). Id:t måste vara exakt ett av de konfigurerade
  (skiftlägeskänsligt, ingen trimning), och resten går till den delegaten, vars
  `IllegalArgumentException` släpps igenom oförändrat. `needsRehash` frågar alltid delegaten om
  resten, så en felaktig rest ger undantag också för ett annat id än standard-id:t, och svaret är
  `true` om id:t inte är standard-id:t eller om delegaten säger det; så flyttar den befintliga
  uppgraderingen i `Authenticator` användare från `{pbkdf2}` och från lägre bcrypt-kostnad.
  Id:n är 1–100 tecken utskrivbar ASCII (0x21–0x7E) utan klamrar, så att de kan stå i
  `toString()` och i en loggrad utan escaping och ett `}` aldrig kan vara en del av ett id.
  Kartan kopieras i ett enda varv (så att kontrollerna och användningen ser samma innehåll), och
  resultatet är oföränderligt.
- **Ingen reservhasher, och felmeddelanden utan id.** En hash utan prefix eller med ett okänt id
  är ett datafel, som med Springs standard (`UnmappedIdPasswordEncoder`); Springs
  `setDefaultPasswordEncoderForMatches` finns inte, eftersom en reserv gör att en hash utan prefix
  tyst tolkas som en annan algoritm. En ren hash från `BCryptPasswordEncoder` verifieras direkt med
  `BCryptPasswordHasher`. Meddelandena är fasta texter som varken innehåller strängen eller texten
  mellan klamrarna, eftersom den kan vara en hash (`{$2a$10$...}`). De listar inte heller de
  konfigurerade id:na (det hade uppdraget tillåtit): ett meddelande med listan kan innehålla texten
  mellan klamrarna när den råkar vara en del av ett id, och id:na syns ändå i `toString()`.
- **`{pbkdf2}` är Spolas format.** Id:na är appens namn, och `"pbkdf2"` i användningsfallet läser
  Spolas PHC-format (`Pbkdf2PasswordHasher`), inte hex-formatet från Springs
  `Pbkdf2PasswordEncoder`, som inte stöds. Det står i Javadoc och README.
- **Primitiven är paketprivat.** Till skillnad från `Pbkdf2.deriveSha256` är en publik
  bcrypt-primitiv lätt att använda fel: den tar vilka byte som helst och kortar tyst allt efter 72,
  vilket är just CVE-2025-22228. Reglerna ligger därför i `BCryptPasswordHasher`, och bara den är
  publik. Primitiven (`BCrypt.hash` på råa byte av vilken längd som helst, också 0 och över 72)
  och vägen som formaterar hela strängen från ett givet salt (`BCrypt.format`) är paketprivata så
  att testerna kan köra alla publicerade vektorer, och `hash` och vektortesterna går genom samma
  `format`.
- **`Authenticator` och `InMemoryUserStore` är oförändrade.** De arbetar genom `PasswordHasher`.
  Dummyhashen för okända användare blir `{bcrypt}$2a$10$...` med standardalgoritmen, så en okänd
  användare kostar en bcrypt-kontroll. Medan användare flyttas mellan algoritmer kostar en känd
  användare med `{pbkdf2}` en PBKDF2-kontroll, alltså en annan tid än en okänd, så en angripare kan
  se vilka användarnamn som finns; lika tid mellan algoritmer var utanför uppdraget. En användare
  med `{pbkdf2}`-hash och ett lösenord över 72 byte loggar in, men uppgraderingen misslyckas med
  `IllegalArgumentException` från `hash`, som `Authenticator` loggar med klassnamnet, och
  PBKDF2-hashen ligger kvar.
- **Tester.** Nio nya testfiler i `spola.security`: vektortabellerna (jBCrypt, Openwall,
  libxcrypt och användningsfallet), Blowfish-kärnan mot JDK:ns `Cipher` och Eric Youngs vektorer,
  lösenordsreglerna (också mot en lagrad kostnad 31, som visar att inget räknas: en sådan kontroll
  körs på en daemontråd och misslyckas efter 5 s med meddelandet att bcrypt räknades), 44 felaktiga
  lagrade hashar, den delegerande hasharen, `Authenticator` med räknande delegater och 200
  virtuella trådar, markörer i lösenord och hashar (också mellan klamrarna) och README-exemplet
  `BCryptExample.java`, som är kompilerad testkälla och jämförs med README.

## Bakom en omvänd proxy (`spola.web`, `spola.http`)

Uppdrag 13a, första delen av behovet "Rätt schema och klientadress bakom en omvänd proxy"
(`2026-10-13-proxyhuvuden`, appen psykologen): motsvarigheten till Spring Boots
`server.forward-headers-strategy=native` (Tomcats `RemoteIpValve`). En app bakom en proxy som
avslutar TLS (Caddy, nginx) får klientens schema och adress ur `X-Forwarded-Proto` och
`X-Forwarded-For`, men bara när förfrågan kommer från en betrodd proxy. **13b** (`Secure` efter
schemat på sessions- och CSRF-cookien) bygger på det här. Huvudena är data utifrån, så koden är
säkerhetskritisk och bör granskas av en människa.

### API

| Typ | Roll |
|---|---|
| `spola.http.Request` | ny metod `remoteAddress()` → `InetSocketAddress`: TCP-anslutningens andra ände (över TLS TCP-socketen under TLS), tagen vid accept, aldrig `null`, utan namnuppslag |
| `spola.web.WebRequest` | nya metoder `scheme()` (`"http"` eller `"https"`) och `clientAddress()` (`InetAddress`); utan `ForwardedHeaders` är de `http().scheme()` och `http().remoteAddress().getAddress()` |
| `spola.web.TrustedProxies` | funktionellt gränssnitt `boolean trusts(InetAddress)` med de statiska fabrikerna `privateNetworks()` och `of(String... networks)` |
| `spola.web.ForwardedHeaders` | final klass som implementerar `Filter`; `trusting(TrustedProxies)`; oföränderlig och trådsäker |

Interna klasser i `spola.web`: `IpLiteral` (den strikta kontrollen av IP-literaler, skapandet
med `InetAddress.ofLiteral` och RFC 5952-texten i förslagen) och `ProxyNetworks` (näten i
`TrustedProxies.of` och `privateNetworks()`). `WebRequest` har en paketprivat `withClient(scheme,
address)` som bara `ForwardedHeaders` anropar (ett källkodstest kontrollerar det). `requires` och
`exports` är oförändrade i båda modulerna (allt finns i `java.base`). I `spola.http` tar
`Connection` adressen vid accept, och `HttpServer` skapar `Connection` inne i sitt
`try`-block, så att en socket utan adress (som aldrig ska förekomma efter `accept`) stängs och
loggas i stället för att stoppa accepttråden.

### Beslut

- **Ett filter i `spola.web`, inte en inställning i `spola.http`.** Behovet gäller vad
  applikationen ser (`getScheme()`, `getRemoteAddr()`), och i Spola är det `WebRequest`.
  `spola.http` beskriver anslutningen och ska fortsätta göra det: den vet inget om proxyer, och
  en inställning där skulle ändra `Request` för alla, också för kod som behöver anslutningens
  värden. Som filter är konfigurationen vanlig Java-kod på routern
  (`.filter(ForwardedHeaders.trusting(...))`), på samma sätt som `sessions.filter()` och
  `security.filter()`, och `MONSTER.md` säger att en befintlig modul byggs ut före en ny. Det
  `spola.http` saknade var avsändarens adress, så den fick `Request.remoteAddress()`, som
  motsvarar `getRemoteAddr()` och `getRemotePort()` för anslutningen.
- **Bara `ForwardedHeaders` kan ändra värdena, och `http()` beskriver anslutningen.** Kopian med
  klientens värden görs av den paketprivata `WebRequest.withClient`, och det finns inget publikt
  sätt att ändra `scheme()` eller `clientAddress()`. Ett värde som skiljer sig från anslutningens
  kommer därför alltid från en betrodd proxy, och en regel i säkerhetsfiltret kan lita på det.
  `withAttribute` och varje `WebRequest` som routern gör behåller värdena. `http()` ändras inte:
  `http().scheme()` och `http().remoteAddress()` är proxyns anslutning, så både klienten och
  proxyn går att se.
- **Regeln om betrodd avsändare.** Filtret frågar `proxies.trusts(request.http().remoteAddress()
  .getAddress())`. Är svaret `false` går förfrågan vidare oförändrad och huvudena läses inte
  alls: en obetrodd avsändare kan varken ändra värdena eller framkalla 400 med huvudena (annars
  kunde vem som helst som når porten få en förfrågan nekad med ett skräpvärde, och fel i
  tolkningen vore åtkomliga utifrån). Filtret räknar alltid från `request.http()` (avsändaren och
  huvudena) och aldrig från förfrågans nuvarande `scheme()` eller `clientAddress()`, så två
  `ForwardedHeaders` i samma kedja ger samma resultat som ett, och en adress i
  `X-Forwarded-For` kan aldrig göra sig själv till en betrodd avsändare.
- **Genomgången från höger.** Alla `X-Forwarded-For`-fält läses i ordning som en lista (RFC 9110
  §5.3), delas vid `,`, SP och HTAB runt elementen tas bort och tomma element hoppas över (§5.6.1).
  Varje proxy lägger till adressen den ser längst till höger, så det som står till höger är det
  som betrodda proxyer har skrivit, och allt till vänster om den första obetrodda adressen kan
  klienten ha skrivit själv. Därför: från höger hoppas adresser som `proxies` litar på över, och
  den första adress som `proxies` inte litar på är klienten; element till vänster om den läses
  aldrig (inte ens tolkas). Litar `proxies` på alla är klienten den vänstraste, och utan element
  är klienten avsändaren. Samma `TrustedProxies` avgör avsändaren och elementen, som
  `internalProxies` i `RemoteIpValve` (Tomcats separata `trustedProxies` och `X-Forwarded-By`
  ingår inte). Genomgången går baklänges över fälten utan att dela upp hela listan i förväg.
- **`X-Forwarded-Proto`.** Utan fältet behålls anslutningens schema. Annars krävs exakt ett fält
  vars värde, utan SP och HTAB runt, är `http` eller `https` i valfritt ASCII-skiftläge (en egen
  jämförelse, inte `equalsIgnoreCase`, så att bara ASCII viks); schemat blir värdet med gemener.
- **Varför ogiltiga värden ger 400.** Ett ogiltigt element i `X-Forwarded-For` nås bara när allt
  till höger om det kommer från betrodda proxyer, alltså har en betrodd proxy skickat något
  trasigt (eller en klient på ett betrott nät). Om filtret då föll tillbaka på avsändaren skulle
  klienten få proxyns adress, som är intern, och en regel som släpper in interna adresser skulle
  släppa in klienten. Att hoppa över elementet och läsa vidare åt vänster vore ännu sämre: då
  bestämmer det som klienten själv skrivit. 400 är fail-closed och syns direkt i stället för att
  ge fel adress i tysthet. Samma skäl för `X-Forwarded-Proto`: två fält eller ett okänt värde går
  inte att tolka säkert (Tomcat ger `http` för allt utom `https`). Filtret kastar
  `WebException(400, detail)`, så svaret är ett problem-svar genom routerns mappers, handlern och
  filtren innanför anropas inte, och det blir aldrig 500. `detail` namnger huvudet men aldrig
  värdet (svaret kan hamna i loggar och värdet är data utifrån), och filtret loggar inget.
- **De strikta IP-literalerna, och varför `InetAddress.ofLiteral` inte räcker ensam.** IPv4 är
  fyra decimala delar med 1–3 ASCII-siffror, värdet 0–255 och ingen inledande nolla (utom `0`);
  IPv6 följer textformen i RFC 4291 §2.2: grupper med 1–4 hexsiffror i valfritt skiftläge, högst
  ett `::` (som står för minst en grupp), och valfritt en IPv4-del enligt samma regel i stället för
  de två sista grupperna. Hakparenteser, zon, port och blanktecken är inte tillåtna. Kontrollen är
  en egen tolk (`IpLiteral`) som bara tar ASCII-tecken och räknar ut adressens byte. JDK:ns
  `InetAddress.ofLiteral` (JDK 22, utan namnuppslag) är för generös för data utifrån: den godtar
  bland annat `127.1`, `2130706433`, `1.2.3` (som blir `1.2.0.3`), `010.0.0.1` (som blir
  `10.0.0.1`), `[2001:db8::1]` och `fe80::1%1`. Sådana former tolkas olika av olika program, så
  en proxy och appen kunde se olika adresser i samma text. Adressen skapas först efter
  kontrollen, med `ofLiteral`, och dess byte jämförs med dem som kontrollen räknade fram: skulle
  JDK:n läsa texten på något annat sätt avvisas den. `InetAddress.getByName`, `getAllByName`,
  `getHostName` och `getCanonicalHostName` används inte i huvudkoden (ett källkodstest i
  `spola.web` och ett i `spola.http` kontrollerar det), så ett namn som `example.com` ger aldrig
  ett DNS-uppslag, och `clientAddress()` har inget värdnamn som `toString()` eller loggar kunde
  avslöja. En IPv4-mappad adress (`::ffff:203.0.113.7`) blir en `Inet4Address`, som JDK:n gör, och
  prövas som IPv4; en mappad post i `TrustedProxies.of` avvisas, så att ett IPv4-nät alltid
  skrivs som IPv4. Testerna jämför kontrollen med `ofLiteral` för 20 000 slumpade adresser i alla
  skrivsätt RFC 4291 tillåter.
- **`TrustedProxies` och näten i `privateNetworks()`.** Ett funktionellt gränssnitt, så att en
  app kan skriva egen logik som ett lambda (`address -> false`), med två fabriker. I `of` är
  varje post en IP-literal med valfri decimal prefixlängd utan inledande nolla eller tecken
  (0–32 för IPv4, 0–128 för IPv6). Bitar efter prefixet måste vara noll, och meddelandet föreslår
  nätet (`10.0.0.1/8` ger förslaget `10.0.0.0/8`), så att ett skrivfel syns vid uppstart i stället
  för att lita på fel nät. Alla fel samlas i ett `IllegalArgumentException` med en rad per post,
  som citerar posten (konfiguration, inte en hemlighet, escapad och kortad). `null` ger
  `NullPointerException` och arrayen kopieras. Ett IPv4-nät matchar bara `Inet4Address` och ett
  IPv6-nät bara `Inet6Address` (adressens byte är 4 respektive 16, och zonen räknas inte).
  `privateNetworks()` är `of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8",
  "::1/128", "fc00::/7")`: RFC 1918, loopback och unika lokala IPv6-adresser, som behovet säger.
  Tomcats standard för `internal-proxies` omfattar fler nät, bland annat länklokala adresser och
  `100.64.0.0/10`; länklokala adresser kan sättas av vem som helst på samma länk och
  `100.64.0.0/10` delas av många kunder hos en operatör (CGNAT), så Spola litar inte på dem
  (avvikelserad i README). Det finns inget standardval: `trusting(...)` tar alltid näten
  uttryckligen. `privateNetworks()` litar på hela nätet, inte bara på proxyn, och med Dockers
  userland-proxy (och i Docker Desktop) kan en direkt anslutning till en publicerad port komma
  från bryggans gateway (till exempel `172.17.0.1`). En klient på ett betrott nät som går genom
  proxyn hoppas också över som en proxy och väljer därmed själv sin klientadress (samma modell som
  `internal-proxies`). README och Javadoc säger att bara proxyns port ska publiceras och att appen
  då ska lita bara på proxyns adress. `toString()` listar
  posterna som de gavs, så att en loggad konfiguration går att känna igen.
- **Ordningen i kedjan.** `ForwardedHeaders` ska registreras först, ytterst på routern. Då ser
  alla filter efter det (till exempel `sessions.filter()` och `security.filter()`), gruppernas
  filter och handlers klientens värden, också på routerns egna 400, 404 och 405 (routerns filter
  körs för dem). Ett filter som registreras före det ser anslutningens värden; det kan modulen
  inte kontrollera, så det står i Javadoc för `ForwardedHeaders`, `WebRequest` och
  `module-info.java` och i README. Ett test i `spola.security.web` visar båda ordningarna.
- **`Request.remoteAddress()` tas vid accept.** `Connection` läser `socket.getInetAddress()` och
  `getPort()` en gång, när anslutningen accepteras, och gör en `InetSocketAddress` av dem utan
  namnuppslag; samma värde gäller varje förfrågan på anslutningen och används också för
  loggraderna (som tidigare skrev `getRemoteSocketAddress()`, med samma text). Över TLS är det
  TCP-socketen under `SSLSocket`. Värdet är aldrig `null`: skulle en accepterad socket sakna
  adress kastar `Connection` och `HttpServer` stänger socketen och loggar det.
- **Tester.** Genom en riktig `HttpServer` med rå socket (exakta byte, obs-text) och `HttpClient`
  (behovets användningsfall, med `WhoamiApp.java` som README visar oförändrad), tabeller med 32
  giltiga och 34 ogiltiga `X-Forwarded-For`, 8 giltiga och 13 ogiltiga `X-Forwarded-Proto`, kedjan
  med filter före och efter, grupper och `withAttribute`, 200 samtidiga virtuella trådar,
  markörer i värdena som letas efter i 400-svar, routerns loggrader och undantagsmeddelanden, och
  `TrustedProxies` utan nätverk med adresser från `InetAddress.ofLiteral`.
