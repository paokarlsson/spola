# Round 1 findings

VERDICT: PASS

The judge step was skipped: all three critics (breaker, auditor, maintainer) gave PASS with no BLOCKER or
MAJOR. The MINOR findings below are carried over as reported, without a ruling.

Counts: 0 blocker, 0 major, 11 minor (reported, not judged; BRK-2 = MNT-5 in part).

## MINOR
- BRK-1: the L3 caveat says old-algorithm users are distinguishable "until they have logged in once"; a
  {pbkdf2} user with a password bcrypt refuses (>72 bytes or U+0000) is never upgraded and stays
  distinguishable for good. Add that case to Authenticator, delegating, README and PROGRESS.
- BRK-2 / MNT-5: ARCHITECTURE:2394 still says `store.upgrader(hasher)`; the 15c test list repeats PROGRESS.
- AUD-1: a test file in security-web for criterion 4 (spec ambiguity; reasonable).
- AUD-2: `upgrader()` lost its hasher parameter (spec ambiguity; reasoned).
- AUD-3: 5 expected WARNING lines in the build output (documented).
- MNT-1: the dummy check is copied in three failure paths; extract `refuse(...)`.
- MNT-2: `authenticate` @throws does not mention other RuntimeExceptions from the hasher.
- MNT-3: the SQL placeholder order differs from the method parameters; show the binding.
- MNT-4: shared and stored-only PBKDF2 bound constants are mixed.
- MNT-6: BAD/ADA repeated in test classes, unexplained offsets, a long inline probe lambda.

Critic reports: critic-breaker.md, critic-auditor.md, critic-maintainer.md.
