# Report: Bygge och guardrails (20261001-2308-bygge-och-guardrails)

- **Status:** PASS
- **Mission source:** uppdrag/01-bygge-och-guardrails.md (moved to uppdrag/klara/)
- **Rounds:** 1 of 5

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | PASS (0 blocker, 0 major, 7 minor) | green | 6f8169c |

## What was fixed
- The MAJOR left from the previous run (20261001-2101, EXHAUSTED): `.md` documents are no longer analysed as Java.
  If a document can be read as Java in any encoding at any release, it gets one `UNCHECKABLE_SOURCE` violation.
  The breaker's attack file is now the fixture `docs/release.md`.
- The oldest release is now probed from javac instead of hard-coded (8). Fixture `tree.txt` files are checked too.

## Remaining findings (MINOR, upheld)
- BRK-1 / BRK-2 (MAJOR from the breaker, downgraded by the judge): annotation reads through `javax.lang.model`
  and `java.lang.classfile` are not detected by the extra reflection check. That check is outside the spec,
  and no code reads annotations this way today. Worth revisiting once the DI core arrives.
- BRK-3: checking documents is slow (40 × 16 KB docs took the build from 3.8 s to 30 s).
- AUD-1 / MNT-5: expected line numbers in the fixtures are hard-coded.
- MNT-1, MNT-3, MNT-4: cleanups to structure and comments.

## Assumptions
- The spec from the previous run was reused verbatim (same mission, same criteria).

## Stash
–
