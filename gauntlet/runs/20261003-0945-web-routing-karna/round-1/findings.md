# Round 1 findings

Verdict: **PASS** (written by the orchestrator: all three critics PASS with no BLOCKER/MAJOR, judge skipped per protocol)

Counts upheld: 0 blocker, 0 major, 8 minor

## MINOR (not blocking)
- BRK-1: An explicit HEAD route with a less specific pattern loses to HEAD-via-GET on a more specific GET route. OPTIONS behaves differently. Document it or make them consistent.
- BRK-2: `requiredQuery(name, STRING)` accepts an empty value (`?s=`). Document that an empty value counts as present, or reject it.
- BRK-3: The `RouterDefinitionException.problems()` Javadoc claims registration order, but group-prefix problems are listed first.
- AUD-1: `requires transitive spola.json` instead of a plain `requires`. Forced by `-Werror` because `Problem` exposes `JsonObject`, and documented (spec ambiguity).
- MNT-1: Private `RouterBuilder.route(...)` is long and shares its name with the public `route`. Rename and split it.
- MNT-2: `Router.handle` builds `WebRequest` and the throwing terminal in several duplicated places. Extract helpers.
- MNT-3: Fully qualified names inline instead of imports.
- MNT-4: `Scope`'s mutable fields and its open/closed invariant are spread across the builder.
