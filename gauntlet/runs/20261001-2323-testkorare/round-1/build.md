# Round 1 – build

## Changes

**New module `spola.test`** (`framework/modules/test/`)
- `src/module-info.java`: exports `spola.test`, `provides ToolProvider with spola.test.TestTool`. No `requires`.
- `Test` (sealed: `TestCase` | `Suite`), `TestCase` and `Suite` (records with copy methods for `timeout`, `skip`, `beforeAll/beforeEach/afterEach/afterAll`), `Body`, `CaseBody<T>`, `Tests` (factories `test`, `suite`, `parameterized`).
- `Assertions`: equality (`assertEquals` incl. double tolerance, `assertNotEquals`, `assertSame`), truth, null, exceptions (`assertThrows` type / type+exact message), collections (`assertSize`, `assertEmpty`, `assertContains`, `assertInOrder`, `assertAnyOrder`), `fail`. `AssertionFailure` holds `expected()`/`actual()`, and its message shows them on separate lines.
- `Runner` (each test and suite fixture runs on a fresh platform daemon thread with a timeout, is interrupted when the timeout expires, then gets a 1 s grace period before it is abandoned), `Report`, `Result`, `Status`, `TimedOut`, `Values` (value formatting), `Names` (validation).
- `TestTool`: the `spola-test` ToolProvider, `--module M --class C --method m`. It is the only reflective call (`Class.forName(Module, String)` + `Method.invoke`).
- Tests in `modules/test/test/spola/test/`: `RunnerTests` (entry), `AssertionsTests`, `FixtureTests`, `ParameterizedTests`, `TimeoutTests`, `StructureTests`, `ReportTests`, `ToolTests`, `SampleEntries`. That is 70 tests.

**Build.java**
- `ModuleDir(name, dir, TestEntry(className, method))`. Every module has an explicit test entry: guardrails → `spola.guardrails.GuardrailsTests::suite`, test → `spola.test.RunnerTests::suite`.
- New steps run in this order: compile, jar, `compileTests` (src+test → `out/test-classes/<m>`, `--add-modules spola.test --add-reads m=spola.test`, `-Xlint:-exports` for tests only), guardrails check (now with `--tests`/`--test-reads`), then `runTests`. `runTests` builds a new ModuleLayer per module, uses `Controller.addReads` and `addExports` for the entry package, gets `spola-test` via ServiceLoader, runs all modules and fails if any of them failed.
- Fail-closed checks: a missing `test/` dir, a `module-info.java` in test sources, a missing entry file, or an entry package that is not in the module each stop the build. Symlink checking now covers the test dirs too (refactored into `javaSources`).

**spola.guardrails**
- `Project` has two new components, `tests` (module → test dir) and `testReads`, with validation, and keeps a 4-arg convenience constructor. `Guardrails.analyzeSources` analyses test sources as part of their module (`--module-source-path m=src:test` + `--add-reads`). Test dirs no longer count as loose sources.
- `GuardrailsTool`: the `selftest` command is removed. `check` gained `--tests NAME=DIR` and `--test-reads MODULE`, and a duplicate `--module`/`--tests` name is now a usage error.
- `SelfTest.java` became `test/spola/guardrails/GuardrailsTests.java` (git mv). It now has one test per former check and a parameterized case per fixture, using `assertAnyOrder` on formatted violations. `TreeSpec.java` moved to the test tree as well. There are new tests for the test-source analysis and the `Project` validation, 17 tests in total.
- `Fixture.java`: Javadoc references updated.

**Docs**: `ARCHITECTURE.md` has a new section "Tester (`spola.test`)" plus updated build/guardrails/self-test sections. `PROGRESS.md` has mission 02 done and new known problems.

## Verification

