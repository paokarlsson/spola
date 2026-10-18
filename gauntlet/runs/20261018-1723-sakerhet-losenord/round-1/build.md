# Build, round 1: 15c, security review part 3 (passwords and authentication)

## Changes

Main code (`framework/modules/security/src/spola/security/`):
- `PasswordUpgrade.java`: **L1.** The method is now `upgrade(username, verifiedHash, newHash)`. The Javadoc describes the compare-and-set and shows the SQL `UPDATE users SET hash = ? WHERE username = ? AND hash = ?   -- newHash, username, verifiedHash`. It also says that no updated row is not an error and why ABA is harmless.
- `InMemoryUserStore.java`: **L1.** `upgrader(PasswordHasher)` became `upgrader()`. It replaces the hash only if `user.passwordHash().equals(verifiedHash)`, inside `computeIfPresent` (atomic), and it no longer parses the stored hash. The hasher parameter only existed for the old `needsRehash` condition, which the compare-and-set replaces (see "Deviations").
- `Authenticator.java`:
  - **L1.** It passes the verified hash (`stored = user.passwordHash()`, the same string that went to `matches`) to `upgrade`.
  - **L2.** It catches `IllegalArgumentException` from `matches` for a found user, then does one `matches(password, dummyHash)`, logs `WARNING` `"login refused, the stored password hash is malformed: " + className` (the exception is not attached), and returns `BAD_CREDENTIALS`.
  - **L3.** The Javadoc rules are rewritten: "Unknown users cost the same, within one algorithm and cost", a new "Old hashes can be told apart" bullet, a new "A malformed stored hash is a failed login" bullet, and the upgrade and store-error bullets are updated. The `@throws IllegalArgumentException` is removed.
- `PasswordHasher.java`: **L3.** `delegating` has a new `<h4>Time</h4>` section with the caveat. The data-error bullet says the Authenticator answers `BAD_CREDENTIALS`.
- `Pbkdf2PasswordHasher.java`: **L4.**
  - `MAX_HASH_BYTES` goes from 64 to 32.
  - A new package-private `MAX_STORED_HASH_BYTES = 64` is used only by the parser.
  - `needsRehash` is unchanged. It is already `true` for 33 to 64 bytes because the length differs.
  - The Javadoc explains the two-block cost and the parse bounds.

Tests:
- New `test/spola/security/UpgradeRaceTests.java` (criterion 3), with two tests:
  - The probe's sequence as a parameterized test. A latch holds the upgrade while the password is changed. It runs for three deployments: bcrypt 4→5 (the probe), PBKDF2 20 000→10 000, and `{pbkdf2}`→`{bcrypt}`. Each deployment runs twice, once with the change hashed by the old instance and once by the upgraded one.
  - 200 virtual threads that log in and change the password in turn (four operations each, three runs). Each change reads what the store holds under the same lock, and that must be a hash of the previous change's password. At the end the last password works and none of the others do.
- New `test/spola/security/MalformedLoginTests.java` (criterion 4):
  - The main test runs 9 stored hashes × 3 hashers. The hashes include `{noop}x`, `{sha256}…`, a truncated bcrypt hash and `""`. For an enabled and a disabled user with 4 passwords each, it checks:
    - the result is `BAD_CREDENTIALS`;
    - `matches` is called with the stored hash (which throws) and then with the dummy hash exactly once;
    - there is no `needsRehash` call and no upgrade;
    - exactly the expected WARNING line is logged, with no hash, password, username or marker in it;
    - an unknown user gets only the dummy check and no log line.
  - Further tests: a null password, an IAE subclass (logged by its class name), another RuntimeException (still propagates), and a wrong password on a good hash (no log line).
- New `framework/modules/security-web/test/spola/security/web/StoredHashTests.java` (criterion 4, HttpBasic). Four users have broken hashes (`{noop}x`, `{sha256}`, a truncated `{bcrypt}`, `""`). Each gets the same 401 as an unknown user, compared byte for byte except `Date`, not 500. `{noop}x` with the password `x` also gets 401, and a real user gets 200.
- `HasherTests`:
  - `of(..., 33)` is added to the refused bounds, and the upper bounds now use 32.
  - New test: `of(..., ..., 33/48/64)` gives `IllegalArgumentException("hashBytes must be 16 to 32")`, and stored 33/48/64-byte hashes verify and `needsRehash` is true (criterion 5).
  - New test: a login with upgrade moves a stored 64-byte hash to 32 bytes.
