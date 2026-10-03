# Round 2: build

## Changes
- `modules/security/src/spola/security/InMemoryUserStore.java`: new `public PasswordUpgrade upgrader(PasswordHasher hasher)`. Inside one `computeIfPresent`, it replaces the hash only if the stored hash still `hasher.needsRehash(...)`. Otherwise it keeps the user unchanged. A missing user is a no-op. A malformed stored hash propagates `IllegalArgumentException` and the stored value is kept. Null arguments give NPE. The class Javadoc now separates the roles: `updatePassword` is for password changes and `upgrader` is for upgrades. `updatePassword` is unchanged, as required by criterion 6.
- `PasswordUpgrade.java`: the Javadoc now says the replacement must be conditional and atomic, explains the lost-update case, gives DB and map examples and points to `InMemoryUserStore#upgrader`. The signature is unchanged.
- `Authenticator.java`: the `of(store, hasher, upgrade)` Javadoc now recommends `store.upgrader(hasher)` instead of `store::updatePassword`. The field and the constructor say explicitly that `null` means "no upgrade". The test overload's doc says it rejects `null` like the public overload (MNT-2).
- `Names.java`: the invisible literal characters are now a `breaksOrReorders(char)` helper written with `\uXXXX` escapes and documented. The behaviour is unchanged (MNT-1).
- `Pbkdf2PasswordHasher.java`: the digit limit is now `MAX_ITERATION_DIGITS = Integer.toString(MAX_ITERATIONS).length()` (MNT-3).
- `test/.../LoginExample.java` + `README.md`: the wiring is now `Authenticator.of(users, hasher, users.upgrader(hasher))`. I updated the README text, the comparison table (`upgrader`, `store.upgrader(hasher)`) and the feature list, and added a deviation row for the conditional upgrade.
- `test/.../AuthenticatorTests.java`:
  - The criterion 7 upgrade test now records calls while delegating to `store.upgrader(hasher)`. It still gives exactly one call, and no further call afterwards.
  - New deterministic race test: the upgrade blocks on a latch, the password is changed with `updatePassword` meanwhile, then the latch is released. It asserts that the old-password login is `Authenticated`, the stored hash is the changed one, the new password authenticates and the old password gives `BAD_CREDENTIALS`.
  - New unit test of `upgrader`: it replaces only an old hash and keeps roles and enabled, does not replace a current hash, is a no-op for a missing user, propagates a malformed hash as IAE without the marker and keeps the hash, and throws NPE for null arguments.
- `test/.../SecretTests.java`: the wiring now uses `upgrader`. The upgrader's IAE was added to the marker-checked exceptions.
- `ARCHITECTURE.md` ("Säkerhet del 1"): new bullet "Villkorad uppgradering" (the why, the atomicity, the residual limitation, the requirement on custom stores, the test). The API table now lists `upgrader`.
- `PROGRESS.md`: the delivered list now mentions `upgrader`, the test count is 103, and the residual limitation is listed under known problems.

## Verification
`java Build.java` in `framework/`, final run:
```
    ok      a password change during an upgrade is not undone (InMemoryUserStore.upgrader) (20 ms)
    ok      InMemoryUserStore.upgrader replaces only a hash that needs a rehash, atomically (12 ms)
    ok      no exception provoked by the tests of criteria 4 to 7 carries a marker (7 ms)
spola.security: OK - 103 tests, 103 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 874 ms
BUILD OK in 97277 ms   (exit=0; all other modules OK, guardrails 17/17)
```
Mutation check: I temporarily replaced the condition in `upgrader` with `true` (unconditional, the round-1 behaviour) and rebuilt. Result: `exit=1`, and the race test FAILED with "values differ, expected <changed hash>, actual <rehash of old password>". The upgrader unit test and the marker test also failed. I restored the file and the final green run above was done after the restore.

## Addressed
- BRK-1 (MAJOR): conditional atomic `InMemoryUserStore.upgrader(hasher)`, used in the README, LoginExample, Javadoc and the tests. Added the deterministic block/change/release test, confirmed it fails against the old behaviour, and documented the rule in the `PasswordUpgrade` Javadoc and in ARCHITECTURE "Säkerhet del 1". The `PasswordUpgrade` signature and all public signatures from mission.md are kept. Criterion 7 is still green.
- MNT-1: `\uXXXX` escapes in a named, documented helper.
- MNT-2: the null-means-no-upgrade rule is now stated on the field, the constructor and both overloads.
- MNT-3: the digit limit is derived from `MAX_ITERATIONS`.

## Rebuttals
- None.

## Known gaps
- The upgrade condition is "the stored hash still needs a rehash", not an exact compare-and-set against the hash that was read, because the fixed signature `upgrade(username, newHash)` does not carry it. A password change stored with *old* settings while an upgrade is in flight can therefore still be overwritten. This is documented in the `upgrader` Javadoc, ARCHITECTURE and PROGRESS: password changes should be hashed with the authenticator's hasher.
- `updatePassword` remains unconditional by design (password changes). Wiring `store::updatePassword` as a `PasswordUpgrade` still compiles and would reintroduce the race. The docs steer away from it.
