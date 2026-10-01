# Round 2 — verdict: FAIL

Round-1 findings: BRK-1 (System.exit makes the build green) and BRK-2 (`assertNotEquals(0, 0L)` passes) are both resolved. The breaker confirmed both, and the judge re-ran `java Build.java`: BUILD OK, exit=0, spola.test 75/75. The round-1 MINORs (BRK-3, the BRK-2 secondary part, MNT-1, MNT-2 and MNT-3) are also addressed.

## Upheld findings (the builder must address these)
- BRK-1 [MAJOR] framework/Build.java `installExitGuard` (`Runtime.getRuntime().halt(code)` on the path after `main` has decided) — The fix for round-1 BRK-1 brought in a regression. The guard halts even after `main` has decided the exit code, so on every build, green or red, `halt` cuts off the other application shutdown hooks and skips the JDK's DeleteOnExit hook, which runs in a later system slot. The judge confirmed the mechanism on its own in /tmp/jdg2/H.java: with a shutdown hook that calls `halt(0)`, a `deleteOnExit` temp file was left behind after `System.exit(0)`. The breaker's controlled before/after comparison against `HEAD:Build.java` reproduces it at build level. Tests that use `File.deleteOnExit` or a shutdown hook are ordinary, good-faith code, and this silently breaks their cleanup on every build, so it is MAJOR. The stated reason for halting ("a late System.exit from an abandoned thread cannot change it") does not hold: a second `System.exit` blocks while shutdown is already in progress, so the halt gains nothing. — fix_hint: Halt only when `decidedExitCode < 0`. Otherwise return from the hook and let the normal shutdown finish. Add a regression check, for example a child JVM through ExitProbe or a build-level probe, showing that a `deleteOnExit` file or an application hook still completes after a green run. Update the decision text in ARCHITECTURE.md to match.

## Rejected
- AUD-1 — Not a defect. The auditor itself says no fix is needed. The reflection loads only the explicitly named entry the spec requires (same ruling as round-1 AUD-1).

## Duplicates
- (none)

## MINOR (optional for the builder)
- BRK-2 [MINOR] framework/modules/test/src/spola/test/TestTool.java, `run(PrintWriter, PrintWriter, ...)` — When a test calls System.exit, the guard writes its message to the in-memory buffer and then halts, so the caller of the PrintWriter overload gets exit 3 with no output. The run is still red, so this is diagnostic only. — fix_hint: In that overload, copy the buffers to the caller's writers (or write there directly) before halting.
- BRK-3 [MINOR] framework/modules/test/src/spola/test/TestTool.java — The guard is registered after `entry.suite()`, so a System.exit in the entry method or a static initializer gets past TestTool's guard when TestTool is used on its own. Build.java's guard covers this case in the build, and the build is the spec's gate. — fix_hint: Register the guard before `entry.suite()`.
- MNT-1 [MINOR] Assertions.java and Values.describeBoth — The "show element classes when elements read the same" logic appears three times. — fix_hint: Add one helper in Values.
- MNT-2 [MINOR] Build.java and TestTool.java exit guards — The two layered hooks race (exit code 1 or 3), and neither says why the other exists. — fix_hint: Add a comment in each hook. Note that the BRK-1 fix removes the post-decision halt from the Build guard.
- MNT-3 [MINOR] TestTool.run — The guard setup is inline. — fix_hint: Extract `installExitGuard(out, err, entry)`.
