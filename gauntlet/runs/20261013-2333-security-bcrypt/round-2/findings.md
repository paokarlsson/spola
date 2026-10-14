# Round 2 findings

VERDICT: PASS

The judge step was skipped: all three critics gave PASS with no BLOCKER or MAJOR. The breaker and
the auditor reported no findings; the maintainer reported five MINOR ones, carried over without a ruling.
Round 1's upheld MAJOR (BRK-1, timing oracle when the stored bcrypt cost is higher than the hasher's)
is fixed in documentation, as the judge asked; the auditor and breaker confirmed it.

Counts: 0 blocker, 0 major, 5 minor (reported, not judged).

## MINOR
- MNT-9: `withoutBcrypt` lives in BCryptHasherTests instead of Support; two `TAIL` constants with different values.
- MNT-10: `Blowfish.keyWords(byte[], boolean)` takes a boolean flag.
- MNT-11: the "malformed stored password hash: " prefix is copied into three classes.
- MNT-12: the stored-hash layout and allowed versions are written in BCrypt and read in BCryptPasswordHasher.
- MNT-13: the round-count source check depends on local variable names.

Critic reports: critic-breaker.md, critic-auditor.md, critic-maintainer.md.
