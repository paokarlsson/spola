# Build, round 2: BCrypt and a delegating {id} password hasher (spola.security)

## Changes

The only upheld finding was BRK-1 (MAJOR), and it needed documentation only. As the judge said,
`needsRehash` semantics and `Authenticator` stay as they are (binding mission decisions). I
also fixed all ten MINOR findings, since each was cheap. No behaviour of the public API changed.

Documentation (BRK-1):
- `BCryptPasswordHasher.java`:
  - Class Javadoc, "Rehashing": new paragraph "Give the hasher at least the highest stored
    cost". It explains that a higher stored cost is never rehashed down and that
    `Authenticator`'s dummy hash uses the hasher's cost. So with hashes from
    `new BCryptPasswordEncoder(12)` and `defaults()`, a known user with a wrong password takes
    about 4x as long as an unknown one, for good, and the time reveals which usernames exist. Use
    `of(12)`. It also covers the opposite, temporary case after the cost is raised.
  - `defaults()` and `needsRehash` point to it.
- `PasswordHasher.java`:
  - `delegating` Javadoc: the `needsRehash` bullet says the default id's hasher decides, and that
    bcrypt keeps a higher cost, with the same advice.
  - Interface `needsRehash` Javadoc reworded (MNT-1): "whether this hasher wants a stored hash
    replaced ... Each implementation says when", and it names the three rules.
- `modules/security/README.md`:
  - New bullet next to the example, "**Ge bcrypt minst den högsta lagrade kostnaden.**", with
    `defaults()` vs `of(12)`, the reason, and the permanent username timing leak.
  - The deviation row "`needsRehash` bara vid lägre kostnad för BCrypt" gets the same advice in
    its "Varför" cell. Its topic text is unchanged, so `ReadmeTests` (which this round does not
    touch) still matches.
- `PROGRESS.md`:
  - New known limitation for mission 12 about the higher stored cost: the timing oracle, the fix
    `of(12)`, and the opposite temporary case.
  - The delivered bullet on `needsRehash` points to it.
- `ARCHITECTURE.md`:
  - New decision bullet "Hasharens kostnad ska vara minst den högsta lagrade". It gives the
    consequence and says the fix is configuration, documented in Javadoc, README and PROGRESS.
    It explains why: lowering the cost on login would go against `upgradeEncoding` and the
    mission, and a dummy hash at the highest stored cost would need `Authenticator` changes,
    which are out of scope.
  - The test paragraph notes the new 5 s cost-31 check.

Main code (MINOR findings, no behaviour change):
- `Blowfish.java`:
  - MNT-8: `expand(keyWords, saltWords|null)` is replaced by two named entry points.
    `expandKey(keyWords)` is Blowfish's ordinary schedule, `ExpandKey(state, 0, key)`, done as
    the salted one with a zero salt (`NO_SALT`). `expandKey(keyWords, saltWords)` is bcrypt's.
  - The two copied loops are now one private `replace(target, block, saltWords, position)`.
    It is called for P and then for S, and the salt pair is `(position + i) & 3`, so the salt
    words carry on from P into S as before.
  - MNT-6: `GUARD_BITS` is declared before `WORDS`.
- `BCrypt.java`:
  - MNT-7: `encode`/`decode` are renamed `toBase64`/`fromBase64`.
  - MNT-2: `SALT_CHARS`/`HASH_CHARS` are derived from the byte counts. `checkCost` is now
    package-private with Javadoc, and `BCryptPasswordHasher.of` uses it.
  - The key-schedule calls use `expandKey`. The round loop is unchanged.
- `BCryptPasswordHasher.java`:
  - MNT-2: `MIN_COST`/`MAX_COST` are `BCrypt.MIN_COST`/`MAX_COST` (still compile-time constants
    for `{@value}`). `ENCODED_CHARS` is `SALT_START + BCrypt.SALT_CHARS + BCrypt.HASH_CHARS` and
    now private. `of` calls `BCrypt.checkCost`, so the range check and its message exist once.
  - MNT-7: the password check `encode(CharSequence)` is renamed `acceptedBytes`.

