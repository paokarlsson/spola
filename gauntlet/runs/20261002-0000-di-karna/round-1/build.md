# Round 1 build: spola.di

## Changes
- `framework/modules/di/src/module-info.java`: new module `spola.di`, `exports spola.di`, no `requires`.
- `framework/modules/di/src/spola/di/`, the public API:
  - `Key<T>` (record: type + qualifier value; rejects primitive types and array qualifiers)
  - `Scope` (SINGLETON lazy/default, EAGER_SINGLETON, PROTOTYPE, CALL)
  - `ContainerModule`, `Binder` (`bind`, `install`; installs each equal module once), `BindingBuilder<T>`
    (typed `to(...)` with 0–5 deps as `Class` or `Key`, resolver form `to(Dependencies, Factory)`,
    `toInstance`), `Binding` (`in(Scope)`)
  - `Factory<T>` (+ `Of0`..`Of5`, all `throws Exception`), `Resolver`, `Dependencies`
  - `Container` (`create`, `get`, `runInCallScope`, `callInCallScope`, `close`)
  - `WiringException` (all graph problems, `problems()`), `ResolutionException` (chain `A -> B`), `CloseException`
    (failures as suppressed)
- Internal: `Registration`/`Definition` (mutable binding becomes an immutable record), `Validator` (duplicates, missing
  target, missing deps with same-type hints, DFS cycles with the full chain, captive call-scoped deps through
  prototypes), `Slot` (double-checked lock per singleton/call instance), `Lifecycle` (reverse-order closing, errors
  collected), `FactoryResolver` (declared deps only, only while the factory runs).
- `framework/modules/di/test/spola/di/`: `DiTests` (entry), `KeyTests`, `BindingTests`, `ValidationTests`,
  `ScopeTests`, `ConcurrencyTests`, `LifecycleTests`. 48 tests.
- `framework/modules/di/README.md`: Spring example (`@Component`, `@Autowired`, `@Qualifier`) next to the same
  wiring in Spola, plus a mapping table.
- `framework/Build.java`: `spola.di` added to `MODULES` with test entry `spola.di.DiTests::suite`.
- `framework/ARCHITECTURE.md`: new section "DI-containern (`spola.di`)" with the API table and decisions, including
  how dependencies are declared and why (typed form preferred; resolver form restricted at runtime; `Container.get`
  refused inside factories). `PROGRESS.md`: mission 03 done, plus known limitations.

## Verification
`cd framework && java Build.java; echo exit=$?`:
```
exit=0
modules in build order: [spola.guardrails, spola.test, spola.di]
compiled spola.di (20 files)
compiled tests of spola.di (7 files)
guardrails: no violations
spola.guardrails: OK - 17 tests, 17 ok, ...
spola.test: OK - 77 tests, 77 ok, ...
spola.di: OK - 48 tests, 48 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 172 ms
BUILD OK in 11659 ms
```
Mapping from criteria to tests (all in `spola.di` and run by the build):
- C2: `grep -rnE "java\.lang\.(reflect|invoke)|MethodHandle" modules/di/src` gives no hits.
- C3: `validation > a cycle A -> B -> C -> A fails create with the whole chain`. It asserts the problem starts with
  `dependency cycle: spola.di.ValidationTests.A -> ...B -> ...C -> ...A (` and that no factory ran.
- C4: `validation > a missing dependency fails create, naming who misses what and where it was bound`. It asserts
  `...OrderService (bound at ...(ValidationTests.java:<line>)) depends on ...OrderRepo, which is not bound`, checks the
  exact line and that no factory ran.
- C5: `bindings > two implementations of one type, fetched by qualifier`, using the enum qualifiers EN and SV. It also
  injects both into one component.
- C6: `concurrency > a lazy singleton is created once when 1 000 virtual threads fetch it at the same time`. The threads
  start on a latch, the factory sleeps 50 ms, and the test asserts 1 factory call and 1 distinct instance. Mutation
  check: removing the lock in `Slot` makes this test and the overlapping-graphs test fail with
  `AssertionFailure: factory calls`. I restored `Slot` afterwards.
- C7: `scopes > a prototype is new on every fetch and every injection` and `scopes > the call scope gives one instance
  per scope and different ones across scopes`, plus nested scopes, use outside a scope, and 1 000 concurrent scopes.
- C8: `lifecycle > closing goes in reverse creation order` (exact log), `a failing close does not stop the others, and
  is reported` (exact log and message), `several failing closes are all collected`, and call-scope closing.
- C10: guardrails green. The only annotations in the module are `@Override`, `@FunctionalInterface` and `@Serial`.

## Addressed
Round 1, no findings yet.

## Rebuttals
None.

## Known gaps
- In the resolver form, an undeclared `r.get(X)` is caught when the component is first created, not at
  `Container.create`. The typed form cannot have this problem. This is documented in ARCHITECTURE.md as the price of
  that form.
- `Key` holds a `Class<T>`, so generic types such as `List<String>` cannot be keys directly.
- There is no `Provider`/lazy injection. A singleton that needs a per-call component must itself be CALL or PROTOTYPE.
- The container does not close prototypes or `toInstance` instances. This is a deliberate decision, the same as
  Spring and Guice, and it is documented.
- Cycle detection reports at least one cycle per cyclic part of the graph, not every elementary cycle.
- The binding source location comes from `StackWalker` (frame names only, no reflection). It names the lambda frame
  (`lambda$...`) for lambda modules.
