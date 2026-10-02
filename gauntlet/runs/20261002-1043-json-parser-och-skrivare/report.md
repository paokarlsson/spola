# Report: 20261002-1043-json-parser-och-skrivare

- **Status:** PASS
- **Title:** JSON part 1: tree model, strict parser and writer (spola.json)
- **Source:** uppdrag/06a-json-parser-och-skrivare.md (moved to uppdrag/klara/)
- **Rounds:** 1 of max 5

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | PASS (0 blocker, 0 major, 6 minor) | green | 6d96cf9 |

## What was built
New module `spola.json`: a sealed, immutable `JsonValue` tree with value semantics; `JsonNumber`
keeps exact text with numeric equality and DoS-safe exact conversions; a strict non-recursive
RFC 8259 parser for String, byte[], InputStream and Reader with `JsonLimits`, strict UTF-8, BOM
skipping, duplicate-key rejection and positioned, log-safe errors; a compact and pretty writer
that also escapes U+2028, U+2029 and U+007F; path-reporting convenience readers; README with
Jackson comparison. 287 tests; the breaker fuzzed 300,000 mutated documents without a failure.

All critics returned PASS without BLOCKER/MAJOR, so the judge step was skipped.

## Remaining findings (MINOR)
- BRK-1: README deviation table shows raw U+2028/U+2029 instead of the text ` `/` `.
- AUD-1: RFC reference per conformance case is a `rule` field, not a code comment.
- MNT-1: `Parser.string()` is a long loop mixing escapes, surrogate state and the length limit.
- MNT-2: path-carrying copy logic duplicated between JsonObject and JsonArray.
- MNT-3: `JsonNumber.equals` recomputes the canonical form each call.
- MNT-4: boilerplate `JsonObject.of(...)` overloads.
- Builder's known gaps: pretty output grows quadratically with nesting depth; a parsed tree can use ~25× the input size (~110 MB at the 4 MiB default); C1 controls (U+0080–U+009F) are not escaped.

## Assumptions
- Spec criterion 8 example sharpened to `"a"` vs `"a"` (the escape was lost in the mission text).

## Stash
–
