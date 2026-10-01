# Round 3 — build

## Changes
- `framework/Build.java`
  - `installExitGuard`: the hook now returns without doing anything once `main` has decided (`decidedExitCode >= 0`), so other shutdown hooks and the JDK's `deleteOnExit` cleanup finish normally. It halts with 1 only when the decision hasn't been made yet. The comment explains why the post-decision halt isn't needed (a second `System.exit` blocks during shutdown) and how it relates to TestTool's guard (MNT-2).
  - New `checkExitGuard()` as the last build step. It spawns `java Build.java --exit-guard-probe decided|undecided DIR` twice. Each child installs the guard, creates a `deleteOnExit` file and registers an app hook (sleeps 200 ms, then writes a marker), then calls `System.exit(0)`. In the decided case the build requires exit 0, the file deleted and the marker present. In the undecided case it requires exit 1 and the "BUILD RED" message. `main()` became `main(String[] args)` to dispatch the probe mode.
- `framework/modules/test/src/spola/test/TestTool.java`
  - Guard extracted to `installExitGuard(out, err, entry, beforeHalt)` (MNT-3), with a comment on the layered guards (MNT-2).
  - Guard registered **before** `entry.suite()` (BRK-3).
  - Both public overloads delegate to a private `run(out, err, beforeHalt, args)`. The PrintWriter overload passes a `beforeHalt` that copies the buffered output to the caller's writers before halting (BRK-2).
- `framework/modules/test/src/spola/test/Values.java` and `Assertions.java`: new `Values.describer(boolean confusing)`. `describeBoth`, `assertContains` and `assertAnyOrder` use it instead of three copies of the ternary (MNT-1).
- Tests: `ExitProbe` takes `<method> [writer]`, and `SampleEntries.exitingEntry()` exits inside the entry method. In `ToolTests`, the single child-JVM test became a parameterized test with 3 cases: exit in a test, exit in a test through the PrintWriter overload, and exit in the entry method.
- `ARCHITECTURE.md` ("Fail closed vid `System.exit`") rewritten to match. `PROGRESS.md` updated (77 tests, exit-guard probe, build time ~9 s).

## Verification
`cd framework && java Build.java` gives exit=0:
```
guardrails: no violations
...
spola.test: OK - 77 tests, 77 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 2155 ms

exit guard:
  ok - after the decision: exit 0, other hooks and deleteOnExit ran
  ok - before the decision: System.exit(0) ends red (exit 1)
BUILD OK in 9556 ms
```
Mutation checks. I made each change temporarily, ran the build, then restored the file.
- Re-added `halt(decidedExitCode)` after the decision in Build.java's guard. The build went red (exit=1): `BUILD FAILED: exit guard: after the decision a System.exit(0) must exit 0 and let the other shutdown hooks and deleteOnExit run; got exit 0, delete-on-exit left behind, hook did not run`
- Removed `beforeHalt.run()` in TestTool. The build went red: `FAILED [2] in a test, PrintWriter overload`
- Moved guard registration back after `entry.suite()`. The build went red: `FAILED [3] in the entry method`

## Addressed
- BRK-1 [MAJOR]: no halt after the decision. A build-level regression probe (`checkExitGuard`) runs on every build, and the mutation check above shows it catches the regression. ARCHITECTURE.md updated.
- BRK-2 [MINOR]: the PrintWriter overload copies its buffers to the caller's writers before the guard halts. Covered by test case [2].
- BRK-3 [MINOR]: the guard is registered before `entry.suite()`. Covered by test case [3].
- MNT-1 [MINOR]: `Values.describer` added and used in all three places.
- MNT-2 [MINOR]: both guards now have comments explaining why the other exists and the 1-or-3 race. The same is in ARCHITECTURE.md.
- MNT-3 [MINOR]: `TestTool.installExitGuard(...)` extracted.

## Rebuttals
- None.

## Known gaps
- The exit-guard probe adds about 2–3 s to the build, because it starts two child JVMs that each compile Build.java in source-launcher mode.
- `Runtime.halt` in a test still bypasses every hook. This is unchanged and documented.
- The probe only runs when the tests are green. On a red build, `runTests` throws before the probe runs, and the build is red anyway.
