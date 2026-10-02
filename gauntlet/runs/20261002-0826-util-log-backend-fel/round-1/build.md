# Round 1 — build

## Changes
- `framework/modules/util/src/spola/util/Failures.java` (new, package-private): `rethrowIfFatal(Throwable)`. It is the only place the rule "rethrow `VirtualMachineError` except `StackOverflowError`" is written.
- `Log.java`: every call to the backend now goes through the new private `deliver(level, text, thrown)`, which `emit` and the supplier variant both use.
  - If `log(level, text, thrown)` throws, `deliver` logs once more without the exception: `text + " [log(<thrown class>) threw <failure class>]"`, built with `MessageFormatter.marker`. If that second call also throws, the error is swallowed.
  - If `log(level, text)` (no exception) throws, it is swallowed with no retry.
  - Fatal errors are rethrown through `Failures` in every path. At most two backend calls, no recursion.
  - The new class Javadoc section "Failures" describes the guarantee, the fallback, and what is outside it: `isLoggable` propagates, supplier and `defer` behaviour is unchanged and documented, and blocking or slow backends are not handled.
- `MessageFormatter.marker`: uses `Failures.rethrowIfFatal` instead of an inline check.
- `LineHandler.publish`: uses `Failures.rethrowIfFatal`, plus the MNT-2 comment on the `RuntimeException` wrapping (it exists only to fit `reportError`'s signature).
- `LineFormatter`: MNT-3. `stackTrace` calls a new helper `printed(thrown)` that returns either the printed trace or the fallback line, so nothing is reassigned in a catch block. The helper calls `Failures.rethrowIfFatal`. Output is byte-for-byte the same, and the existing tests at lines 162-185 of `LoggingTests` are unchanged and still pass.
- `framework/modules/util/test/spola/util/LogFailureTests.java` (new, 9 tests), registered in `UtilTests`.
- `PROGRESS.md`: the guarantee and the `isLoggable` boundary are added under 05a, the test count goes to 79, and the `isLoggable` limitation is noted under known problems. The old finding was never listed there, so there was nothing to remove.
- `ARCHITECTURE.md`, section "Util del 1": three new decisions (the guarantee, its boundary, one fatal-error rule) and `Failures` added to the package-private list.

## Verification
`java Build.java` in `framework/`:
```
spola.guardrails: OK - 17 tests, 17 ok, 0 failed
spola.test: OK - 77 tests, 77 ok, 0 failed
spola.di: OK - 48 tests, 48 ok, 0 failed
spola.config: OK - 63 tests, 63 ok, 0 failed
spola.util: OK - 79 tests, 79 ok, 0 failed   (70 existing + 9 new)
BUILD OK in 16508 ms      (exit 0)
```
New tests in the output:
```
  log failures
    a backend that prints a bad trailing exception: nothing escapes, the text and a marker are logged
      ok [1] toString throws AssertionError / ok [2] recursive getMessage gives StackOverflowError
    ok a backend that prints a good trailing exception gets it once, unchanged
    a backend whose log always throws: nothing escapes, at most two calls per message
      ok [1] IllegalStateException / ok [2] AssertionError
    ok an OutOfMemoryError from the backend is rethrown, on the first call or the retry
    ok isLoggable that throws propagates (outside the guarantee)
    the JDK default backend (ConsoleHandler + SimpleFormatter, no Logging.configure): a bad trailing exception does not escape
      ok [1] toString throws AssertionError / ok [2] recursive getMessage gives StackOverflowError
```
Mutation check: I temporarily made `deliver` call the backend directly, as the old code did, then restored the file. The result was `spola.util: FAILED - 79 tests, 72 ok, 7 failed`, and every new guarantee test failed. So the tests catch the original defect.

Criterion 6: `grep -rn VirtualMachineError modules/util/src` finds only `Failures.java:6` (Javadoc), `Failures.java:27` (the rule) and `Log.java:58,79` (Javadoc).
`grep java.lang.reflect|java.lang.invoke|@interface` in `modules/util/src` finds nothing. Guardrails pass.
The redirected default-backend output does not leak: the build log contains 0 `WARNING: x` / `WARNING: after` lines.

## Acceptance criteria mapping
- 2: `PrintingLogger` calls `thrown.toString()` and `printStackTrace(PrintWriter)`. Cases (a) and (b) are tested through `warn("x", e)`, `error("order {} failed", 42, e)` and `info(() -> "lazy", e)`. The test asserts the exact fallback text with both class names and that the retry is logged without the exception.
- 3: `ThrowingLogger` is run with `RuntimeException` and with `AssertionError`. It checks exact counts: 1 for `info("x")` and `error("x {}", arg)`, 2 for `error("x", e)`. It then checks all 7 overload shapes on all 5 levels.
- 4: `OutOfMemoryError("test")` from the first call is rethrown, with no retry (4 calls for 4 logging calls). An OOM on the retry (the first call threw a `RuntimeException`) is also rethrown.
- 5: the logger comes from `System.getLogger(name)` (the JDK default `LoggerFinder`). It uses a fresh JUL `ConsoleHandler` (which uses `SimpleFormatter` by default), created while `System.err` points at a `ByteArrayOutputStream`, and `useParentHandlers=false`.
  - Afterwards the test restores `System.err`, the logger's level and `useParentHandlers`, and removes the handler.
  - The test asserts that `WARNING: x [log(...) threw ...]` was written.
- 7: MNT-2 and MNT-3 are done as described under Changes.
- 8: covered by the "Failures" section in `Log`'s class Javadoc.
- 11: the guarantee and the `isLoggable` boundary are under "Util del 1" in both documents.

## Addressed
- BRK-1 (run 0751, MAJOR) → `Log.deliver`, with the fallback and swallowing described above. Tests cover an injected backend and the JDK default backend.
- MNT-1 → `Failures.rethrowIfFatal`, used by `Log`, `MessageFormatter`, `LineFormatter` and `LineHandler`.
- MNT-2 → comment in `LineHandler.publish`.
- MNT-3 → the `LineFormatter.printed` helper.

## Rebuttals
- None.

## Known gaps
- Criterion 5 does not use the root logger's existing `ConsoleHandler`. That handler bound the real `System.err` when it was created, so redirecting `System.err` afterwards would not capture its output; the test would write to the real stderr and could not check the output. The test therefore builds an identical JDK handler chain on its own logger. criterion 5 explicitly allows "equivalent isolation".
- A failure that `Log` swallows is not reported anywhere. This is by design and documented.
- A backend's `isLoggable` that throws still propagates. This is out of scope and documented.
