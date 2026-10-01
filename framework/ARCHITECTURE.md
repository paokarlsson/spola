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
  Guardrails kontrollerar dessutom den kompilerade `module-info.class`.
- **Kompilering:** en javac-körning per modul via `javax.tools.ToolProvider`, i
  beroendeordning, med `--module-path out/classes` (redan byggda moduler) och
  `-d out/classes/<modul>`. Flaggor: `--release 25 -proc:none -Xlint:all,-deprecation
  -Xdoclint:all/protected -Werror`.
  - `-Werror` + lint: varningar är fel (kompileringsfel framför runtime-fel).
  - `-Xdoclint:all/protected` maskinverifierar REGLER §5 (Javadoc på publikt API).
  - `-deprecation` är avstängd så att `@Deprecated` på ett API aldrig gör bygget rött.
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
| `SourceCheck` | `JavacTask.parse()` + `analyze()` (attribuering), `TreePathScanner`, `Trees.getElement` | annotering utanför tillåtelselistan (fullt kvalificerat namn efter attribuering, så en egen `Override` skiljs från `java.lang.Override`), annoteringar som inte går att lösa upp, `@interface`, import från annat än JDK/projekt, läsning av annoteringar via reflektion (`AnnotatedElement.getAnnotation` m.fl., även method references) |
| `BytecodeCheck` | `java.lang.classfile` | `Runtime(In)Visible{,Parameter,Type}Annotations` på klass, fält, metod, record-komponent och i `Code` (typannoteringar i metodkroppar), `AnnotationDefault`, nästlade annoteringar, klasser med `ACC_ANNOTATION` |
| `DependencyCheck` | `module-info.class` → `ModuleAttribute` | `requires` (även `static`/`transitive`) som varken är JDK-modul eller projektmodul |
| `FileCheck` | filträd | `.jar` (skiftlägesokänsligt) utanför `out/`; byggfiler från Maven, Gradle, Ant, Ivy, Bazel och sbt (`pom.xml`, `build.gradle(.kts)`, `settings.gradle(.kts)`, `gradlew`, `mvnw`, `build.xml`, …) |

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
  `Build.java`) analyseras som en grupp i den namnlösa modulen. En `module-info.java`
  utanför de listade modulerna är ett fel med tydligt meddelande ("lägg till modulen i
  `MODULES`").
- **Vad som undantas:** fixtures-katalogen undantas från käll-, bytekod- och
  beroendekontrollen men *inte* från filkontrollen. `out/` undantas från källkontrollen och
  är den enda plats där `.jar` får finnas.
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
  liksom en fixture-katalog som inte finns i listan.
- Typer: `SOURCE` (källkontroll direkt på filerna), `BYTECODE` (kompileras till en
  temporär katalog, bytekodkontroll), `DEPENDENCIES` (`src/` kompileras som moduler till en
  temporär katalog, beroendekontroll), `FILES` (varje rad i `tree.txt` skapas som tom fil i
  en temporär katalog, filkontroll).
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
`foreign-build-file`.

Lägga till en fixture: skapa katalogen, lägg till en `Fixture` i `Fixtures.ALL` med de exakta
förväntade överträdelserna och kör `java Build.java`.
