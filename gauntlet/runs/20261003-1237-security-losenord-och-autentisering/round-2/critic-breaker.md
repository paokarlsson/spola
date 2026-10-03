```
VERDICT: PASS
FINDINGS: none
```

I found no BLOCKER or MAJOR defects in round 2. The fix for last round's lost-update finding holds up.

- **Build:** `java Build.java` in `/workspace/framework/` exits with 0. `spola.security` passes its 103 tests, guardrails are green and no test fails.
- **Scope:** `git diff` shows changes only under `modules/security/` plus `ARCHITECTURE.md` and `PROGRESS.md`. No other module's `src/` or `test/` changed, and `Build.java` is unchanged in this round.
- **Fix for last round's finding:** I reran the round-1 experiment with the new recommended wiring, `store.upgrader(hasher)` instead of `store::updatePassword`. A login with the old password starts a 600k-iteration rehash, and 50 ms later the password is changed. In 3 of 3 runs:
  - the attacker's login: `Authenticated`
  - the new password: `Authenticated`
  - the old password: `Failed[BAD_CREDENTIALS]`

  So the password change is no longer undone. Last round the same experiment brought the old password back.
- **`upgrader` code:** the check and the replacement happen in one atomic step. It keeps the user's current roles and enabled flag, does nothing for a user that no longer exists, and rejects `null` arguments. If two logins upgrade at once, the second does nothing. A malformed stored hash in the upgrade path is caught and logged by the authenticator.
- **Remaining gap:** the upgrade can still overwrite a password change if that change was hashed with the old settings while the upgrade is running. The fixed `PasswordUpgrade` signature doesn't pass along the hash that was checked, so an exact comparison isn't possible. The limitation is documented in the `upgrader` Javadoc, in "Säkerhet del 1" in `ARCHITECTURE.md` and in `PROGRESS.md`. It only happens if the developer hashes password changes with a different hasher than the docs tell them to, so I didn't count it as MAJOR.
- **Docs:** `README.md`, `LoginExample.java`, the Javadoc on `Authenticator` and `PasswordUpgrade`, `ARCHITECTURE.md` and `PROGRESS.md` all use the new wiring and describe it consistently. The test count in `PROGRESS.md` is 103.
- **Small cleanups:** I checked the code; behaviour is unchanged.
  - The invisible characters in `Names` are now written as `\uXXXX` escapes.
  - The iteration digit limit is derived from `MAX_ITERATIONS`, so the overflow guard still holds.

Files are in `/workspace/gauntlet/runs/20261003-1237-security-losenord-och-autentisering/round-2/scratch-breaker/`:
- `Exp.java` (the experiment)
- `cls/` (compiled classes)
