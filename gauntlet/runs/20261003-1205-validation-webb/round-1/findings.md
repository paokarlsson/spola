# Round 1: findings (written by the orchestrator, judge skipped)

VERDICT: PASS
All three critics gave PASS with no BLOCKER or MAJOR, so per the orchestrator rules the judge was skipped.

Counts: 0 blocker, 0 major, 5 minor (not verified by a judge).

## MINOR (left open)
- BRK-1: `WebException.invalid` throws `IllegalArgumentException` (so 500) for a `Violation` code over 100 characters or with a space or control character, which `spola.validation` allows (hand-built `Violation`, long `reject` code). Documented as `@throws` and in PROGRESS "Kända problem".
- AUD-1: `SourceRulesTests` was edited (expected `requires` list), despite criterion 3. The edit is minimal and forced by criterion 2. Spec ambiguity, accepted.
- MNT-1: path rendering is copied into a fourth place (`FormFields`); a comparison test guards the copy against `spola.validation`.
- MNT-2: the `count > 0 → throw failure` check is duplicated in `FormFields.bind`.
- MNT-3: the empty-value rule uses identity `param != Param.STRING`; a custom `Param.of` string parser is treated as non-STRING and `Param` doesn't say so.
