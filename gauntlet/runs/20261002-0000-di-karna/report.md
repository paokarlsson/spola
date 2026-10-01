# Report: 20261002-0000-di-karna

- **Status:** PASS
- **Title:** DI-kärna: explicit dependency injection container (spola.di)
- **Source:** uppdrag/03-di-karna.md (moved to uppdrag/klara/)
- **Rounds:** 1 of 5

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | PASS (0 blocker, 0 major, 7 minor) | green | 0f99e22 |

## Built
New module `spola.di` with:
- type-safe `Key<T>` and value qualifiers;
- typed factory arities (0–5 dependencies) plus a resolver form with declared dependencies;
- reusable modules;
- scopes: lazy/eager singleton, prototype and a `ScopedValue` call scope;
- reverse-order closing that collects errors;
- build-time validation of missing dependencies, duplicates, cycles with the full chain, and singletons that capture call-scoped components.

It has no reflection, and all 48 tests pass. The README compares it with Spring.

The judge was skipped because all three critics gave PASS with no BLOCKER or MAJOR findings.

## Remaining findings (MINOR)
- BRK-1: The ScopedValue guard can be bypassed from non-structured threads, and ARCHITECTURE.md overstates it.
- BRK-2: Errors thrown by factories are not wrapped with the dependency chain.
- BRK-3: Recursive resolution and validation overflow the stack for chains about 1000 deep.
- AUD-1: An undeclared `r.get` in the resolver form is only caught at first creation.
- MNT-1/2/3: Small duplications (type hint, singleton-scope check, overload Javadoc).

## Assumptions
"Main code" means non-test sources. I removed the breaker's compiled scratch classes (`scratch-breaker/cls`) before committing, and kept its sources.

## Stash
–
