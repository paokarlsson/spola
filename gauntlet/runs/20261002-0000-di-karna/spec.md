# Spec: DI-kärna (spola.di)

## Task
Build a dependency injection container replacing Spring Core in a new module `spola.di`.
Components are registered and wired explicitly in Java code, without annotations, scanning
or reflection. Wiring errors (missing dependencies, cycles) are detected when the container
is built, not on first use. Full mission text: `mission.md` in this run directory.

## Ground rules
Everything in `framework/REGLER.md` applies. A violation is a BLOCKER.

## Work product
`framework/` (new module under `framework/modules/di/`, plus updates to `PROGRESS.md` and `ARCHITECTURE.md`).

## Verification command
`java Build.java` in `framework/` — must exit 0.

## Requirements (from mission.md)
- New module `spola.di` under `framework/modules/di/`, with no dependencies on other framework
  modules except `spola.test` (tests only).
- Type-safe keys (`Key<T>`). Multiple implementations of the same type are distinguished by
  qualifiers that are plain values, not annotations.
- Registration via factory functions that receive a resolver, e.g.
  `bind(OrderService.class, r -> new OrderService(r.get(OrderRepo.class)))`.
- Modules: registrations can be grouped in reusable units that are installed into the container.
- Scopes: singleton (lazy and eager), prototype, and a call scope built on `ScopedValue`.
- Lifecycle: components implementing `AutoCloseable` are closed when the container closes, in
  reverse creation order. Errors on close are collected and reported, and do not stop closing
  the others.
- Build-time validation detects missing dependencies and cycles, and the error shows the full
  dependency chain (e.g. `A -> B -> C -> A`).
- For validation to work without reflection, every registration declares its dependencies.
  Choose and justify in `ARCHITECTURE.md` how this is done so that it is hard to declare wrongly.
- Thread-safe resolution of singletons, including with virtual threads.
- No use of `java.lang.reflect` or `MethodHandles` in the module's main code.

## Acceptance criteria
1. `java Build.java` in `framework/` exits with code 0.
2. The module's main code imports nothing from `java.lang.reflect` or `java.lang.invoke`
   (checkable by searching the source; fully-qualified uses count too).
3. A test shows that a cycle `A -> B -> C -> A` fails when the container is built, with the full
   chain in the error message.
4. A test shows that a missing dependency fails when the container is built, with who is missing
   what in the error message.
5. A test shows two implementations of the same type retrieved with different qualifiers.
6. A test shows that a lazy singleton is created exactly once even when 1 000 virtual threads
   fetch it concurrently.
7. A test shows that prototype gives a new instance per fetch, and that the call scope gives the
   same instance within one scope but different instances across scopes.
8. A test shows closing in reverse creation order, and that an exception while closing one
   component does not prevent closing the others.
9. `README.md` in the module shows an example next to the corresponding Spring code using
   `@Component`, `@Autowired` and `@Qualifier`.
10. No annotations beyond the allow-list, no dependencies beyond the JDK (guardrails green).
11. `PROGRESS.md` and `ARCHITECTURE.md` are updated.

## Out of scope
Configuration/properties, AOP/proxies/interceptors, integration with web/data/security, code generation.

## Assumptions
- "Main code" means the module's non-test sources.
- The tests for criteria 3–8 run as part of `java Build.java`.
