# Build, round 1: BCrypt and a delegating {id} password hasher (spola.security)

## Changes

Main code (`framework/modules/security/src/spola/security`):
- `BCryptPasswordHasher.java` (new, public, final):
  - `defaults()` (cost 10) and `of(cost)` (4–31, otherwise IAE `cost must be 4 to 31`).
  - Public constants `MIN_COST`, `MAX_COST`, `DEFAULT_COST`, `MAX_PASSWORD_BYTES`.
  - `hash`, `matches`, `needsRehash`. `toString()` is `BCryptPasswordHasher[cost=N]`.
  - Password rules: null gives NPE. Empty or more than 72 `char`s is refused before encoding, and
    so is any U+0000. Then UTF-8 with `REPORT`, refused if more than 72 bytes. A refused password
    gives an IAE without the password in `hash` and `false` in `matches`, and no bcrypt is
    computed. Bytes are cleared.
  - Strict parser (`Stored.parse`), checks in this order: exactly 60 chars, `$2a$`/`$2b$`/`$2y$`,
    two ASCII digits 04–31 followed by `$`, then canonical salt and hash. Messages are fixed texts
    with the same `malformed stored password hash: ` prefix as PBKDF2. `matches` parses before it
    looks at the password.
  - Compares with `MessageDigest.isEqual`. Salt from a `new SecureRandom()` per hasher.
    `needsRehash` is `stored cost < cost`.
  - Class Javadoc covers format, parsing, the 72-byte/U+0000 rules with CVE-2025-22228, costs
    (cost 31 means days), and `needsRehash`.
- `BCrypt.java` (new, package-private): the primitive.
  - `hash(byte[] password, byte[] salt, int cost)` takes any length, including 0 and over 72, and
    returns 23 bytes.
  - `format(char version, int cost, byte[] salt, byte[] password)` builds the full 60-char
    string. `BCryptPasswordHasher.hash` and the vector tests both go through `format`.
  - `rounds(cost)` returns `1L << cost`. The loop is `for (long round = 0; round < rounds; ...)`.
  - bcrypt Base64: `encode`, and a strict canonical `decode` that returns null.
  - Key, salt words, state, blocks and output are cleared.
- `Blowfish.java` (new, package-private): a mutable state (P[18], S[1024]) holding:
  - `keyWords` (cyclic key, optional NUL terminator, unsigned bytes);
  - `expand(keyWords, saltWords|null)` (bcrypt's ExpandKey, and Blowfish's ordinary key schedule
    when salt is null);
  - `encrypt` and `clear`;
  - `initialP()`/`initialS()`, which return copies for the tests.
  - The tables are derived from π in the nested holder `Pi`: Machin's formula, `BigInteger`
    fixed point, 64 guard bits, once per JVM on first use. There is no constant table in the
    source.
- `DelegatingPasswordHasher.java` (new, package-private): the `{id}` implementation.
  - One-pass copy of the map, then validation: NPE for null id or hasher; IAE for an empty map,
    a bad id (1–100 printable ASCII 0x21–0x7E, no braces) or a default id that isn't a key.
    Messages name the problem.
  - Looks for `}` at most 101 chars in, with an exact, case-sensitive id.
  - Delegate IAEs pass through unchanged. `needsRehash` always asks the delegate.
  - `toString()` is `DelegatingPasswordHasher[default=..., ids=[sorted]]`.
  - Parse messages are fixed texts that contain neither the string, nor the text between the
    braces, nor even the word "id".
- `PasswordHasher.java`: the new `static PasswordHasher delegating(String, Map<String, ? extends
  PasswordHasher>)`. Its Javadoc documents the whole behaviour, and the interface Javadoc names
  the new implementations.
- `module-info.java`: Javadoc only (BCrypt and delegating bullets). `requires`/`exports` are
  unchanged (no `requires`, `exports spola.security`).

