```
VERDICT: FAIL
FINDINGS:
- id: BRK-1
  severity: MAJOR
  where: framework/Build.java:84-97 (installExitGuard, halt on the "decided" path)
  problem: The round-2 exit guard calls Runtime.halt(decidedExitCode) even when the build ends normally, so every green or red build now kills the JVM before the other shutdown hooks and File.deleteOnExit cleanup can finish. Any test that cleans up through these standard JDK mechanisms leaks its resources, or has its cleanup cut off, on every build.
  evidence: I copied framework/ to round-2/scratch-breaker/fw and added this test at the front of GuardrailsTests.suite():
      Tests.test("temp file cleaned on exit", () -> { var f = Files.createTempFile("brk2-doe-", ".tmp").toFile(); f.deleteOnExit(); System.out.println("TEMP " + f); })
    `java Build.java` gave exit=0 and printed "TEMP /tmp/brk2-doe-16777769758429830170.tmp". Afterwards `ls -la /tmp/brk2-doe-*` showed "-rw------- 1 node node 0 Oct  1 23:49 /tmp/brk2-doe-16777769758429830170.tmp", so the file was NOT deleted.
    Control: same copy, with Build.java swapped for `git show HEAD:framework/Build.java` (no guard). It gave exit=0, and `ls /tmp/brk2-doe-*` returned "No such file or directory", so the file was deleted. This is a regression.
    Second check: a test that registers an application shutdown hook (sleep 200 ms, then write /tmp/brk2-hook-done). With the round-2 Build.java I got exit=0 and `ls /tmp/brk2-hook-done` returned "No such file or directory". The build guard's halt kills the hook before it finishes.
    Cause: DeleteOnExitHook runs in a system hook slot after all application hooks, and halt() from inside an application hook skips it. Future spola modules (a DI container with graceful close) will need shutdown hooks that actually run.
  fix_hint: Halt only when decidedExitCode < 0. Once main has decided, return from the hook and let the normal exit continue. A late System.exit(n) from an abandoned thread blocks on the shutdown lock held by the first exit, so it cannot change the status anyway. Add a test or build-level check that deleteOnExit still works.

- id: BRK-2
  severity: MINOR
  where: framework/modules/test/src/spola/test/TestTool.java:58-70 together with :94-100
  problem: Through the PrintWriter overload of run (the standard ToolProvider entry point), a test that calls System.exit makes the guard halt with status 3, but neither the guard message nor the report collected so far is ever printed.
  evidence: The guard writes to `err`, which in this overload is the ByteArrayOutputStream-backed PrintStream, and then calls Runtime.halt(3) (line 100). The `finally` at lines 64-70 that copies the buffers to the caller's writers never runs after a halt. The caller sees exit 3 with no output at all. The new regression test only covers the PrintStream path (ExitProbe).
  fix_hint: In the PrintWriter overload, have the guard write directly to the caller's writers (or flush the buffers to them) before halting.

- id: BRK-3
  severity: MINOR
  where: framework/modules/test/src/spola/test/TestTool.java:86-101 (guard registered only after entry.suite())
  problem: When TestTool is used on its own (outside Build.java), a System.exit(0) inside the entry method or a static initializer runs before the guard is registered, so the run ends with exit 0, no report and no failure.
  evidence: entry.suite() is called at line 86, and addShutdownHook only at line 101. Build.java's own guard covers this case inside the build (build.md shows exit=1), but TestTool's Javadoc promises that "the run can never end green without its report".
  fix_hint: Register the guard before calling entry.suite().
```

I also tried the following, and found no defect in any of them:
- The verification command is green: exit 0, 75 tests in spola.test.
- assertNotEquals numeric edge cases are correct: Long.MAX_VALUE vs (double) Long.MAX_VALUE, 0 vs -0.0, NaN and infinity.
- The guard covers a System.exit in an abandoned timed-out thread and in afterAll fixtures.
- Nested in-process TestTool runs still work with the guard.

Scratch work is in /workspace/gauntlet/runs/20261001-2323-testkorare/round-2/scratch-breaker/fw.
