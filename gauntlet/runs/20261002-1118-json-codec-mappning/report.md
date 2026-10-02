# Report: 20261002-1118-json-codec-mappning

- **Status:** PASS
- **Title:** JSON part 2: explicit codec mapping between records and JSON
- **Source:** uppdrag/06b-json-mappning.md (moved to uppdrag/klara/)
- **Rounds:** 2 of max 5

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | FAIL (0 blocker, 1 major, 6 minor) | green | 10f7936 |
| 2 | PASS (0 blocker, 0 major, 4 minor) | green | fcaed2a |

## What was built
New exported package `spola.json.codec` in `spola.json`: codecs as values (no reflection,
no annotations), built-in scalar and java.time codecs with no silent coercion, list/set/map/
optional/nullable/lazy/map combinators, record codecs with 1–16 fields plus a general form,
sealed hierarchies via an exhaustive `switch`, error collection with JSON paths capped at
100 problems, depth limits in both directions, README Jackson comparison and deviation table.

## What was fixed
- BRK-1 (MAJOR, round 1): declared field names over 40 characters were cut in error paths,
  so two fields could share a path. Now rendered in full exactly as `JsonAccessException`.

## Remaining findings (MINOR)
- MNT-4/5: `Path` readability (bare boolean, duplicated identifier rule from `Paths`).
- MNT-6/MNT-1: the 16 constructor arities are kept consistent only by a comment.
- BRK-2: exception messages from app code are quoted up to 200 characters (documented choice).
- BRK-3/BRK-4: `JSON` identity codec has no depth check (Javadoc narrowed); big numbers can
  be written longer than `read()` accepts (documented).
- Data-derived names (unknown members, map keys) over 40 characters with the same prefix can
  share a path.

## Assumptions
- Names/signatures marked "choose and document" in the mission were the builder's choice.
- Criterion 11's javac demonstration is recorded in round-1/build.md.
- The round-2 judge was skipped because all critics gave PASS with no BLOCKER/MAJOR.

## Stash
–
