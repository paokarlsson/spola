# Grundregler för Spola

Dessa regler gäller för varje uppdrag, varje modul och varje rad kod i `framework/`.
De får aldrig ändras, försvagas eller kringgås av ett uppdrag.

## 1. Inga tredjepartsberoenden

- Endast moduler som ingår i JDK 25 får användas (`java.*`, `javax.*`, `jdk.*` samt
  `com.sun.*`-paket som exporteras av JDK-moduler, t.ex. `jdk.compiler` och `jdk.httpserver`).
- Ingen tredjepartskod får finnas i repot: inga beroenden, plugins, byggverktyg,
  testverktyg, vendorade jar-filer eller inkopierad källkod.
- Ingen Maven, Gradle eller liknande. Projektet byggs med `java Build.java` i `framework/`.

## 2. Annoteringar

Endast dessa JDK-annoteringar är tillåtna, överallt (ramverk, tester, exempelapp):

| Annotering | Retention |
|---|---|
| `java.lang.Override` | SOURCE |
| `java.lang.SuppressWarnings` | SOURCE |
| `java.io.Serial` | SOURCE |
| `java.lang.FunctionalInterface` | RUNTIME |
| `java.lang.SafeVarargs` | RUNTIME |
| `java.lang.Deprecated` | RUNTIME |

Förbjudet:
- alla andra annoteringar
- egna annoteringstyper (`@interface`)
- att läsa annoteringar via reflektion för att styra logik

## 3. Java 25

- JPMS med en `module-info.java` per modul.
- Använd språket fullt ut: records, sealed interfaces, pattern matching, virtual threads,
  scoped values, flexible constructor bodies, module import declarations, compact source files.
- Preview-funktioner får inte användas i publikt API.

## 4. Designprinciper

- Explicit framför magiskt: all wiring sker i vanlig Java-kod som går att följa med
  "Go to definition". Ingen classpath-scanning, inga namnkonventioner.
- Ingen reflektion i runtime där det går att undvika. Föredra lambdas, method references,
  records och sealed hierarkier.
- Kompileringsfel framför runtime-fel.
- Varje modul ska kunna användas fristående.
- Säkerhetskritisk kod (kryptografi, protokoll, parsning) bygger på JDK:ns primitiver och
  testas mot officiella testvektorer och specifikationer.

## 5. Dokumentation

- `framework/PROGRESS.md`: vad som är klart, vad som återstår, kända problem.
  Uppdateras i slutet av varje uppdrag.
- `framework/ARCHITECTURE.md`: designbeslut och motiveringar. Uppdateras när beslut fattas.
- Publikt API dokumenteras med Javadoc.
