# Spola – läget

## Klart

### Uppdrag 01: bygge och guardrails
- `Build.java`: `java Build.java` i `framework/` läser den explicita modullistan, ordnar
  modulerna efter `requires` i `module-info.java`, kompilerar med `javax.tools`
  (`-Xlint:all,-deprecation -Xdoclint:all/protected -Werror`), packar `out/<modul>.jar` med
  JDK:ns jar-verktyg och kör guardrails från den byggda jar-filen. Exitkod 0/1.
- Modulen `spola.guardrails` (`modules/guardrails/`) med fyra kontroller i varje bygge:
  källkod (Compiler Tree API med attribuering), bytekod (Class-File API inklusive
  parameter- och typannoteringar), beroenden (`module-info.class`), filer (jar-filer
  utanför `out/`, byggfiler från Maven/Gradle m.fl.).
- Utöver uppdragets tre kontroller: importer från annat än JDK/projekt och läsning av
  annoteringar via reflektion (REGLER §1 och §2).
- Självtest mot 8 fixtures med exakt angivna förväntade överträdelser, plus kontroll att
  tillåtelselistan i koden stämmer med tabellen i `REGLER.md`.
- Bygget tar ungefär 1,5 s.

## Återstår (enligt `uppdrag/PLAN.md`)
- 02 testkörare: guardrails självtest ska flyttas in i den nya köraren
  (i dag `SelfTest` + `Fixtures` i `spola.guardrails`, körs via `GuardrailsTool selftest`).
- 03–20 enligt planen.

## Kända problem och begränsningar
- Filkontrollen och källkontrollen täcker `framework/`, inte hela repot (uppdraget anger
  `framework/`). Repot i övrigt innehåller i dag inga jar- eller byggfiler.
- Reflektionskontrollen hittar direkta anrop och method references till
  `AnnotatedElement`-metoder. Indirekta anrop (t.ex. via `MethodHandles.lookup()` med
  metodnamnet som sträng) upptäcks inte.
- `--should-stop=ifError=FLOW` är en dold (men stabil sedan JDK 9) javac-flagga.
- Bytekodkontrollen läser `.class`-filer i `framework/` (i praktiken `out/classes`), inte
  innehållet i jar-filerna, som byggs från samma klasser.
