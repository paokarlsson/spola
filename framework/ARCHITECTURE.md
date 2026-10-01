# Spola – arkitektur och designbeslut

Grundreglerna i `REGLER.md` går före allt i detta dokument.

## Katalogstruktur

```
framework/
  REGLER.md                 grundreglerna (lag)
  Build.java                bygget, körs med `java Build.java` (source launcher)
  modules/<modul>/src/      källkod för en modul, med module-info.java överst
  modules/guardrails/fixtures/   medvetet felaktiga exempel för guardrails självtest
  out/                      byggresultat (ignoreras av git)
    classes/<modulnamn>/    kompilerade klasser per modul
    <modulnamn>.jar         en jar per modul
```

## Bygget (`Build.java`)

- **Compact source file** (Java 25) med `import module java.compiler` och
  `import module jdk.compiler`. Körs utan förkompilering och utan verktyg från tredje part.
- **Modullistan är explicit** i konstanten `MODULES` (`ModuleDir(namn, katalog)`). Ingen
  katalogscanning hittar moduler. En ny modul läggs till där.
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
- Inga inkrementella byggen: `out/` raderas och byggs om varje gång.
- Exitkod 0 = grönt, 1 = rött.

## Modulen `spola.guardrails`

Publikt API (paket `spola.guardrails`):

| Typ | Roll |
|---|---|
| `Guardrails.check(Project, PrintStream)` | kör alla kontroller, returnerar sorterade `Violation` |
| `Project` | rot, output-katalog, undantagna kataloger, projektmoduler (namn → källkatalog) |
| `Violation` | fil, rad (0 om ej tillämpligt), `Rule`, subjekt, plats i bytekoden |
| `Rule` | regel med avsnitt i `REGLER.md` och beskrivning |
| `GuardrailsTool` | `ToolProvider` + `main`: `check …` och `selftest …` |

Kontroller (paketprivata klasser):

| Kontroll | Hur | Regler |
|---|---|---|
| `SourceCheck` | `JavacTask.parse()` + `analyze()` (attribuering), `TreePathScanner`, `Trees.getElement` | annotering utanför tillåtelselistan (fullt kvalificerat namn efter attribuering, så en egen `Override` skiljs från `java.lang.Override`), annoteringar som inte går att lösa upp, `@interface`, import från annat än JDK/projekt, läsning av annoteringar via reflektion: anrop och method references till `java.base`-metoder vars returtyp (efter erasure, även arrayer) är `Annotation` eller `AnnotatedType`, samt `isAnnotationPresent` och `Method.getDefaultValue` |
| `BytecodeCheck` | `java.lang.classfile` | `Runtime(In)Visible{,Parameter,Type}Annotations` på klass, fält, metod, record-komponent och i `Code` (typannoteringar i metodkroppar), `AnnotationDefault`, nästlade annoteringar, klasser med `ACC_ANNOTATION` |
| `DependencyCheck` | `module-info.class` → `ModuleAttribute` | `requires` (även `static`/`transitive`) som varken är JDK-modul eller projektmodul |
| `FileCheck` | filträd inklusive länkar; innehållet i zip-filer (`java.util.zip`) | jar-arkiv utanför `out/`: efter namn (`.jar`, `.jmod`, `.war`, `.ear`, skiftlägesokänsligt) **och** efter innehåll (en zip-fil med vilket namn som helst, även med skript eller jmod-huvud framför, som innehåller en `.class`-post, en post som börjar med class-filens magiska tal, `META-INF/MANIFEST.MF`, ett arkiv efter namn eller en nästlad zip med något av detta); byggfiler från Maven, Gradle, Ant, Ivy, Bazel, Buck, Pants, Mill, sbt och Make (`pom.xml`, `build.gradle(.kts)`, `gradlew`, `mvnw`, `libs.versions.toml`, `build.xml`, `BUILD`, `BUCK`, `pants.toml`, `build.mill`, `Makefile`, …) samt alla filer under `.mvn/`, `.gradle/`, `.mill/`, `.bsp/`; varje symbolisk länk |

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
  (`Fixture.uses`: `.java` för `SOURCE`/`BYTECODE`, `src/**.java` för `DEPENDENCIES`,
  `tree.txt` för `FILES`), och varje `.java` i en fixture källkontrolleras med exakt angivna
  förväntningar. `out/` undantas från källkontrollen och är den enda plats där jar-arkiv får
  finnas.
- **Jar-arkiv känns igen på innehåll, inte bara namn.** En omdöpt jar (`.zip`, utan
  filändelse, med ett skript framför) är lika mycket vendorad kod. Zip-filer utan Java-kod
  (testdata) är tillåtna. `ZipFile` hittar centralkatalogen från filens slut, så data framför
  arkivet döljer det inte; nästlade arkiv läses med `ZipInputStream` upp till 8 nivåer, och
  djupare nästling räknas som Java-kod.
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
  felmeddelandet pekar på `ALLOWLIST_ROW` i `SelfTest.java`.
- **Utskrift i ASCII:** regelavsnitt skrivs som `REGLER.md 2. Annoteringar` (inte `§`) så att
  felen går att läsa i terminaler utan UTF-8.

Felformat: `fil[:rad]: REGEL (REGLER.md avsnitt): subjekt [at plats] - beskrivning`, t.ex.

```
modules/x/src/spola/x/Marker.java:3: ANNOTATION_DECLARATION (REGLER.md 2. Annoteringar): @interface spola.x.Marker - custom annotation types (@interface) are forbidden
```

## Självtest och fixtures

- `Fixtures.ALL` listar explicit varje fixture: namn (katalog under
  `modules/guardrails/fixtures/`), typ och **exakt** vilka överträdelser den ska ge (fil, rad,
  regel, subjekt, plats). Självtestet jämför som multimängder: både saknade och oväntade
  överträdelser ger rött bygge. En fixture utan förväntade överträdelser är också ett fel,
  liksom en fixture-katalog som inte finns i listan, en fil direkt i `fixtures/` och en fil
  i en fixture-katalog som fixturen inte använder.
- Typer: `SOURCE` (källkontroll direkt på filerna), `BYTECODE` (källkontroll, sedan
  kompilering till en temporär katalog och bytekodkontroll), `DEPENDENCIES` (källkontroll av
  `src/`, sedan kompilering som moduler och beroende- och bytekodkontroll), `FILES` (varje
  rad i `tree.txt` skapas i en temporär katalog, filkontroll). `tree.txt` kan beskriva tomma
  filer, kataloger (`dir/`), symboliska länkar (`sökväg -> mål`) och genererat innehåll
  (`sökväg = zip(post, post = class, ...)`, `jmod(...)`, `script+zip(...)`, `class`), så att
  inga riktiga arkiv eller class-filer checkas in.
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
`foreign-requires`, `foreign-import`, `reflective-annotation-read`, `foreign-jar`,
`foreign-build-file`, `symbolic-link`.

Lägga till en fixture: skapa katalogen, lägg till en `Fixture` i `Fixtures.ALL` med de exakta
förväntade överträdelserna och kör `java Build.java`.