Tests (`framework/modules/security/test/spola/security`):
- New files:
  - `BlowfishTests`: crit. 6. Also the round-count helper and "primitive uses it" from crit. 7,
    and source rules from crit. 2/16: no `Cipher` or reflection in main code, no known Blowfish
    constants, no numeric table, Machin's formula present.
  - `BCryptVectorTests`: crit. 4 and 5. All 20 jBCrypt vectors including cost 12, all Openwall
    vectors plus the `$2y$` variant of `ff ff a3`, 10 libxcrypt vectors and the 3 use-case vectors.
  - `BCryptHasherTests`: crit. 7, 8 and 10. Refused passwords are also checked against a stored
    cost-31 hash and `of(31).hash`, which proves no bcrypt is computed (10 s timeout).
  - `BCryptMalformedTests`: crit. 9, 44 cases.
  - `DelegatingTests`: crit. 11.
  - `BCryptAuthenticatorTests`: crit. 12 and 13.
  - `BCryptSecretTests`: crit. 14.
  - `BCryptExample`: the README example, which is the need's code.
  - `BCryptExampleTests`: crit. 3, and that the README shows `BCryptExample.java` unchanged.
- `SecurityTests`: registers the 8 new suites.
- `SourceRulesTests`: `13` becomes `17`, and it checks that `BCryptPasswordHasher` contains
  `MessageDigest.isEqual(actual, stored.hash())` and `new SecureRandom()`. The forbidden list is
  unchanged and applies to the new files.
- `ReadmeTests`: the topic `Bara PBKDF2-SHA256` is replaced by `Ingen SCrypt, Argon2`. New topics
  cover the new deviation rows. A new check asserts the old row is gone, and the three new
  comparison rows are checked (crit. 15). The other expectations are unchanged.

Docs:
- `modules/security/README.md`:
  - Intro and bullets mention BCrypt and delegating.
  - New section "BCrypt och hashar från Spring": the Spring code (`new DelegatingPasswordEncoder
    ("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(), ...))`, with
    `PasswordEncoderFactories.createDelegatingPasswordEncoder()` mentioned), then
    `BCryptExample.java` verbatim, then notes.
  - 3 new comparison rows. The "Bara PBKDF2-SHA256" row is replaced, and 6 new deviation rows
    cover 72 byte/U+0000, canonical Base64, `needsRehash` only at a lower cost, always `$2a$`,
    exact ids without a fallback, and `{pbkdf2}` being Spola's format.
- `framework/PROGRESS.md`:
  - New section for mission 12: the need is delivered, the code is security critical and needs
    a human review, and all six known limitations are listed.
  - "Återstår" updated.
  - The 10a known-problems sentence "Bara PBKDF2-HMAC-SHA256 (ingen BCrypt, ...)" is corrected.
- `framework/ARCHITECTURE.md`: new section "Lösenord från Spring: BCrypt och delegerande hasher
  (`spola.security`)" with every topic crit. 17 lists.
- `framework/MONSTER.md` (catalog row) and the root `README.md` (module table row): updated.

No changes in `Build.java`, other modules, `Authenticator` or `InMemoryUserStore`.

## Verification

Final full build: `cd framework && export PATH=/opt/jdk-25/bin:$PATH && java Build.java`.
```
guardrails: source       465 .java files
guardrails: source       15 other files: 0 not allowed, 15 documents (0 parse as Java)
guardrails: no violations
...
spola.security: OK - 305 tests, 305 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 17208 ms
spola.security.web: OK - 130 tests, 130 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 7557 ms
spola.demo: OK - 43 tests, 43 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 287 ms
BUILD OK in 269365 ms
real	4m31.086s
exit 0
```
Every module is OK. Security has 305 tests, of which 202 are new and take about 13 s, about 6 s
of that for the five cost-12 jBCrypt vectors. Excerpts:
```
  Blowfish core
    ok      the tables derived from pi have the known values, also after hashes were computed
    ok      the core gives the JDK's Blowfish/ECB/NoPadding for 250 random keys of 4-56 bytes and blocks
    ok      the round count does not overflow: 16 for cost 4, 2^31 for cost 31
  bcrypt vectors
      ok      [20] "~!@#$%^&*()      ~!@#$%^&*(..." $2a$12$ (309 ms)
    ok      CVE-2025-22228: L98 verifies only as its first 72 bytes, and cannot be hashed
  the bcrypt README example (the need's use case)
    ok      the package-private path gives exactly Spring's string for "hemligt", cost 10 and the salt 0x10..0x1f
```

