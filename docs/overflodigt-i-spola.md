# Vad i Spola är överflödigt med Java 25?

En genomgång av modulerna, mot vad som faktiskt används och vad JDK 25 redan ger. Ordnat efter
hur överflödigt det är. Skriven 2026-10-26.

## Mest överflödigt

**1. `spola.di` (cirka 1 600 rader)**
Spolas egen stil säger "wiring i vanlig Java, metodanrop som går att följa med Go to
definition". Det gör ren konstruktorinjektion i `main` redan, utan container. Ingen
ramverksmodul har `requires spola.di`. Den enda som använder den är `demo`
(`InventoryApp.java:112`). Det containern tillför är:

- Grafvalidering i förväg (saknade beroenden, cykler). Med handskriven wiring fångar
  kompilatorn saknade beroenden, och cykler går inte att skriva med `final`-fält.
- Ordning när saker stängs. Det ger try-with-resources gratis i omvänd ordning.
- Scopet `CALL` via `ScopedValue`. Det är egentligen bara `ScopedValue.where(...).run(...)`.

Det är modulen som står längst från "efter behov". Den byggdes för att Spring har en container,
inte för att Java behöver en.

**2. `ToString` i `spola.util`**
Records har redan `toString`. `ToString` behövs bara för vanliga klasser, och den enda
användningen är `demo/AuditSettings.java:26`, för att dölja en hemlighet. Ett record som skriver
över `toString()` med en rad gör samma sak.

**3. `Unchecked` och `Throwing*` (fyra gränssnitt)**
Det är bekvämt i streams, men ingen kod utanför `util` använder dem. JDK:n har
`UncheckedIOException`, och ofta är en explicit `try`/`catch` i en hjälpmetod tydligare. Det är
litet och ofarligt, men det ersätter en Lombok-funktion som bara finns för att Lombok har den.

## Överflödigt snart, men motiverat i Java 25

- **`Lazy`.** JDK:ns motsvarighet `StableValue` (JEP 502) är bara preview i 25. När den blir
  final är `Lazy` onödig.
- **`spola.codegen` (withers och builders).** Derived record creation (JEP 468,
  `p with { age = 37; }`) finns inte i 25. När det kommer försvinner halva modulen, alltså
  `withX` och `toBuilder`. Builders med namngivna fält är tills vidare motiverade för records
  med många komponenter.

## Diskutabelt

- **`spola.http` (cirka 4 800 rader).** `REGLER.md` §1 tillåter uttryckligen `jdk.httpserver`,
  som har både `HttpServer` och `HttpsServer` och tar en executor för virtuella trådar. Spola
  valde bort den. Valet går att försvara: `com.sun.net.httpserver` saknar strikt parsning, skydd
  mot request smuggling, gränser per förfrågan och vettiga tidsgränser (de styrs med
  systemegenskaper), och HTTP är data utifrån där säkerheten väger tungt. Men det är det
  enskilt största stället där JDK:n hade gett ett fungerande "good enough" gratis.
- **`Log`.** `System.Logger` har redan `log(Level, Supplier)` och platshållare i formen `{0}`.
  `Log` tillför SLF4J-stilens `{}` och `isDebugEnabled()`. Det är ergonomi, inte en lucka.

## Inte överflödigt

`json`, `web`, `security`, `security.web`, `validation` och `config` täcker verkliga luckor i
JDK:n. `client` är tunn men tillför JSON, felmappning och tidsgränser ovanpå `java.net.http`.
`test` krävs av §1, eftersom JUnit inte får användas. `guardrails` är riggens egen modul.

## Bedömning

Den tydligaste kandidaten är `spola.di`. Ska Spola vara konsekvent med "wiring i vanlig Java"
kunde katalogen rekommendera manuell wiring och behålla containern bara om ett behov kommer från
en app. `ToString` och `Unchecked` är små och kostar lite att ha kvar, men de skulle knappast ha
klarat behovsprövningen i `framework/MONSTER.md` om de kommit in som behov i dag.
