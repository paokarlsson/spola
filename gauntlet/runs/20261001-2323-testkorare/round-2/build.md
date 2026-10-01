# Round 2 — build

## Changes
- `framework/Build.java`: added an exit guard (`installExitGuard`, a shutdown hook). If the JVM is asked to exit before `main` has decided the exit code, it prints `BUILD RED: ...` and calls `halt(1)`. Once `main` has decided, it halts with the decided code, so a late `System.exit` from an abandoned test thread cannot change the result. Also extracted `findTool(layer, module, name)` (MNT-1).
- `modules/test/src/spola/test/TestTool.java`: added a shutdown hook that is registered for the length of `Runner.run`. It prints which entry was running and calls `halt(3)` (`EXIT_DURING_RUN`, documented in the Javadoc). Added a one-argument `EntryException` constructor (MNT-3).
- `modules/test/src/spola/test/Assertions.java`: `assertNotEquals` now treats boxed primitive numbers of different types as equal when their exact values match (compared through `BigDecimal`, with a separate path for NaN and infinity). `assertContains`, `assertAnyOrder` and `assertInOrder` show element classes when elements read the same (`1` vs `1L`). `assertContains` uses `Arrays.asList` instead of `List.of`, so a null element no longer causes an NPE in the message.
- `modules/test/src/spola/test/Values.java`: `describe` tracks the collections it is inside in an identity set and prints `(cycle)` where one repeats (BRK-3). New `describeTyped` adds the class of each element. `describeBoth` uses it.
- `modules/test/src/spola/test/Runner.java`: added a `label(path, suffix)` helper (MNT-2).
- Tests: `AssertionsTests` has 4 new tests (numeric `assertNotEquals`, typed collection messages ×2, mutually containing collections). `ToolTests` has 1 new test that starts a child JVM. New `test/spola/test/ExitProbe.java` (a main class used only by that child JVM). New `SampleEntries.exiting()` (a test that calls `System.exit(0)`, followed by a failing test).
- `PROGRESS.md`: updated the known-problems entry and the test count (75). `ARCHITECTURE.md`: new decisions "assertNotEquals jämför boxade tal efter värde" and "Fail closed vid System.exit", including why primitive overloads and a child JVM per module were rejected.

## Verification
`java Build.java` in `framework/`:
```
spola.guardrails: OK - 17 tests, 17 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 377 ms
...
    ok      a test that calls System.exit(0) makes the run red instead of ending it green (101 ms)
...
spola.test: OK - 75 tests, 75 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 1972 ms
BUILD OK in 6892 ms
exit=0
```
I reproduced BRK-1 at build level in a copy (`/tmp/b2/fw`), using the judge's scenario. I added `test("exit", () -> System.exit(0))` and then `test("fail", () -> assertEquals(42, 41))` to the front of `GuardrailsTests.suite()`:
```
exit=3
spola-test: the JVM was asked to exit (System.exit?) while the tests of spola.guardrails/spola.guardrails.GuardrailsTests::suite were running; the run is red (halting with status 3)
BUILD RED: the JVM was asked to exit before the build finished (System.exit in a test or tool?)
```
I also tried `System.exit(0)` inside the entry method itself, before the tool's guard is installed, so only the Build guard can catch it:
```
exit=1
BUILD RED: the JVM was asked to exit before the build finished (System.exit in a test or tool?)
```
(Both hooks halt with a non-zero code. Which one wins (1 or 3) is a race, and either result is red.)

## Addressed
- BRK-1 [MAJOR]: The build now fails closed through two shutdown hooks (TestTool during a run, Build.java for the whole build). The regression test `ToolTests` › "a test that calls System.exit(0) makes the run red instead of ending it green" runs `SampleEntries.exiting` through `TestTool` in a child JVM. It asserts exit code 3, the guard message, and that no `OK` summary appears. I checked the build-level behaviour by hand (above).
- BRK-2 [MAJOR]: `assertNotEquals(0, 0L)` now fails with "values are numerically equal (only their types differ)" and shows `anything but 0 (java.lang.Integer)` / `0 (java.lang.Long)`. Regression tests cover int/long, byte/short, float/double, int/double and NaN/NaN. Tests also check cases that must pass: 1 vs 2L, 0.1f vs 0.1, `Long.MAX_VALUE` vs `(double) Long.MAX_VALUE`, +inf vs -inf, and 0 vs "0". I did not add primitive overloads. They would make `assertEquals(1, integerVar)` ambiguous at compile time (the JUnit problem) and throw an NPE when unboxing null. The reasoning is recorded in ARCHITECTURE.md.
- BRK-3 [MINOR]: fixed with an identity set and tested ("collections that contain each other are shown without overflowing").
- BRK-2 secondary [MINOR]: fixed for `assertContains`, `assertAnyOrder` and `assertInOrder`, with tests.
- MNT-1, MNT-2, MNT-3 [MINOR]: fixed as hinted.

## Rebuttals
- None.

## Known gaps
- If a test calls `Runtime.halt(0)` directly, all shutdown hooks are bypassed and the build ends green with no report. This cannot be caught inside the same JVM. The only remedy is a child JVM per module with a completion marker, which I rejected (slower, and halt is not a plausible good-faith call). Documented in PROGRESS.md and ARCHITECTURE.md.
- The numeric comparison in `assertNotEquals` applies only at the top level. `assertNotEquals(List.of(0), List.of(0L))` still passes because the lists differ by `equals`.
- When both exit hooks run, the exit code is either 1 or 3 depending on which hook halts first. Both are red, but the code is not deterministic.
