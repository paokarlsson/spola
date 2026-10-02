# Spec: Finish 05a — keep backend logging errors from escaping `spola.util` `Log`

## Task
Fix the remaining MAJOR finding from run `20261002-0751-util-loggning-och-hjalpare` (STALLED)
plus the MINOR findings MNT-2 and MNT-3, and lock the boundary of the guarantee. When this
passes, mission 05a is delivered. Full detail: `mission.md` in this directory (it is authoritative
for requirements; this spec restates them).

## Ground rules
Everything in `framework/REGLER.md` applies. A violation is a BLOCKER.

## Work product
`framework/` (scope: `Log`, `LineHandler`, `LineFormatter`, `MessageFormatter`, a new
package-private helper for fatal errors, tests, `PROGRESS.md`, `ARCHITECTURE.md`).

## Verification command
`java Build.java` in `framework/` — must exit 0.

## Requirements (from mission.md)
- **Guarantee:** a call to any logging method in `Log` (all levels, with and without
  placeholders, with and without a trailing `Throwable`, lazy variants) never throws because of
  (1) the arguments' or the trailing exception's `toString`/`getMessage`/`getLocalizedMessage`/
  `printStackTrace`/`getCause` etc., or (2) the backend's `System.Logger.log(...)` throwing for
  any reason — except fatal `VirtualMachineError`s that are not `StackOverflowError` (e.g.
  `OutOfMemoryError`), which are rethrown. Holds independent of backend: with and without
  `Logging.configure`, and with a custom `System.Logger` via `Log.of`.
- **Outside the guarantee (documented):** if the backend's `isLoggable(level)` throws, it
  propagates. A user-supplied `Supplier<String>`/`Log.defer` that throws behaves as today
  (documented in Javadoc; not changed unless required by the guarantee).
- **Fallback when backend `log(level, text, thrown)` throws:** log once more without the
  exception: original text followed by a marker containing the trailing exception's class name
  and the class name of the error that occurred (same marker style as
  `MessageFormatter.marker`). If the second call also throws, swallow (except fatal). When
  `log(level, text)` without exception throws: do not retry, swallow (except fatal). No
  recursion, no unbounded retries.
- **Single fatal-error rule:** one shared package-private helper (e.g.
  `Failures.rethrowIfFatal(Throwable)`) used by `Log`, `LineHandler`, `LineFormatter` and
  `MessageFormatter`. The rule is written nowhere else.
- **MINOR:** MNT-2 — comment on the `RuntimeException` wrapping in `LineHandler.publish`
  explaining it exists only for `reportError`'s signature. MNT-3 — `LineFormatter.stackTrace`
  does not reassign `text` in a catch block; a helper returns either the stack trace or the
  fallback line.
- Class-level Javadoc in `Log` describes the guarantee and its boundary exactly as above.
- Nothing else in `spola.util` changes in substance. All existing 70 tests stay green.

## Acceptance criteria
1. `java Build.java` in `framework/` exits with code 0.
2. A test with an injected `System.Logger` (via `Log.of`) whose `log(level, msg, thrown)` calls
   `thrown.toString()` and `thrown.printStackTrace(...)` shows, for (a) an exception whose
   `toString` throws `AssertionError` and (b) an exception with recursive `getMessage`
   (yielding `StackOverflowError`), that nothing escapes `log.warn("x", e)` and that a record
   with the original text and the marker (containing both class names) is logged.
3. A test with an injected `System.Logger` whose `log` methods always throw (`RuntimeException`
   and `AssertionError` respectively) shows that `log.info("x")`, `log.error("x {}", arg)` and
   `log.error("x", e)` do not throw, and that the backend is called at most twice per logging
   call (counter).
4. A test shows that an `OutOfMemoryError` thrown by the backend (simulated with
   `throw new OutOfMemoryError("test")`) is rethrown out of `Log`.
5. A test without `Logging.configure` (JDK default backend, with `System.err` redirected to a
   `ByteArrayOutputStream` during the test and restored afterwards, or equivalent isolation
   that leaves no global configuration behind) shows that cases (a) and (b) of criterion 2 do
   not throw out of `log.warn("x", e)`.
6. The fatal-error rule exists in exactly one place in main code (checkable by searching for
   `VirtualMachineError` in `modules/util/src/`: only the helper and Javadoc).
7. MNT-2 and MNT-3 are addressed as required.
8. Javadoc in `Log` describes the guarantee and the `isLoggable` exception.
9. All acceptance criteria in `uppdrag/05a-util-loggning-och-hjalpare.md` still hold (no
   existing tests removed or weakened).
10. No annotations beyond the allow-list, no dependencies beyond the JDK, no
    `java.lang.reflect`/`java.lang.invoke` in main code, no resource files (guardrails green);
    no rule in `framework/REGLER.md` broken.
11. `PROGRESS.md` and `ARCHITECTURE.md` are updated: the guarantee and its boundary
    (`isLoggable`) are under "Util del 1", and the finding no longer appears as a known problem.

## Out of scope
- New functionality in `spola.util`; builders and with-methods (mission 05b).
- Protection against backends that block, are slow or drop records.
- `isLoggable` that throws (documented, not fixed).
- Changing the JDK's default JUL configuration as a side effect.
- A new `Lazy` test without `System.gc()`.

## Assumptions
- The mission text mentions "three MINOR findings" but lists only MNT-2 and MNT-3; those two
  are the required ones.
- Round limit is 2 (from mission.md `Rundor`).
