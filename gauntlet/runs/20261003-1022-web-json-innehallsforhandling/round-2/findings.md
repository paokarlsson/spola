# Round 2: findings

VERDICT: PASS (written by the orchestrator: all three critics PASS, no BLOCKER/MAJOR, so the judge was skipped)

Counts upheld: 0 blocker, 0 major, 4 minor

## Upheld (MINOR)
- MNT-1: the charset=utf-8 exception in `MediaType.includes` uses inline literals; extract a helper or constants.
- MNT-2: the "utf-8" literal is duplicated in `JsonBody.checkContentType` and `MediaType.includes`; share one constant or helper.
- MNT-3: `MediaTypeParser.parse` is still long (carried over from round 1, deliberately deferred).
- AUD-1: the JSON-only UTF-8 exception in `includes` goes beyond the literal spec wording (spec-ambiguity; documented and reasonable).

## Round 1 MAJOR resolved
- BRK-1 (`Accept: application/json; charset=utf-8` gave 406): fixed, verified over HTTP by the breaker.
