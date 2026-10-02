# Round 2: build

## Changes
- `framework/modules/util/src/spola/util/LineFormatter.java`: the stack trace (`printStackTrace`, so causes and suppressed exceptions are included) is split on `\r\n|\r|\n`. Every resulting line is written with a leading tab, and control characters other than tab, plus U+2028/U+2029, are escaped with the same `escape` used for the message. A line that starts at column 0 is therefore always a record. The `printStackTrace()`-threw marker goes through the same path.
- `framework/modules/util/test/spola/util/LoggingTests.java`: new test "line breaks in exception messages cannot forge a record". It uses the judge's case: a `NumberFormatException` from `Integer.parseInt("42\n<fake record>")`, plus a cause with `\r` and a suppressed exception with `\r\n` and U+2028. It checks that every continuation line starts with a tab, that no continuation line matches the record pattern, and that U+2028 is escaped. The existing stack-trace test was updated for the tab prefix (`\tjava.io.IOException…`, `\t\tat`, `\tCaused by:`).
- `framework/modules/util/src/spola/util/Logging.java`: the Javadoc line format and the anti-forging claim now describe exactly what is guaranteed and how. MNT-3: `originalHandlers`/`originalRootLevel` were replaced with a private `record RootSnapshot(List<Handler> handlers, Level level)` that is set and cleared as a unit.
- `framework/modules/util/src/spola/util/Log.java`: MNT-1/MNT-2. Added a comment at the start of the level blocks: the blocks must be edited together, and the comment explains why the 1- and 2-argument methods check `isLoggable` inline.
- `framework/ARCHITECTURE.md` ("Radformat"): describes the tab prefix and why it was chosen over rewriting `printStackTrace`.
- `framework/modules/util/README.md`: example output with tabs, plus a short note about anti-forging.
- `framework/PROGRESS.md`: test count is now 69, the new test is listed, and the tab prefix is noted under known limitations (tools that expect plain JDK stack traces see an extra tab).

## Verification
`java Build.java` in `framework/`:
```
modules in build order: [spola.guardrails, spola.test, spola.di, spola.config, spola.util]
...
    ok      line breaks in exception messages cannot forge a record (2 ms)
...
spola.util: OK - 69 tests, 69 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 206 ms
BUILD OK in 15837 ms
exit=0
```
The judge's reproduction run against `out/spola.util.jar` (`cat -A`; `^I` = tab):
```
2026-10-02T08:14:39.777+02:00 WARN  [main] R : bad id$
^Ijava.lang.NumberFormatException: For input string: "x$
^I2026-10-02T08:00:00.000+02:00 ERROR [main] com.example.Auth : admin login OK"$
^I^Iat java.base/java.lang.NumberFormatException.forInputString(NumberFormatException.java:67)$
```
The fake line now starts with a tab and cannot be confused with a record.

## Addressed
- BRK-1 [MAJOR]: fixed with option (a), using the tab prefix on every continuation line plus escaping of other control characters. The Javadoc, ARCHITECTURE and README now describe the actual guarantee. Added a test covering the exception message, the cause and the suppressed exception.
- MNT-1 [MINOR]: comment added saying the five level blocks must be edited together.
- MNT-2 [MINOR]: comment at the code explaining the inline `isLoggable` (no array allocation) versus `log(...)`.
- MNT-3 [MINOR]: private record `RootSnapshot` replaces the two fields.
- AUD-1 [MINOR]: not changed. It is already documented and has been stable in every build. Making it more robust without `System.gc()` would require reflection on the private field, which the rules forbid in the main code and which would add little in the test.

## Rebuttals
- (none)

## Known gaps
- Continuation lines in the stack trace now have an extra tab compared with the JDK's own format. This is documented in PROGRESS.md under known limitations.
- AUD-1: the supplier-release test still depends on `System.gc()` clearing a `WeakReference` (theoretical flakiness).