`java Build.java` in `framework/`, run 3 times in a row:
```
exit=0 BUILD OK in 6752 ms
exit=0 BUILD OK in 6866 ms
exit=0 BUILD OK in 6844 ms
guardrails: no violations
spola.guardrails: OK - 17 tests, 17 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 349 ms
spola.test: OK - 70 tests, 70 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 1817 ms
```
Excerpt of the report (guardrails, fixture cases reported separately):
```
spola.guardrails
  self-test
    ok      annotation allowlist matches the table in REGLER.md (4 ms)
    ...
    fixture yields exactly its expected violations
      ok      [1] source-annotation (14 ms)
      ...
      ok      [10] symbolic-link (10 ms)
```
AC3, manually: I temporarily added `test("deliberately failing", () -> assertEquals(42, 41))` to GuardrailsTests (then restored the file). The run exited 1 and printed:
```
1) spola.guardrails > test sources > deliberately failing
   spola.test.AssertionFailure: values differ
     expected: 42
     actual:   41
   	at spola.test/spola.test.Assertions.assertEquals(Assertions.java:53)
   	...
BUILD FAILED: tests failed in spola.guardrails (exit code 1) (see the reports above)
```
The `spola.test` tests still ran in that build (`spola.test: OK - 70 tests`).

Guardrails covers test sources. I temporarily added `@interface Sneaky` in `modules/test/test/` and the build went red with both the source violation and the bytecode violation (`out/test-classes/...Sneaky.class`). File removed afterwards.

The shipped jars contain no test classes: `jar tf out/spola.guardrails.jar | grep -E "Tests|TreeSpec"` gives 0 matches. In `spola.test.jar` the only match is the API class `Tests.class`.

During development a real failure (a wrong frame-name expectation in ReportTests) gave a red build with the full report. That is how I found it and fixed it.

Acceptance criteria → evidence:
1. Exit 0, see above.
2. `RunnerTests` covers assertions, fixtures, parameterization, timeout and nested suites (plus report and tool).
3. Manual run above. `ToolTests` "a red entry exits 1 and the report shows name, expected and actual" and `ReportTests` "a failure shows the test's name, the expected and the actual value and a stack trace" check it automatically.
4. `FixtureTests` "cleanup runs when the test throws after a fixture opened a resource". It opens a real `FileChannel` in `beforeEach`, the test throws, and the test asserts that the channel is closed and the file deleted.
5. `TimeoutTests` "a test that overruns its timeout fails, and the remaining tests still run", plus a non-interruptible spinning test ("is abandoned and the run goes on").
6. `ParameterizedTests` "three cases, one failing, report two ok and one failed" checks the counts, the statuses in order and the output lines.
7. Guardrails self-test runs as `spola.guardrails > self-test > ...` via spola-test. `GuardrailsTool selftest` no longer exists.
8. `guardrails: no violations`. Only `@Override` and `@Serial` are used. No dependencies.
9. PROGRESS.md and ARCHITECTURE.md are updated.

## Addressed
Round 1, so there are no findings yet.

## Rebuttals
None.

## Known gaps
- **Abandoned threads:** a test that overruns its timeout and ignores interrupts is abandoned on a daemon thread. Its `afterEach` runs only if it ever finishes, possibly concurrently with later tests. This is documented, and the spec allows it.
- **`System.exit`:** a test that calls `System.exit` ends the build, because tests run in the build's JVM.
- **Working directory:** tests depend on `framework/` as the working directory (guardrails tests read `REGLER.md` and the fixtures relatively). The build enforces this directory, and it is documented.
- **Fixture order:** every `afterEach` runs even if the matching `beforeEach` did not run (JUnit 5 semantics), so cleanup must tolerate partial setup. This is documented in the `Suite` Javadoc.
- **Name clash:** an entry method named `suite()` shadows the statically imported `Tests.suite`, so entry classes write `Tests.suite(...)`. This is documented.
- **Writer output:** the `TestTool.run(PrintWriter, ...)` overload buffers the output until the run ends. The build uses the streaming `PrintStream` overload.
- **Build time** went from about 3.5 s to about 6.8 s. Roughly 1.7 s of that is the timeout tests' deliberate sleeps.
- **Staged moves:** `git mv` was used for the two moved guardrails files, so those renames are staged in the index. Nothing was committed.