Tests (all of them mission-12 files; `SecurityTests`, `SourceRulesTests` and `ReadmeTests` are
not touched this round):
- `BCryptHasherTests`, AUD-3:
  - New helper `withoutBcrypt(what, check)` runs a cost-31 check on a daemon thread. If the check
    has not returned within 5 s, the test fails with "bcrypt was computed: <what> did not return
    within 5 s (cost 31 takes days)". Otherwise it rethrows what the check threw.
  - It is used for `of(31).hash` and `matches` against a cost-31 hash in the refused-password
    test, and for `needsRehash` of cost-31 hashes.
- `BCryptMalformedTests`:
  - The `$2a$31$` `needsRehash` check uses the same helper.
  - MNT-3: the 10 000-character case is the named constant `TEN_THOUSAND`.
- `BCryptVectorTests`, MNT-3: named vectors `LIBXCRYPT_COMPOSED`, `LIBXCRYPT_DECOMPOSED` and
  `HEMLIGT_COST_4` replace `LIBXCRYPT.get(3/4/10)`. `DelegatingTests` uses `HEMLIGT_COST_4`.
- `BlowfishTests`:
  - AUD-2: the "primitive runs rounds(cost) rounds" source check now ignores comments and all
    whitespace, so reformatting doesn't break it.
  - A shared `withoutComments` helper.
  - MNT-4: a comment points to `SourceRulesTests` for the other main-code rules.
- `BCryptExampleTests`:
  - MNT-5: a comment says why the README-block check is not shared with `ReadmeTests` (crit. 2).
  - The call is renamed to `BCrypt.toBase64`.

## Verification

Full build: `cd framework && export PATH=/opt/jdk-25/bin:$PATH && time java Build.java`, exit 0.
```
guardrails: source       465 .java files
guardrails: source       15 other files: 0 not allowed, 15 documents (0 parse as Java)
guardrails: no violations
spola.guardrails: OK - 17 tests, 17 ok, 0 failed, ...
spola.test: OK - 77 tests, 77 ok, 0 failed, ...
spola.di: OK - 48 tests ...   spola.config: OK - 68 ...   spola.util: OK - 80 ...
spola.codegen: OK - 41 ...    spola.json: OK - 417 ...    spola.http: OK - 283 ...
spola.validation: OK - 251 ... spola.web: OK - 823 ...
spola.security: OK - 305 tests, 305 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 18207 ms
spola.security.web: OK - 130 tests, 130 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 8108 ms
spola.demo: OK - 43 tests, 43 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 306 ms
BUILD OK in 274214 ms
real	4m36.139s
```
Excerpts from `spola.security`:
```
    ok      the primitive runs exactly rounds(cost) rounds, counted in a long (5 ms)
      ok      [20] "~!@#$%^&*()      ~!@#$%^&*(..." $2a$12$ (307 ms)
    a refused password: hash throws without it, matches is false, no bcrypt computed
      ok      [3] ? x 24 + a (73 bytes) (1 ms)
    ok      needsRehash with of(10): true only for a lower cost; version and higher cost give false (1 ms)
```
The cost-12 vectors take 293–307 ms, the same as in round 1 (309 ms), so the `Blowfish`
refactor costs no speed.

Extra checks (scratch only, not part of the build). I compiled the module with its tests using
the build's javac options (`-Xlint:all -Xdoclint:all/protected -Werror`) and ran the suite
through the `spola-test` tool:
- Unmodified: `spola.security: OK - 305 tests, 305 ok`.
- **Mutation `Blowfish`: `(position + i) & 3` → `i & 3`** (salt words restart in the S-boxes):
  `FAILED - 305 tests, 223 ok, 82 failed`. This shows the vectors pin the salt continuity that
  the refactored `replace` must keep.
