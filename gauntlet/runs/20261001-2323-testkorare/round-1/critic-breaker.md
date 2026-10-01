```
VERDICT: FAIL
FINDINGS:
- id: BRK-1
  severity: MAJOR
  where: framework/Build.java:345-357 (runTests runs tests in the build's own JVM) together with framework/modules/test/src/spola/test/Runner.java:199-208
  problem: If a test calls System.exit(0), for example a test of a CLI main(), the whole build ends with exit code 0. Every later test is never run, including a deliberately failing one in the same suite and all of spola.test's own tests, so the build goes green even though tests failed or never ran. That breaks "Build.java ... fails if any test fails" and AC3.
  evidence: In a copy (round-1/scratch-breaker/fw) I added Experiments.all() to GuardrailsTests containing
      test("a CLI main that exits 0", () -> System.exit(0)),
      test("deliberately failing", () -> assertEquals(42, 41))
    and ran `java Build.java; echo exit=$?`. The output ends with:
      tests of spola.guardrails:
      spola-test: running spola.guardrails/spola.guardrails.GuardrailsTests::suite
      spola.guardrails
        experiments
      exit=0
    There is no summary and no BUILD OK/RED line, and the spola.test module was never tested. build.md lists this as a "known gap", but it is a silent false green on an ordinary test. Maven Surefire, by contrast, fails the build when a forked VM exits early.
  fix_hint: Run each module's tests in a child JVM (ProcessBuilder with `java --module-path ...`) and require an explicit completion marker or exit code. Or, at minimum, register a shutdown hook that calls Runtime.halt(1) unless the run finished normally.

- id: BRK-2
  severity: MAJOR
  where: framework/modules/test/src/spola/test/Assertions.java:84-89 (assertNotEquals(Object, Object), no primitive overloads)
  problem: assertNotEquals boxes its arguments to Object with no primitive overloads. So assertNotEquals(0, someLong) compares Integer 0 with Long 0L and passes even when the value is exactly the "unexpected" 0. This is a false green on an ordinary call: JUnit has assertNotEquals(long, long) for exactly this case.
  evidence: In the scratch copy I added this test:
      var p = Files.createTempFile("brk", ".txt");   // empty file
      assertNotEquals(0, Files.size(p));             // size is 0L, must fail
    The build report shows "ok      notEquals boxing (2 ms)". The same build shows that `assertContains(List.of(1L, 2L), 1)` fails with "collection lacks [1] / expected: contains [1] / actual: [1, 2]", a message that does not reveal the Integer/Long mismatch (describeBoth is not used there).
  fix_hint: Add primitive overloads (long/long, double/double, char/char) for assertEquals and assertNotEquals, or reject comparisons between different boxed Number types. Show the classes when an element description is ambiguous in assertContains, assertInOrder and assertAnyOrder.

- id: BRK-3
  severity: MINOR
  where: framework/modules/test/src/spola/test/Values.java:20-24
  problem: describe() only guards against a collection that directly contains itself. Two collections that contain each other recurse until a StackOverflowError, so the test fails with SOE instead of an AssertionFailure that shows expected and actual.
  evidence: Code reading: List a = new ArrayList<>(), b = new ArrayList<>(); a.add(b); b.add(a); assertEquals(List.of(), a). deepEquals is false because the sizes differ, then describe(a) → describe(b) → describe(a) ... with no visited set (the element == collection check covers only one level).
  fix_hint: Track visited collections with an identity set in describe.
```

What I tried that held up:
- AC3: a failing assertEquals turns the build red and the report shows the name, expected and actual.
- AC6: a parameterized test with 3 cases, one failing, reports 2 ok and 1 failed, each case separately.
- AC5: a test that swallows the interrupt is still marked failed with TimedOut, and later tests run.
- Guardrails catches a jar dropped in `modules/test/test/lib/` (FOREIGN_JAR).
- Guardrails catches `@java.lang.annotation.Native` in a test-only package (`spola.guardrails.extra`).
- The baseline build is green (exit 0).

All experiments are in `/workspace/gauntlet/runs/20261001-2323-testkorare/round-1/scratch-breaker/fw`.
