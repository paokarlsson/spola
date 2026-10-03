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
  modules/codegen/README.md arbetsflödet för genererade builders/with-metoder, bredvid Lombok
  modules/demo/README.md    integrationsprogrammet: körning, Spring Boot-jämförelse, friktionsrapport
  modules/json/README.md    exempel för spola.json och spola.json.codec bredvid Jackson, avvikelser från Jacksons standard
  modules/http/README.md    exempel för spola.http bredvid Spring Boot/Tomcat, gränserna mot Spring Boots egenskaper, HTTPS och `server.ssl.*`
  modules/web/README.md     exempel för spola.web bredvid Spring MVC, avvikelser från Spring MVC
  out/                      byggresultat (ignoreras av git)
    classes/<modulnamn>/    kompilerade klasser per modul
    <modulnamn>.jar         en jar per modul (utan tester)
    test-classes/<modulnamn>/   modulen kompilerad igen tillsammans med sina tester
    codegen/spola.codegen/  generatorn, kompilerad av `java Build.java codegen`
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
  första argument än `codegen` är ett fel i stället för att ignoreras.
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
  Testerna fångar loggen via en paketprivat `RouterBuilder.log(System.Logger)`.
- **Inga ändringar i `spola.http`.** Allt uppdraget behöver finns i dess publika API.
- **Tester** går genom en riktig `HttpServer` (127.0.0.1, port 0) med `HttpClient` eller en
  rå socket (för sökvägar som `HttpClient` inte skickar oförändrade), eftersom
  `spola.http.Request` bara skapas av servern.
