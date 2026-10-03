# Report: 20261003-1237-security-losenord-och-autentisering

**Status:** PASS
**Title:** Security part 1: PBKDF2 password hashing and authentication (`spola.security`, mission 10a)
**Rounds:** 2 of 5

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | FAIL (0 blocker, 1 major, 3 minor) | green | 3e18d5f |
| 2 | PASS (0 blocker, 0 major, 4 minor) | green | ae72594 |

## Delivered
New standalone module `spola.security`:
- PBKDF2-HMAC-SHA256 on `Mac`, tested against the RFC 7914 §11 vectors and the JDK's `SecretKeyFactory`.
- `Pbkdf2PasswordHasher` with a strict, fail-closed PHC format.
- `User`, `InMemoryUserStore` and `Identity`.
- `Authenticator` with a dummy-hash check for unknown users, `DISABLED` only after the right password, and hash upgrades.
- README comparing it with Spring Security.

103 tests. PROGRESS.md and ARCHITECTURE.md are updated.

## Fixed
- BRK-1 (MAJOR, round 1): a hash upgrade could overwrite a password change made at the same time, so the old, leaked password worked again. It is fixed by `InMemoryUserStore.upgrader(hasher)`, which replaces the hash atomically and only while it still needs a rehash. A test covers it.
- Round-1 MINOR: invisible characters in `Names` are now escaped, and the digit limit is derived from `MAX_ITERATIONS`.

## Remaining MINOR
- `store::updatePassword` still compiles as a `PasswordUpgrade`, and using it would bring the race back. Its Javadoc should warn about this.
- The fixed `PasswordUpgrade` signature only allows a "still needs rehash" check, not an exact compare-and-set. This is documented; revisit it in 10b.
- Null handling for `upgrade` is spread over the `Authenticator` factories and constructor.
- `upgrader` is a public addition beyond the API listed in the mission.

## Assumptions
- A null password is checked against the dummy hash using an internal dummy password, so that `matches` is called exactly once.
- The judge was skipped in round 2 because all three critics gave PASS with no BLOCKER/MAJOR.
- The breakers' compiled scratch classes (`scratch-breaker/cls`) were not committed.
- The module is security-critical and should be reviewed by a person (stated in PROGRESS.md).

## Stash
–