- `AuthenticatorTests`:
  - The new signature throughout.
  - The `upgrader()` unit test now covers the compare-and-set: a different hash of the same password is not replaced, an equal string in a different instance is replaced, and after a replacement the old hash is not replaced again.
  - "a malformed stored hash propagates" became "gives BAD_CREDENTIALS".
- `BCryptAuthenticatorTests`, `LoginExample` (shown in the README): the new signature.
- `SecretTests`, `BCryptSecretTests`: logins with a malformed hash no longer throw, so they are no longer counted among the provoked exceptions. The log lines for malformed hashes are now checked for markers instead.
- `ReadmeTests`: a new test for the SQL and the 15c statements, plus new table-row topics.
- Registration: `SecurityTests` (`UpgradeRaceTests`, `MalformedLoginTests`) and `SecurityWebTests` (`StoredHashTests`, before `ReadmeTests`/`LeakTests`).

Docs:
- `framework/modules/security/README.md`:
  - The bullet list.
  - The paragraph under the example, with the SQL block.
  - Two new bullets in "BCrypt och hashar från Spring": the L3 caveat, and the L2 behaviour.
  - Comparison table: `PasswordUpgrade`, and `userNotFoundEncodedPassword` with the caveat.
  - Deviation table: a new 32-byte row; the rows for malformed hashes and conditional upgrade are rewritten.
- `framework/PROGRESS.md`:
  - A new "Uppdrag 15c" entry.
  - "Återstår" now says 15a–15c are done.
  - The 15a/15b status lines and the stale API mentions in the 10a/12 entries are updated.
  - Known problems: the 10a upgrade paragraph is rewritten, the BCrypt timing bullet now carries the L3 caveat, and there is a new "Säkerhetsgranskningen 2026-10-18 (15c)" block.
- `framework/ARCHITECTURE.md`:
  - A new section "Säkerhetsgranskning del 3", with an API table and decisions for L1–L4 and the tests.
  - In "Säkerhet del 1", the API table, the bounds, malformed hash, unknown users, conditional upgrade and result-as-value decisions now point to it.
  - The BCrypt section's "oförändrade" note is updated.

## Verification

Full build, `cd framework && export PATH=/opt/jdk-25/bin:$PATH && java Build.java`, exit code 0:
```
guardrails: source       541 .java files
guardrails: source       16 other files: 0 not allowed, 16 documents (0 parse as Java)
guardrails: bytecode     1434 .class files
guardrails: dependencies 28 module-info.class files
guardrails: files        2029 files
guardrails: no violations
...
spola.security: OK - 346 tests, 346 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 17664 ms
spola.security.web: OK - 287 tests, 287 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 9054 ms
spola.client: OK - 330 tests, ...
spola.demo: OK - 43 tests, ...
BUILD OK in 267532 ms
exit guard:
  ok - after the decision: exit 0, other hooks and deleteOnExit ran
  ok - before the decision: System.exit(0) ends red (exit 1)
```
All 14 modules are OK. `spola.security` went from 305 to 346 tests: 7 in `UpgradeRaceTests`, 31 in `MalformedLoginTests`, 2 in `HasherTests` and 1 in `ReadmeTests`. `spola.security.web` went from 286 to 287. A second full build after the final PROGRESS wording edits is reported at the end of this file.

