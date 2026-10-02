# Report: 20261002-0751-util-loggning-och-hjalpare

- **Status:** STALLED
- **Title:** Util part 1: logging on System.Logger and Lombok helpers (`spola.util`)
- **Mission:** `uppdrag/05a-util-loggning-och-hjalpare.md` (written by the planner as the first half of PLAN row 05; stays in `uppdrag/`)
- **Rounds:** 3 of 4

| Round | Verdict | Upheld | Build | Commit |
|---|---|---|---|---|
| 1 | FAIL | 0 blocker, 1 major, 4 minor | green | ba58f82 |
| 2 | FAIL | 0 blocker, 1 major, 4 minor | green | 588e977 |
| 3 | FAIL | 0 blocker, 1 major, 3 minor | green | 9b21e3d |

The upheld BLOCKER+MAJOR count did not drop for two rounds in a row (1 → 1 → 1), so the stall rule ended the iteration.
Each round's MAJOR was a *different*, narrower issue, and the previous one was fixed each time, so the work did progress.

## What was built
A new module `spola.util`. It contains:
- `Log`, which wraps `System.Logger` with `{}` placeholders and does no work when the level is off.
- `Logging`, a one-line console format with a level per prefix.
- `Lazy`.
- `Unchecked`, which wraps checked exceptions instead of a sneaky throw.
- `ToString`, which hides secrets.

There is also a README comparing each helper with Lombok and Spring. All 13 acceptance criteria were judged MET by the auditor in every round.

## Fixed along the way
- R1 BRK-1: an exception message containing a newline could forge a log record. Fixed with tab-prefixed continuation lines and escaping of control characters.
- R2 BRK-1: an `Error` thrown from a trailing Throwable's `toString`/`getMessage` escaped the console handler. Fixed by catching `Throwable` and logging a marker line.
- Minors: duplicate-block comments in `Log`, a `RootSnapshot` record in `Logging`, U+2028/U+2029 written as escapes.

## Remaining (blocking)
- **R3 BRK-1 (MAJOR):** without `Logging.configure`, using the JDK's default JUL handler, `log.warn("x", e)` still lets `AssertionError`/`StackOverflowError` out when `e.toString()`/`getMessage()` throws. The fix hint is to wrap `logger.log(level, text, thrown)` in `Log.emit` and in the supplier variant with `catch (Throwable)`. That catch should rethrow fatal VM errors other than SOE and otherwise log again without the throwable, with a marker. It also needs a test with the default setup or a `System.Logger` that calls `thrown.toString()`.

## Remaining (minor)
- MNT-1: the "rethrow fatal VMError except SOE" rule is duplicated in `LineHandler` and `MessageFormatter.marker`.
- MNT-2 / AUD-1: the unreachable `RuntimeException` wrapper in `LineHandler.publish` has no comment.
- MNT-3: `LineFormatter.stackTrace` reassigns `text` in a catch block.
- (Earlier) the supplier-release test in `LazyTests` relies on `System.gc()`.

## Assumptions
- The planner split PLAN row 05 into 05a (this one) and 05b (record builders/with). The split is recorded in `PROGRESS.md`/`ARCHITECTURE.md`.

## Stash
None. Every round was green and committed.
