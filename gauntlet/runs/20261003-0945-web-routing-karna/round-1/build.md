# Round 1 — build

## Changes
- `framework/Build.java`: `spola.web` with test entry `spola.web.WebTests::suite` added to `MODULES`.
- `framework/modules/web/src/module-info.java`: `requires transitive spola.http`, `requires transitive spola.json`, `requires static java.net.http` (tests only), `exports spola.web`.
- Public API (`spola.web`): `Router`, `RouterBuilder`, `RouteGroup`, `WebHandler`, `WebRequest`, `Param`, `AttributeKey`, `Filter` (+`Chain`), `ExceptionMapper`, `WebException`, `Problem`, `RouterDefinitionException`. All Javadoc'd (doclint passes).
- Internal: `PathPattern` (pattern parsing, sealed segments), `Route`, `RouteTable` (trie, literal > variable > catch-all with backtracking, collects `Allow`), `Decoding` (strict percent-decoding via JDK UTF-8 decoder with REPORT, path check, form-urlencoded query), `QueryState` (lazy, at most one parse, failure cached), `Text` (escape/truncate), `LoggedThrowable` (escaped copy of the cause chain).
- Tests (`modules/web/test/spola/web/`, 288 tests): `DecodingTests`, `ClientTests` (c3), `MatchingTests` (c4), `StatusTests` (c5), `DefinitionTests` (c6), `PathTests` (c7), `QueryTests` (c8), `ParamTests` (c9), `FilterTests` (c10), `ErrorTests` (c11), `ProblemTests` (c12), `LogTests` (c13), `ScaleTests` (c14), `SourceRulesTests` (c2), `Support`.
- `framework/modules/web/README.md` (c15): full example next to Spring Boot (the Spola block was extracted and compiled against `out/` with javac: compiles), deviation table.
- `framework/ARCHITECTURE.md`: new section "Web del 1" (split 08a/08b, dependencies, pattern syntax, trie and matching order, strict path, query rules/limit, filter scope/order, attributes, mapper selection, problem format, logging) + README line in the directory structure.
- `framework/PROGRESS.md`: 08a section, "Återstår" now 08b + 09–20, known limitations for `spola.web`, the stale "no routing (08)" note in spola.http updated.
- No changes in `spola.http` (07a/07b tests unchanged: 283 ok).

## Verification
`cd framework && java Build.java` → exit 0:
```
guardrails: no violations
spola.http: OK - 283 tests, 283 ok, 0 failed, ...
spola.web: OK - 288 tests, 288 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 1408 ms
spola.demo: OK - 43 tests, 43 ok ...
BUILD OK in 89985 ms
```
Parameterized case counts (from the run output): matching 32 cases x 2 registration orders (+ the backtracking example alone), invalid registrations 22, malformed paths 36, query cases 17, valid typed values 22, invalid typed values 39, UTF-8 decoding vectors 36.
README example: `javac -p framework/out --add-modules spola.web ShopApp.java` → compiles (one `serial` lint warning about the example's exception class, irrelevant for a doc snippet).

## Addressed
Round 1: no findings yet.

## Rebuttals
None.

## Design decisions worth a critic's attention (documented in ARCHITECTURE.md)
- **`requires transitive spola.json`** instead of the literal `requires spola.json`: `Problem` exposes `JsonObject` in its public API, and `-Xlint:all -Werror` (lint `exports`) fails the build without `transitive`. It is still a `requires` of `spola.json` and no other framework module is required (tested in `SourceRulesTests`).
- Group lambda receives `RouteGroup` (no `onException`/`build`), so misuse is a compile error.
- Routing is decided before filters; exceptions are mapped at the step where they occur, so outer filters see (and can decorate) error responses.
- `RequestBodyException` only goes to a mapper for exactly that class (an `Exception` mapper must not turn 413 into 500); without one, it (or one in the cause chain) is rethrown to `spola.http`.
- Mappers for `Error` classes are rejected at `build()`; `Error`s are not caught.
- Query parsing is lazy: an invalid query that nothing reads gives no 400.
- For a request that fails the path check, filters see the raw path in `path()`.
- Pattern `/` inside a group is the prefix itself; patterns may end with `/` (strict match); `*`/`?` rejected as wildcards.
- `Param.enumOf(Class)` calls `Class.getEnumConstants()` once at creation (the shape in the mission); no per-request reflection.

## Known gaps
- 404/405 under a group's prefix do not run the group's filters (no group route matched) — by design, documented.
- C1 control characters (U+0080–U+009F) are allowed in decoded path segments (the mission lists only U+0000–U+001F and U+007F); they are escaped in every log line.
- No benchmark showing lookup cost is independent of route count; the claim rests on the trie structure (HashMap per literal level, no linear scan) and the 1 000-route correctness test.
