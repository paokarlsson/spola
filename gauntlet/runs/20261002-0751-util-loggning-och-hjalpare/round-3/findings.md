# Round 3 — verdict: FAIL

## Upheld findings (the builder must address these)
- BRK-1 [MAJOR] framework/modules/util/src/spola/util/Log.java `emit` (~line 586-593) and `log(Level, Supplier<String>, Throwable)` (~line 605-609) — The round-2 BRK-1 fix lives only in `LineFormatter`/`LineHandler`, so it only takes effect after `Logging.configure`. Without configuration (JDK default JUL `ConsoleHandler`/`SimpleFormatter`, which the spec says is not touched as a side effect), and with any user-supplied `System.Logger` via `Log.of`, the trailing Throwable goes straight to `logger.log(level, msg, thrown)`. The backend's `printStackTrace` errors then escape from the logging call. The judge reproduced this independently against the current `framework/out/spola.util.jar` with `round-3/scratch-breaker/T.java`. Without configure it prints `ESCAPED default class java.lang.AssertionError` (toString throws) and `ESCAPED default class java.lang.StackOverflowError` (recursive `getMessage`). With configure (`T 1`) all five cases are logged with a marker. The spec states "Ett argument vars toString kastar ger inte undantag ut ur loggningen" as a property of `Log`, the public API. It does not depend on which backend is installed, and `Log.of(System.Logger)` is an explicit spec feature. Logging before `Logging.configure`, or never calling it (libraries, tests), is ordinary good-faith use, and the failure hits error paths. This is the same defect class the round-2 judge graded MAJOR, now on the default path, so it stays MAJOR. — fix_hint: In `Log.emit` and the supplier variant, wrap `logger.log(level, text, thrown)` in `catch (Throwable failure)`. Rethrow fatal VirtualMachineErrors other than SOE, using a shared helper (see MNT-1). Otherwise log once more without the throwable, with the text plus `thrown.getClass().getName()` and `MessageFormatter.marker("printStackTrace()", failure)` or similar. If that second call also throws, swallow it. Update Log's Javadoc so the guarantee explicitly covers the trailing Throwable regardless of backend. Add a test with an injected `System.Logger` whose `log(level, msg, thrown)` calls `thrown.toString()` (AssertionError case and recursive-getMessage SOE case). It must show that nothing escapes and that a record with the marker is logged.

## Rejected
- (none)

## Duplicates
- AUD-1 → MNT-2

## MINOR (optional for the builder)
- MNT-1 [MINOR] LineHandler.java:37 — The "rethrow fatal VMErrors except SOE" rule is written out in a second place next to `MessageFormatter.marker`. A shared `rethrowIfFatal(Throwable)` helper would avoid drift. This matters more now that BRK-1's fix needs the rule a third time.
- MNT-2 [MINOR] LineHandler.java:40 — The `RuntimeException` wrapper exists only to fit `reportError`'s signature and is nearly unreachable. A one-line comment would do.
- MNT-3 [MINOR] LineFormatter.java:78-87 — `text` is reassigned inside the catch block of the try-with-resources. A helper that returns either the trace or the fallback line would read more clearly.
- Carried over from round 2 (no action needed): AUD-1 (System.gc in the Lazy release test) and AUD-2 (tab-prefixed stack-trace lines).
