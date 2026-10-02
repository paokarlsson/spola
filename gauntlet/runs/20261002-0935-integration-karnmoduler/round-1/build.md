# Round 1 – build

## Changes
- **New module `spola.demo`** (`framework/modules/demo/`), registered in `Build.java` `MODULES` with test entry `spola.demo.DemoTests::suite`.
  - `module-info.java`: `requires spola.config; requires spola.di; requires spola.util;` no exports.
  - Entry `InventoryApp` (`main`, public `run(List<String>, Map<String,String>, PrintStream out, PrintStream log)` -> exit code; package-private overload taking the module list as a function, used by the wiring-error test). Maps errors to `error: <message>` + sysexits codes 65/70/74/78, no stack traces.
  - Wiring module `InventoryWiring`: source order defaults -> file (`config.file`) -> profile file (`inventory-<p>.properties`) -> env `INVENTORY_` -> args; bootstrap round for `config.file`; `audit.secret` (and `*.secret`/`*.password`) sensitive; one root `bind` to `AppSettings` (`InventorySettings`, `RuleSettings`, `AuditSettings`, log levels); container modules `settings(app,out)`, `STOCK`, `CHECKS`, `PROCESSING` with `toInstance` for the records.
  - Components (no container/config knowledge): `Stock`, `OrderCheck` + `QuantityLimit`/`BlockedSkus` (qualifier `CheckKind`), `AuditTrail` (AutoCloseable, HMAC-SHA256-signed trail), `OrderProcessor`, `ReportWriter` (summary stream qualified `Channel.SUMMARY`). Data: `OrderLine`, `OrderFileException`, `Summary`.
  - Tests (36): `ProgramTests` (crit. 3), `PriorityTests` (4), `SecretTests` (5), `LifecycleTests` (6), `LogLevelTests` (7), `FailureTests` (8), `BoundaryTests` (2, 9: source scan + module-info scan), `PartsTests` (RFC 4231 HMAC vectors, order-line format). Helper `Shop` (temp dir, runs program via entry).
  - `README.md`: how to run, keys, exit codes, Spring Boot comparison, `## Friktion` (7 points: 2 Åtgärdat, 5 Kvar) + what worked well.
- **spola.config**: `Section.keys()` (additive) + 3 tests in `BindingTests`; README section "Nycklar som inte är kända i förväg".
- **spola.util**: `LoggingSettings.parseLevel(String)` (additive) + 1 test in `LoggingTests`; README "Nivåerna ur spola.config" rewritten to use `Section.keys()` + `parseLevel` (example error text verified by running it).
- `ARCHITECTURE.md`: dir listing, API tables (config/util), new section "Integration: kärnmodulerna i ett program (spola.demo)" with decisions and the two core changes.
- `PROGRESS.md`: "Uppdrag 05c" under Klart; the 5 Kvar items under "Återstår".

## Verification
`java Build.java` in `framework/` -> exit 0:
```
modules in build order: [spola.guardrails, spola.test, spola.di, spola.config, spola.util, spola.demo]
guardrails: no violations
spola.guardrails: OK - 17 tests
spola.test: OK - 77 tests
spola.di: OK - 48 tests
spola.config: OK - 66 tests, 66 ok   (63 before + 3 new)
spola.util: OK - 80 tests, 80 ok     (79 before + 1 new)
spola.demo: OK - 36 tests, 36 ok
BUILD OK in 19768 ms
```
The boundary test was observed failing once (it caught javadoc mentions in test files) before it was narrowed to names + imports, so it does detect offenders.
Manual: `grep -rlE "Container|Binder|Config" modules/demo` -> only `InventoryWiring.java`, `InventoryApp.java`.
Manual CLI run from a temp dir: `java -p framework/out/classes -m spola.demo/spola.demo.InventoryApp` -> summary + exit 0; without secret -> `error: invalid configuration ... missing key 'audit.secret'`, exit 78; README error examples copied from real runs.
`git diff --stat`: `Allowlist` untouched; no non-.java/.md files added.

## Addressed
Round 1 – no findings yet.

## Rebuttals
None.

## Known gaps
- Wiring error (crit. 8) cannot be triggered by end-user configuration (by design wiring errors are developer errors caught at `Container.create`); the test reaches it through the entry's package-private overload with `CHECKS` left out. Documented in ARCHITECTURE.md.
- Criterion 9 check (`BoundaryTests`) is a text search; it exempts `module-info.java` (which must say `requires spola.config`) and the two wiring files.
- Kvar items (README Friktion 3–7) are not fixed: mixed Class/Key typed bindings, two-round config-file bootstrap, hand-written startup/error mapping, JVM-global logging, logger-prefix validation outside the config report.
- Profile files are optional (like Spring); a typo in a profile name silently loads nothing.
