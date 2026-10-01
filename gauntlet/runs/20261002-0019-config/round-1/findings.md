# Findings — round 1

VERDICT: PASS
COUNTS: 0 blocker, 0 major, 7 minor

All three critics returned PASS with no BLOCKER or MAJOR findings; judge step skipped per orchestrator rules.

## Upheld (MINOR, not verified by judge)
- BRK-1: Deep placeholder chains (≥~2000 links) crash build() with StackOverflowError instead of a ConfigException. Cap recursion depth.
- AUD-1: `Converter.forEnum` uses `Class.getSimpleName()` (documented; not java.lang.reflect).
- MNT-1: `Placeholders` signals failure via identity comparison to a sentinel `FAILED` record.
- MNT-2: `Config.Builder.build()` does too much in one method.
- MNT-3: "Swallow binder exception if problems recorded" rule duplicated in `Config.bind` and `Section.section` with different outcomes.
- MNT-4: `java.net.URI` fully qualified in `Section.uri` signature.
- MNT-5: Argument-without-`=` error echoes the argument name (key only, no value); masking policy scope undocumented.
