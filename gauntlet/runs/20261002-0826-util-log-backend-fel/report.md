# Report: 20261002-0826-util-log-backend-fel

- **Status:** PASS
- **Title:** Finish 05a: keep backend logging errors from escaping spola.util Log
- **Source:** uppdrag/05a-util-loggning-och-hjalpare.md (now moved to uppdrag/klara/)
- **Rounds:** 1 of 2

## Rounds
| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | PASS (0 blocker, 0 major, 5 minor) | green (79 util tests) | b2bb961 |

## What was fixed
- BRK-1 (MAJOR from the previous run): a bad exception's `toString`/`getMessage`, or a backend that throws,
  no longer escapes `Log`, whichever backend is used. All backend calls go through `Log.deliver`. If the backend
  fails, `Log` retries once without the exception and adds a marker. Other failures are swallowed, with
  at most two backend calls.
- The fatal-error rule now lives in one place, `Failures.rethrowIfFatal`, which `Log`, `MessageFormatter`,
  `LineFormatter` and `LineHandler` all use.
- MNT-2 (comment on the `RuntimeException` wrapping) and MNT-3 (the `LineFormatter.printed` helper).
- The Javadoc, PROGRESS.md and ARCHITECTURE.md describe the guarantee and the `isLoggable` boundary.

## Remaining findings (MINOR)
- MNT-1: duplicated try/catch blocks in `Log.deliver`.
- MNT-2: naming of the private `log`/`emit`/`deliver` chain.
- MNT-3: an over-long line in `LineHandler.publish`.
- AUD-1: the supplier bullet's wording under "Outside the guarantee" in the `Log` Javadoc.
- AUD-2: spec ambiguity; no action needed.

## Assumptions
- The judge was skipped because all critics gave PASS with no BLOCKER or MAJOR.
- "Three MINOR" in the mission was read as MNT-2 and MNT-3; MNT-1 was covered by the shared-helper requirement.

## Stash
–
