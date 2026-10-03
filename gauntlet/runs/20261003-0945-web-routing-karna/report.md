# Report: 20261003-0945-web-routing-karna

- **Status:** PASS
- **Title:** Web part 1: routing as code, parameters, filters and error handling
- **Source:** uppdrag/08a-web-routing-karna.md (moved to uppdrag/klara/)
- **Rounds:** 1 of 5

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | PASS (0 blocker, 0 major, 8 minor) | green | 0e6c386 |

## What was built
The new module `spola.web` on top of `spola.http` contains:
- an immutable, thread-safe `Router` with a segment trie (literal > variable > catch-all, with backtracking, independent of registration order);
- strict percent-decoding of the path, with 400 before routing;
- lazy query parsing capped at 1 000 pairs;
- strict typed `Param<T>` values and typed `AttributeKey<T>` attributes;
- router and group filters;
- closest-class exception mapping into RFC 9457 `application/problem+json` responses.

`build()` reports every definition problem in one exception. The module has 288 tests, and the `spola.http` tests are unchanged and green.

## Remaining findings (MINOR)
- BRK-1: An explicit HEAD route with a less specific pattern loses to HEAD-via-GET on a more specific GET route. Explicit OPTIONS behaves differently.
- BRK-2: `requiredQuery(name, STRING)` accepts an empty value.
- BRK-3: The `RouterDefinitionException.problems()` Javadoc about ordering is wrong, because group-prefix problems are listed first.
- AUD-1: `requires transitive spola.json`, which `-Werror` forces because `Problem` exposes `JsonObject`. It is documented.
- MNT-1..4: Readability items: a long private `route(...)` in `RouterBuilder`, duplicated `WebRequest` construction in `Router.handle`, inline FQNs, and `Scope`'s mutability.

## Assumptions
- The judge was skipped because all critics gave PASS with no BLOCKER/MAJOR.
- The auditor checked several criteria by test name and build.md rather than reading the test cases.

## Stash
–
