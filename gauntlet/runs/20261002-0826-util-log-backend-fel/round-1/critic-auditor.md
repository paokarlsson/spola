VERDICT: PASS
CRITERIA:
- 1. `java Build.java` exits 0: MET. I ran it: `spola.util: OK - 79 tests, 79 ok, 0 failed`, `BUILD OK`, exit 0.
- 2. Injected `System.Logger` with a bad trailing exception (toString throws `AssertionError`, recursive `getMessage` gives `StackOverflowError`): MET. `PrintingLogger` in `LogFailureTests.java` calls `thrown.toString()` and `printStackTrace`. The test checks that nothing escapes `warn("x", e)`, that the fallback text has both class names, and that the retry carries no exception. It also covers `error` with an argument and the lazy variant.
- 3. Backend `log` always throws (`RuntimeException`, `AssertionError`): MET. `ThrowingLogger` counts backend calls. `info("x")` and `error("x {}", arg)` make 1 call, and `error("x", e)` makes 2. All overload shapes on all levels are covered.
- 4. `OutOfMemoryError` from the backend is rethrown: MET. The test covers the first call (4 logging calls, 4 backend calls, no retry) and an OOM on the retry.
- 5. JDK default backend, no `Logging.configure`: MET. The test builds a fresh `ConsoleHandler` with `System.err` redirected to a buffer. The `finally` block restores `System.err`, the logger level, `useParentHandlers` and the handler. Both bad cases are covered. Only the logger is replaced; it is an equivalent setup rather than the root logger's own handler, which criterion 5 allows ("or equivalent isolation").
- 6. Fatal-error rule in exactly one place: MET. `grep VirtualMachineError modules/util/src` finds only `Failures.java:6` (Javadoc), `Failures.java:27` (the rule) and `Log.java:58,79` (Javadoc). `Log`, `MessageFormatter`, `LineFormatter` and `LineHandler` all call `Failures.rethrowIfFatal`.
- 7. MNT-2 and MNT-3: MET. `LineHandler.publish` has the comment explaining the `RuntimeException` wrapping is only for `reportError`'s signature. `LineFormatter.stackTrace` now calls `printed()`, which returns either the trace or the fallback line, and `text` is not reassigned in a catch block.
- 8. `Log` Javadoc covers the guarantee and the `isLoggable` exception: MET. The new "Failures" section covers both, plus the fallback and the supplier and blocking-backend boundaries.
- 9. Earlier criteria and tests still hold: MET. The 70 existing tests are still there (79 in total, 9 new). The diff shows no test removed or weakened; I did not diff the test files line by line.
- 10. Ground rules and guardrails: MET. The diff shows no annotations beyond the allow-list, no new dependencies, no reflection or `java.lang.invoke` in main code and no resource files. The guardrails suite passed in the build.
- 11. `PROGRESS.md` and `ARCHITECTURE.md` updated: MET. Both state the guarantee and the `isLoggable` boundary under "Util del 1". `ARCHITECTURE.md` has three new decisions. The old finding was never listed as a known problem, so nothing needed removing.

FINDINGS:
- id: AUD-1
  severity: MINOR
  where: `Log.java` class Javadoc, "Outside the guarantee" supplier bullet
  problem: The spec says a throwing supplier "behaves as today", and the Javadoc documents that. It lists the bullet under "Outside the guarantee" while describing the call as not throwing, which reads slightly contradictory.
  evidence: The Javadoc says the supplier case "does not make the call throw either". The code catches and writes a marker, as before.
  fix_hint: Move the supplier note out of the "outside the guarantee" list, or reword it as "handled as before".
- id: AUD-2
  severity: MINOR
  where: spec.md, Assumptions
  problem: spec-ambiguity. The mission mentions "three MINOR findings" but lists only MNT-2 and MNT-3.
  evidence: The builder also addressed MNT-1 (the shared helper), which the spec requires anyway.
  fix_hint: None needed.
