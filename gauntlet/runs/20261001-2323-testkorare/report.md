# Report: 20261001-2323-testkorare

- **Status:** PASS
- **Title:** Testkörare: lambda-based test runner (spola.test) replacing JUnit
- **Source:** uppdrag/02-testkorare.md (moved to uppdrag/klara/)
- **Rounds:** 3 of 4

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | FAIL (0 blocker, 2 major, 6 minor) | green | 4b5cf15 |
| 2 | FAIL (0 blocker, 1 major, 5 minor) | green | 5bad320 |
| 3 | PASS (0 blocker, 0 major, 4 minor) | green | 9fd3bb7 |

## Delivered
New module `spola.test`. Tests are lambda values in nestable suites, with:
- assertions (equality, truth, null, exceptions, collections)
- fixtures with guaranteed cleanup
- parameterized cases reported one by one
- per-test timeouts
- a stdout report

Build.java runs each module's explicitly named test entry after guardrails. The guardrails self-tests now run through the new runner. 77 tests in spola.test and 17 in guardrails.

## Fixed along the way
- **Round 1:** a test calling `System.exit(0)` gave a silent green build. Now exit guards turn it red.
- **Round 1:** `assertNotEquals(0, 0L)` passed. Boxed numbers are now compared by value.
- **Round 1:** collections that contain each other caused a StackOverflowError. Now they print `(cycle)`.
- **Round 2:** the build's exit guard halted even after the result was decided, which skipped shutdown hooks and `deleteOnExit`. It now halts only before the decision, and a build-time probe checks this.
- **Round 2:** the TestTool guard is now installed before the entry loads, and the PrintWriter overload flushes its output before halting.

## Remaining (MINOR)
- The exit-guard probe adds about 2–3 s per build.
- TestTool cleanups: Javadoc wrapping, an undocumented assumption that `copy` runs once, and the private `run` overload should be renamed `runEntry`.
- Known gaps, documented in ARCHITECTURE.md:
  - `Runtime.halt(0)` from a test bypasses the guards.
  - Numbers are compared by value only at the top level, not inside collections.
  - A timed-out test that ignores interrupts stays alive on a daemon thread.

## Assumptions
- "Skipped" status semantics are defined by the builder.
- A non-interruptible timed-out test may stay alive on a daemon thread.

## Stash
–
