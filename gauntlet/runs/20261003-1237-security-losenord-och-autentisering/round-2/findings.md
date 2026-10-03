# Round 2 — findings

VERDICT: PASS (0 blocker, 0 major, 4 minor)

Judge step skipped: all three critics gave PASS with no BLOCKER/MAJOR (orchestrator rule 3b).
The round-1 MAJOR (BRK-1, lost update on hash upgrade) is fixed. The breaker reran the
experiment with `store.upgrader(hasher)` and the password change survived in 3 of 3 runs.

## Remaining MINOR (not judged; carried over as reported)
- MNT-1: `store::updatePassword` still compiles as a `PasswordUpgrade` and would bring the race back. Add a warning to its Javadoc.
- MNT-2: null handling for `upgrade` is spread over the `Authenticator` factories and constructor.
- AUD-1: the public `InMemoryUserStore.upgrader` goes beyond the API listed in the mission. It is additive and justified by BRK-1.
- AUD-2: the fixed `PasswordUpgrade` signature only allows a "still needs rehash" check, not an exact compare-and-set. This is documented; revisit in 10b.