- **Mutation `BCryptPasswordHasher`: byte limit 72 → 200** (accepts 73–200 bytes). The two
  multi-byte refused cases now fail after 5 s with
  `spola.test.AssertionFailure: bcrypt was computed: of(31).hash of a refused password did not
  return within 5 s (cost 31 takes days)` instead of a bare 10 s timeout. The secrets test also
  fails. The JVM exited normally afterwards (no `Launch` process left), so the stuck daemon
  threads did not keep it alive.
- Scope (`git diff --stat HEAD`): only `framework/modules/security/{src,test,README.md}`,
  `PROGRESS.md` and `ARCHITECTURE.md` changed. `Build.java`, `module-info.java`, `ReadmeTests`,
  `SourceRulesTests`, `SecurityTests` and other modules are unchanged.

## Addressed

- **BRK-1 [MAJOR]**: documentation only, as the fix hint says. The advice "hasher cost at least
  the highest stored bcrypt cost, e.g. `of(12)` for `new BCryptPasswordEncoder(12)`", with the
  reason (the dummy hash uses the hasher's cost, a higher cost is never lowered, so the
  username timing oracle is permanent), is now in:
  - the `BCryptPasswordHasher` Javadoc ("Rehashing", `defaults()`, `needsRehash`);
  - the `PasswordHasher.delegating` Javadoc;
  - the README, as a bullet next to the example and in the `needsRehash` deviation row;
  - the PROGRESS.md known limitations for mission 12;
  - an ARCHITECTURE.md decision bullet.

  `needsRehash`, `Authenticator` and `ReadmeTests`' expectations are unchanged.
- **AUD-2 [MINOR]**: the source check is now whitespace- and comment-insensitive. The functional
  part (cost 4–12 vectors, and the cost-4 use-case vector in the same test) is unchanged. A
  countable seam would not show anything for cost 31 either.
- **AUD-3 [MINOR]**: cost-31 checks run through `withoutBcrypt`, which fails after 5 s with an
  explicit message. They already ran on a daemon thread (the runner's), so the JVM can exit. A
  CPU-bound bcrypt cannot be interrupted, so a regressed thread still runs until the JVM exits.
- **MNT-1 [MINOR]**: the interface `needsRehash` Javadoc is reworded, see Changes.
- **MNT-2 [MINOR]**: the constants are shared or derived, `HASH_CHARS` is used, and the range
  check exists once (`BCrypt.checkCost`). `Stored.parse` keeps its own range test because it
  needs the "malformed" message.
- **MNT-3 [MINOR]**: named constants replace the list indices.
- **MNT-4 [MINOR]**: a pointer comment and a `withoutComments` helper in `BlowfishTests`.
  `SourceRulesTests` is left alone (crit. 2).
- **MNT-5 [MINOR]**: a pointer comment only. Extracting the check would mean changing
  `ReadmeTests`, which crit. 2 limits.
- **MNT-6 [MINOR]**: `GUARD_BITS` is declared before `WORDS`.
- **MNT-7 [MINOR]**: `toBase64`/`fromBase64` and `acceptedBytes`.
- **MNT-8 [MINOR]**: two named key-schedule entry points, no `null` switch, one shared loop.

## Rebuttals

None.

## Known gaps

- The BRK-1 timing leak still exists when an app ignores the advice. Closing it in code would
  mean a dummy hash at the highest stored cost (an `Authenticator` change) or lowering costs on
  login. Both are excluded by the mission.
- "The primitive uses `rounds(cost)`" is still shown by reading the source, together with the
  cost 4–12 vectors. Cost 31 cannot be executed in a test.
- A regression in the refusal rule still leaves daemon threads computing bcrypt until the test
  JVM exits. The test now fails after 5 s with a clear message.
- As in round 1: compatibility with Spring is shown through jBCrypt/Openwall/libxcrypt vectors,
  not by running Spring. The π derivation costs about 50–300 ms once per JVM.
