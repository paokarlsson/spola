# Round 1: build

## Changes
- `framework/Build.java`: one new entry in `MODULES` (`spola.security`, test entry `spola.security.SecurityTests`). Nothing else changed.
- New module `framework/modules/security/`:
  - `src/module-info.java`: no `requires` at all, `exports spola.security`, with Javadoc on the module and its standalone status.
  - `src/spola/security/`: `Pbkdf2` (RFC 8018 §5.2 on `Mac`, with a private destroyable `SecretKey` so the key copy is cleared), `PasswordHasher`, `Pbkdf2PasswordHasher` (defaults/of, UTF-8 with `REPORT`, a length check before encoding, a strict PHC parser, `MessageDigest.isEqual`, bytes zeroed), `User`, `UserStore`, `InMemoryUserStore`, `Identity`, `AuthResult` (`Authenticated`/`Failed`), `Failure`, `PasswordUpgrade`, `Authenticator` (dummy hash, `DISABLED` only after the right password, upgrade with a `RuntimeException` logged by class name only), and the internal `Names` (username/role rules plus escaping for `toString`).
  - `test/spola/security/`: `SecurityTests` (entry), `Pbkdf2Tests` (crit. 3), `HasherTests` (4), `MalformedHashTests` (5, 44 cases), `UserTests` (6), `AuthenticatorTests` (7), `ConcurrencyTests` (8), `SecretTests` (9), `LoginExample` + `ReadmeTests` (10), `SourceRulesTests` (2), `Support` (fast hasher, counting hasher, recording `System.Logger`).
  - `README.md`: the Spring Security example next to `LoginExample.java` (verbatim and checked by a test), a comparison table and a deviation table.
- `framework/PROGRESS.md`: a 10a section, "rad 10 fortsätter i 10b", known limitations, and the requirement for a human review.
- `framework/ARCHITECTURE.md`: a new section "Säkerhet del 1: …" with the API table and every decision required by criterion 12.

## Verification
`cd framework && java Build.java` -> exit 0:
```
guardrails: source       13 other files: 0 not allowed, 13 documents (0 parse as Java)
guardrails: no violations
...
spola.validation: OK - 251 tests, 251 ok, 0 failed
spola.web: OK - 730 tests, 730 ok, 0 failed
spola.security: OK - 101 tests, 101 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 850 ms
spola.demo: OK - 43 tests, 43 ok, 0 failed
BUILD OK in 94164 ms
```
The slowest security tests: RFC vector c=80000 (43 ms), 60 cases against SecretKeyFactory (119 ms), and `defaults()` hash + verify (328 ms).
`git diff --stat`: only `ARCHITECTURE.md`, `Build.java` (+2 lines, one `MODULES` entry) and `PROGRESS.md`; everything else is new under `modules/security/`.

During development the build went red once on my own test data: a "non-canonical" marker salt (`HASHMARKERAB`) was in fact canonical and 9 bytes long. I changed it to `HASHMARKERAAAB`.

## Addressed
Round 1, so there were no findings to address.

## Rebuttals
None.

## Known gaps / judgement calls
- `Pbkdf2.deriveSha256` also throws `IllegalArgumentException` for a `null` password or salt (mission: "annars IllegalArgumentException"), and it treats an empty salt as invalid too (JDK's `PBEKeySpec` rejects it as well).
- With a `null` password, `Authenticator` calls `matches` once with its internal dummy password against the dummy hash, because `matches(null, …)` must throw NPE. The result is always `BAD_CREDENTIALS`.
- The upgrade's `try` covers `hasher.hash(password)` as well as `upgrade.upgrade(...)`. `needsRehash` stays outside the `try`, so a data error there would propagate. In practice it cannot happen because `matches` has already parsed the same hash.
- `Identity` uses the username of the `User` the store returned, not the argument as given. The two are the same for `InMemoryUserStore`.
- A password that `matches` refuses (empty, too long, lone surrogate) returns `false` without running PBKDF2. That is faster, but it is the same for known and unknown users. Documented in PROGRESS.
- `User`/`Identity` roles are kept as an unmodifiable sorted set, which gives a deterministic `toString`.
- The `toString` of usernames escapes control, bidi and lone-surrogate characters. The upgrade log line escapes the class name the same way.