Extra checks during development (scratch only, not part of the build):
- **Differential test against libxcrypt** (`perl -e 'print crypt(...)'` on this machine):
  1 200 random cases, `ok 600 bad 0` twice. Cases were valid UTF-8 (`$2a$`/`$2b$`/`$2y$`) and
  raw NUL-free bytes including ≥0x80 (`$2b$`/`$2y$`), 0–100 bytes, cost 4–5. The tests themselves
  call no external program.
- **Mutation runs** (a mutated copy compiled and run against the suite). Each was caught by at
  least one test:
  - sign-extended key bytes;
  - `needsRehash` with `!=`;
  - non-canonical Base64 accepted;
  - delegating `needsRehash` short-circuiting the delegate;
  - password checked before the hash is parsed;
  - 73 bytes accepted;
  - U+0000 accepted;
  - case-insensitive or stripped id;
  - the delegating map not copied.

  The U+0000 mutation first survived because the rule was enforced twice (on chars and on bytes).
  I removed the redundant byte loop (only U+0000 encodes to 0x00 in UTF-8), and it is caught now.
- π derivation in a cold JVM: about 295 ms once. The 8 known values match.

## Addressed

Round 1, no findings.

## Rebuttals

None. Interpretations and deviations, also noted in ARCHITECTURE.md:
- **Delegating messages don't list the configured ids.** The mission permits listing them ("Det
  får lista de konfigurerade id:na"). I chose fixed texts because a list can contain the text
  between the braces when that text is a substring of an id (`{bcrypt,}`, `{pbk}`), which is the
  thing crit. 11/14 forbid. The ids are visible in `toString()`.
- **ReadmeTests also checks the three new comparison rows.** Crit. 2 lists ReadmeTests changes as
  "avvikelseraderna", but crit. 15 says "Jämförelse- och avvikelseraderna kontrolleras av
  ReadmeTests". The existing expectations are unchanged. Only the replaced topic `Bara
  PBKDF2-SHA256` was swapped for `Ingen SCrypt, Argon2`, and a check that the old row is gone was
  added.
- **"The primitive uses the helper" (crit. 7)** is shown by a source check in `BlowfishTests`
  (`long rounds = rounds(cost);` and `for (long round = 0; round < rounds; round++) {`, exactly
  one such loop) together with the cost-4/5/6/8/10/12 vectors. A cost-31 run cannot be executed
  in a test.
- **The criterion 6 known-values check reads copies** (`initialP()`/`initialS()`). The copies
  reflect the internal arrays at the time of the call. The test zeroes its own copies, computes
  hashes and Blowfish encryptions, then reads again.
- **Wording of the README example.** The need's snippet is not compilable on its own
  (`storedFromSpring`, `accounts`). `BCryptExample` keeps its lines verbatim, comments included,
  split across `hasher()`, `check(...)`, `hashPassword(...)` and `authenticator(hasher,
  accounts)`, with an `Account` record for `APP_AUTH_USERS_<n>_*`.

## Known gaps

- The first use of bcrypt in a JVM pays for deriving π, about 50–300 ms once. In practice that
  happens in `Authenticator.of` (the dummy hash) at startup. This is documented in PROGRESS.md.
- Spring itself is not in the repo, so compatibility with Spring is shown through jBCrypt's
  vectors and the libxcrypt vectors, not by running `BCryptPasswordEncoder`.
- `BCryptHasherTests` uses a stored cost-31 hash to prove that refused passwords compute nothing.
  If someone breaks that rule, the test fails at its 10 s timeout and leaves a thread burning CPU
  until the JVM exits.
- The "no table of constants" check is a heuristic: known values, 16+ numbers in one array
  initialiser, and at most two 32-bit hex literals per file.
