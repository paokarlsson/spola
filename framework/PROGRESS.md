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
- Källkontrollen omfattar även Java-kod utan filändelsen `.java` som source launchern kan
  köra: `#!`-skript (JEP 330) och varje annan fil som javac kan parsa. jshell-skript
  (`.jsh`) och `#!`-skript som nämner `java` men inte går att läsa, parsa eller attribuera
  rapporteras som `UNCHECKABLE_SOURCE` (fail-closed).
- Filkontrollen är fail-closed för zip: en fil som börjar som en zip men som `ZipFile` inte
  kan öppna (skadad, okänd komprimering) räknas som jar.
- Självtest mot 10 fixtures med exakt angivna förväntade överträdelser, plus kontroll att
  tillåtelselistan i koden stämmer med tabellen i `REGLER.md`, att varje fil under
  `fixtures/` används av sin fixture, och att huvudkontrollen bara undantar de listade
  fixture-katalogerna (inte hela `fixtures/`).
- Bygget tar ungefär 2 s.

## Återstår (enligt `uppdrag/PLAN.md`)
- 02 testkörare: guardrails självtest ska flyttas in i den nya köraren
  (i dag `SelfTest` + `Fixtures` i `spola.guardrails`, körs via `GuardrailsTool selftest`).
- 03–20 enligt planen.

## Kända problem och begränsningar
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
- En fil utan `.java`-ändelse och utan `#!`-rad som bara kompilerar med en äldre
  `--source` (t.ex. `record` som identifierare) parsas inte av javac 25 och hoppas över.
  Projektet ska vara Java 25 (REGLER §3).
- Ett `#!`-skript analyseras ensamt och utan class path. Ett skript som behöver andra
  källfiler eller en class path rapporteras som `UNCHECKABLE_SOURCE` (fail-closed, kan ge
  falsklarm). Det gäller också en `#!`-rad där `java` bara ingår i ett längre namn.
