# Report — 20261001-2101-bygge-och-guardrails

**Status:** EXHAUSTED (5/5 rounds, last verdict FAIL with 0 blocker, 1 major)
**Title:** Bygge och guardrails
**Mission:** uppdrag/01-bygge-och-guardrails.md (stays in uppdrag/, not moved to klara/)

## Rounds
| Round | Verdict | Upheld B/M/m | Build | Commit |
|---|---|---|---|---|
| 1 | FAIL | 0 / 3 / 10 | green | 57091c7 |
| 2 | FAIL | 0 / 2 / 5 | green | c8d7836 |
| 3 | FAIL | 0 / 1 / 6 | green | 4452cd7 |
| 4 | FAIL | 0 / 3 / 5 | green | 58db3ab |
| 5 | FAIL | 0 / 1 / 4 | green | ad09875 |

The auditor marked every acceptance criterion (1–10) MET from round 1 on. Every MAJOR came from the breaker, and each was a new way to get past the guardrails.

## What was built and fixed
- `framework/Build.java` uses only the JDK. It compiles the JPMS modules in a fixed order, packages one jar per module, and runs `spola.guardrails`.
- `spola.guardrails` has five checks:
  - the source check (Tree API)
  - the bytecode check (`java.lang.classfile`)
  - the dependency check
  - the file check, which catches jars by their content, build-tool files, and symlinks
  - LauncherCheck, which reads `.md` files and fails closed on every other file that is not `.java`.
- A self-test verifies 10 fixtures with exact expected violations.
- Bypasses closed along the way:
  - symlinked jars and sources (round 1)
  - `getParameterAnnotations` and other annotation reads (round 1)
  - files placed directly in the fixtures root (round 2)
  - renamed jars and `.class` files (round 2)
  - shebang scripts without a `.java` extension (round 3)
  - CR-only shebang lines, non-UTF-8 files, and code that only parses at an older release (round 4)

## Remaining (blocking)
- **BRK-1 [MAJOR] (round 5):** a `.md` file that javac can parse as Java is analysed at only one release, against the JDK 25 API. Run with `java --source 8`, the same file binds to different methods, so a reflective annotation read gets through.
  - The judge's binding ruling: any `.md` that parses as Java, in any reading and at any release, is reported as `UNCHECKABLE_SOURCE` and is not analysed. The fix is small, but the run had no rounds left.

## Remaining MINOR
- Fixture content is not pinned beyond each fixture's expected violations, so a `tree.txt` or a launcher input can hide runnable Java. Per the judge, review covers this and it belongs in the known gaps.
- Cleanups in LauncherCheck: `run` is too long, readings are scanned again for every document, and `OLDEST_RELEASE` is not verified.
- Expected line numbers in `Fixtures.ALL` are hard-coded.
- Known gaps: indirect reflection through MethodHandles or string lookup, and jshell snippets inside `.md` files.

## Binding rulings from the judge (for later rounds and missions)
- **Round 3:** the machine check must catch anything the JDK runs or loads directly. Anything that must be unpacked or decoded first (gzip, tar, base64) is out of scope and is enforced by review.
- **Round 4:** close the remaining gaps by failing closed, not by adding special cases.
- **Round 5:** documents are never analysed as Java. Any `.md` that parses as Java is `UNCHECKABLE_SOURCE`. Fixture content is pinned only through its expected violations.

## Assumptions and notes
- I resumed this run at step 3b of round 1. The auditor and maintainer reports were already saved and newer than build.md, so I kept them. The breaker report was missing, so I ran the breaker again.
- `max_rounds` was 5 (from mission.md).
- The breakers' `scratch-*` directories are gitignored and were left in place.

## Stash
None. Every round was green and committed, so `framework/` has no unfinished changes to park.
