# Round 1 build: spola.util (05a)

## Changes
- `framework/Build.java`: added `spola.util` (`modules/util`, test entry `spola.util.UtilTests::suite`) to `MODULES`.
- `framework/modules/util/src/module-info.java`: `module spola.util { requires java.logging; exports spola.util; }`. No Spola `requires`.
- `framework/modules/util/src/spola/util/` (main code):
  - `Log`: `forClass` (`System.getLogger(type.getName())`), `of(System.Logger)`, `defer(Supplier<?>)` / `Log.Deferred` for lazy arguments, `trace/debug/info/warn/error` with `(String)`, `(String,Object)`, `(String,Object,Object)`, `(String,Object...)`, `(Supplier<String>)`, `(Supplier<String>,Throwable)`, plus `isXxxEnabled()`. Javadoc has the level table and the exact placeholder rules. Every method checks `isLoggable` first.
  - `MessageFormatter` (package-private): implements the placeholder rules: `{}` in order, leftover `{}` kept, surplus ignored, last unconsumed `Throwable` becomes the exception, `\{}` gives a literal, `\\{}` gives a backslash plus a placeholder, `null`, arrays via `deepToString`/`toString`, and a `[toString() threw X]` / `[supplier threw X]` marker without the exception message.
  - `Logging` (`configure(LoggingSettings)`, `reset()`), `LoggingSettings` (record: levels per prefix, output, zone; `defaults()`, `withLevel(s)`, `withOutput`, `withZone`; validates prefixes), `LineFormatter` and `LineHandler` (package-private: the one-line format and a synchronous handler that never closes the stream).
  - `Lazy<T>`: volatile plus `ReentrantLock` double-check. Exceptions and `null` are not memoized (null gives an NPE with a message), the supplier is cleared after success, and a recursive `get()` gives an ISE.
  - `Unchecked` plus `ThrowingSupplier/Function/Consumer/Runnable` and `UncheckedException`: wrapping without a sneaky throw (`wrap(Exception)` is public).
  - `ToString`: record-form builder with `secret` (`***`, `null` stays `null`, never calls the secret's `toString`).
- `framework/modules/util/test/spola/util/`: `UtilTests`, `RecordingLogger`, `LogTests`, `LoggingTests`, `LazyTests`, `UncheckedTests`, `ToStringTests` (68 tests).
- `framework/modules/util/README.md`: each helper next to its Lombok counterpart (`@Slf4j`, `@Getter(lazy = true)`, `@SneakyThrows`, `@ToString.Exclude`), Spring Boot `logging.level.*` next to `Logging`, how to read `logging.level.*` with `spola.config` and pass it in, and which Lombok annotations records replace.
- `framework/ARCHITECTURE.md`: new section "Util del 1" with the API and decisions (placeholder rules, laziness and `Log.defer` instead of treating `Supplier` arguments as lazy, how the console format is installed, line format and escaping, `Lazy`, `Unchecked` without a sneaky throw, `ToString`, and the 05a/05b split). Also added the README to the directory listing.
- `framework/PROGRESS.md`: 05a under "Klart", 05b under "Återstår" (split documented), and the util limitations under "Kända problem".

## Verification
`java Build.java` in `framework/`:
```
modules in build order: [spola.guardrails, spola.test, spola.di, spola.config, spola.util]
compiled spola.util (15 files)
...
spola.config: OK - 63 tests, 63 ok, 0 failed ...
spola.util: OK - 68 tests, 68 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 194 ms
exit guard:
BUILD OK in 15619 ms
```
(Guardrails runs in the same build and is green.)

Mutation check: I made `Log.log(...)` format before `isLoggable`. The "nothing is formatted ... when off" test then failed for all 5 levels. I restored the code and the build is green again (`BUILD OK in 15958 ms`).

Criterion checks:
- `grep -rnE "java\.lang\.(reflect|invoke)|StableValue|requires" framework/modules/util/src` gives only `requires java.logging;`.
- `git diff --stat`: only `ARCHITECTURE.md`, `Build.java` and `PROGRESS.md` changed. `Allowlist` is untouched.
- No new non-`.java`/`.md` files under `framework/`. The only such files are the existing guardrails fixtures.

Criterion to test mapping:
- 3 (placeholders): `LogTests > placeholders` covers order, too few, too many, a last Throwable logged as exception and absent from the text, a consumed Throwable, a Throwable that is not last, `\{}`/`\\{}`, null, arrays (primitive and nested), and a throwing `toString` (also nested in an array, a throwing deferred supplier, a self-referential deferred and a throwing message supplier).
- 4 (no work when off): `LogTests > no work when the level is off` is parameterized over all 5 levels and all 6 method forms. It uses counters on `toString` and on the suppliers, then switches the level on and checks the counts.
- 5 (mapping): `LogTests > levels` checks that each method logs at its `System.Logger.Level` and that `isXxxEnabled` is on at its own level and off one level up and at OFF. `LoggingTests` checks the JUL mapping.
- 6 (console format): `LoggingTests` covers line regex plus timestamp parse and range, a named platform thread, an unnamed virtual thread (`virtual-<id>`), newline escaping, stack trace lines including "Caused by", prefix levels (longest wins, `com.ex` does not cover `com.example`, `com.example` does not cover `com.examples`), a second configure (one handler, no doubled lines, the old prefix level gone, the old stream gets nothing more), reset restoring the JDK handler and preset levels, and the PrintStream charset plus a never-closed stream.
- 7 (Lazy): 1 000 virtual threads behind a start latch give one computation. An exception is not memoized, null gives an NPE that is not memoized, the supplier is released (WeakReference plus GC loop), and recursion gives an ISE.
- 8 (Unchecked): parameterized over 6 shapes (4 adapters plus `run`/`get`) with IOException, InterruptedException (flag set again), TimeoutException (cause), and RuntimeException/Error/UncheckedIOException passed through as the same instance.
- 9 (ToString): equals the record `toString` for String, int, double, boolean, char, null, List and nested record fields, and for an empty record. The secret is absent, length is not leaked, the secret's `toString` is not called, and null shows as null.

## Addressed
Round 1: no findings yet.

## Rebuttals
None.

## Known gaps
- `Logging.configure` removes all root handlers, foreign ones too (they are not closed). Logger levels set by others, e.g. via `logging.properties`, take precedence over a shorter prefix for that subtree. A record logged during `configure` may be lost. All of this is documented in Javadoc and PROGRESS.
- The varargs `Log` methods get their array allocated at the call site even when the level is off. This is unavoidable in Java. The 1- and 2-argument forms allocate nothing.
- Escaping makes a literal `\n` in a message indistinguishable from an escaped newline.
- The Lazy "supplier released" test relies on `System.gc()` clearing a WeakReference within 100 tries. It passed reliably locally but is GC-dependent.
- The "nothing configured by loading the classes" test cannot prove first-load behavior, because the classes may already be loaded by earlier tests. It checks that `Log` use and `LoggingSettings.defaults()` leave the root logger untouched.