New test output (from the build):
```
the upgrade is a compare-and-set on the verified hash
  the probe: ... [1] bcrypt cost 4 to cost 5, the change hashed by the old instance  ok
  ... [6] {pbkdf2} to {bcrypt}, the change hashed by the upgraded instance  ok
  ok 200 virtual threads log in and change the password in turn: the last password always holds (~2.3 s)
a malformed stored hash gives BAD_CREDENTIALS, as an unknown user  (27 cases + 4 tests) ok
a malformed stored hash through HttpBasic
  ok a known user with a malformed or unknown stored hash gets the 401 of an unknown user, not 500
```
The build prints 5 expected `WARNING: login refused, the stored password hash is malformed: java.lang.IllegalArgumentException` lines from `StoredHashTests`. That test goes through the public `Authenticator.of`, which logs to `System.getLogger("spola.security")`, and `spola.security.web` cannot reach the package-private factory that takes a logger. This is documented in PROGRESS, like 15b's warnings.

Mutation checks. The security tests were compiled and run in a scratch directory against `out/spola.test.jar`, with each mutation applied to `framework/` and then restored:

| Mutation | Result |
|---|---|
| `upgrader()` replaces unconditionally (the review's bug) | 10 tests fail: all 6 probe cases, the 200-thread test, the old latch test and the `upgrader()` unit test. The 200-thread test failed in 6 of 6 executions once the per-change invariant was added. With only the end-state check it failed in 2 of 3, which is why the per-change check exists. |
| The Authenticator re-reads the stored hash right before `upgrade` instead of passing the verified one | The 200-thread test fails in 3 of 3 executions. |
| The catch block rethrows (the old behaviour) | 30 security tests fail, and `StoredHashTests` fails with `user 0: 500 {"type":"about:blank","title":"Internal Server Error",...}`. |
| No dummy check in the catch block | 27 tests fail. |
| `MAX_HASH_BYTES` back to 64 | 2 tests fail. |

All sources were restored, checked with grep, before the full build.

## Addressed
Round 1: no findings yet.

## Rebuttals
None.

## Deviations (justified)
- **A test in `framework/modules/security-web`.** Criterion 2 allows changes there "bara om ett anrop till `PasswordUpgrade` måste följa med" (only if a call to `PasswordUpgrade` has to follow). Criterion 4, however, requires: "Genom `HttpBasic` ger samma användare 401, inte 500" (through `HttpBasic` the same user gets 401, not 500). `HttpBasic` lives in `spola.security.web`, and `spola.security`'s tests cannot read that module (`spola.security` has no `requires`, and the build only adds reads to `spola.test`). The only change there is therefore one new test file plus its registration in `SecurityWebTests`. No main source in `spola.security.web` changed. `spola.demo` is untouched.
- **`InMemoryUserStore.upgrader()` without a parameter.** The spec fixes `PasswordUpgrade.upgrade(username, verifiedHash, newHash)` and that the store "byter bara om den lagrade hashen fortfarande är `verifiedHash`" (replaces only if the stored hash is still `verifiedHash`). It says nothing about `upgrader`'s signature. The hasher was used only for the old `needsRehash` condition. With the exact comparison it would be an unused or redundant parameter, because the Authenticator already asked `needsRehash` about the very same string. The spec says "Befintliga anropare och tester uppdateras" (existing callers and tests are updated), and that was done. The motivation is in ARCHITECTURE.md ("upgrader() utan hasher").

## Known gaps
- L3 is documented only. The timing difference between algorithms and costs remains, because evening it out is outside the scope of the spec.
- Each login attempt against a user with an unparsable hash logs one WARNING line, with no deduplication. Someone who knows such a username can produce one line per request, and each request also costs a dummy check. Documented in PROGRESS and ARCHITECTURE; rate limiting is outside the scope.
- Only `IllegalArgumentException` counts as a malformed hash. Another RuntimeException from a custom hasher's `matches` still propagates (500 through HttpBasic), as the spec intends. Documented.
- A user whose hash Spola cannot read cannot log in until the row gets a hash that Spola reads. That is inherent to the fix and documented.
- `PasswordUpgrade` returns `void`, so a custom store cannot report whether the compare-and-set hit. The Authenticator does nothing with that information anyway, as ARCHITECTURE explains.

## Second full build (after the final PROGRESS.md wording edits)
```
guardrails: no violations
spola.security: OK - 346 tests, 346 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 17750 ms
spola.security.web: OK - 287 tests, 287 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 9006 ms
(all 14 modules OK)
BUILD OK in 274559 ms
exit=0
```
