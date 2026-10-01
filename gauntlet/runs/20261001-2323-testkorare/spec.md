# Spec: Testkörare (spola.test)

## Task
Build a custom test runner, module `spola.test`, that replaces JUnit. Tests are registered as lambdas in plain Java code, grouped in nestable suites. From this mission on, all framework code is tested with this runner. Full text: `mission.md` (source: `uppdrag/02-testkorare.md`).

## Ground rules
Everything in `framework/REGLER.md` applies. A violation is a BLOCKER.

## Work product
`framework/`

## Verification command
`java Build.java` in `framework/` (must exit 0).

## Requirements (from mission)
- New module `spola.test` under `framework/modules/test/`.
- Tests are values, e.g. `test("name", () -> { ... })`, grouped into suites that can nest.
- Assertions: equality, truth value, null, exceptions (type and message), collections (contents, order, size). Failure messages show the expected and the actual value.
- Fixtures: before/after per test and per suite, with cleanup guaranteed even when the test fails.
- Parameterized tests: one test runs against a list of inputs, and each case is reported separately.
- Timeout per test.
- Report to stdout: per test (ok/failed/skipped), a summary with counts and time, and stack traces for failures.
- Each module's test entry point is given explicitly in `Build.java` (module → test class with a method that returns the suite). No scanning, no naming conventions.
- `Build.java` runs the tests for all modules after guardrails and fails if any test fails.
- The guardrails self-tests from mission 01 are moved into the new runner.

## Acceptance criteria
1. `java Build.java` in `framework/` exits with code 0.
2. The `test` module has its own tests, run by the runner itself, covering assertions, fixtures, parameterization, timeout and nested suites.
3. Adding a failing test to any module gives a red build, and the report shows the test's name, the expected value and the actual value.
4. A test that throws an exception after a fixture has opened a resource still gets its cleanup run (verified by a test).
5. A test that exceeds its timeout is marked as failed, and the remaining tests keep running.
6. A parameterized test with three cases, one of which fails, reports two ok and one failed.
7. The guardrails self-tests run through the new runner.
8. No annotations beyond the allowlist and no dependencies beyond the JDK (guardrails green).
9. `PROGRESS.md` and `ARCHITECTURE.md` are updated.

## Out of scope
Parallel test execution, a mocking framework, IDE integration, CI report formats (JUnit XML and the like).

## Assumptions
- "Skipped" status is supported in the report (for example a disabled/skip test or a skip on a failed suite fixture); the builder documents the exact semantics.
- A timed-out test that cannot be interrupted may be left running on a daemon thread; the runner must not hang.
