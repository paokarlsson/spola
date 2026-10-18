VERDICT: PASS
FINDINGS:
- id: MNT-9
  severity: MINOR
  where: /home/user/spola/framework/modules/security/test/spola/security/BCryptHasherTests.java:32,53 and BCryptMalformedTests.java:34,104
  problem: The generic helper `withoutBcrypt` is in `BCryptHasherTests` and `BCryptMalformedTests` calls it from there, although the module already has `Support.java` for shared test helpers. Also two sibling test classes each define a `TAIL` constant with the same name and different values.
  evidence: `BCryptHasherTests.TAIL = "m0CrhHm10qJ3lXRY.5zDGO3rS2KdeeWLuGmsfGlMfOxih58VYVfxe"` (vector "a", cost 06). `BCryptMalformedTests.TAIL = GOOD.substring(7)` (`DCq7YP...`, the "" vector). `BCryptMalformedTests` calls `BCryptHasherTests.withoutBcrypt(...)`. `Support` is described as "Shared test helpers".
  fix_hint: Move `withoutBcrypt` and `NO_BCRYPT` to `Support`. Rename one `TAIL`, for example `VECTOR_A_TAIL` and `EMPTY_TAIL`.
- id: MNT-10
  severity: MINOR
  where: /home/user/spola/framework/modules/security/src/spola/security/Blowfish.java:74 (callers BCrypt.java:98-99, Blowfish.java:56)
  problem: `keyWords(byte[] key, boolean nulTerminated)` takes a boolean flag, so call sites do not say what they mean. The password gets a NUL and the salt does not.
  evidence: `Blowfish.keyWords(password, true)`, `Blowfish.keyWords(salt, false)` and `keyWords(key, false)`. The meaning is only in the Javadoc of the parameter.
  fix_hint: Use two named entry points, for example `passwordKeyWords(password)` and `cyclicKeyWords(bytes)`, or a short comment at the two bcrypt call sites.
- id: MNT-11
  severity: MINOR
  where: /home/user/spola/framework/modules/security/src/spola/security/BCryptPasswordHasher.java:118, DelegatingPasswordHasher.java:18, Pbkdf2PasswordHasher.java:89
  problem: The prefix of the "malformed stored hash" message is copied into three classes, and the tests rely on all three being the same.
  evidence: `private static final String MALFORMED = "malformed stored password hash: ";` appears three times. `BCryptMalformedTests.checkMessage` says "starting like Pbkdf2PasswordHasher's". A change in one class makes the others differ and only the tests notice.
  fix_hint: One package-private constant, for example in `PasswordHasher`'s implementation helpers or `Names`, used by all three.
- id: MNT-12
  severity: MINOR
  where: /home/user/spola/framework/modules/security/src/spola/security/BCrypt.java:68-78 and BCryptPasswordHasher.java:268-296
  problem: The stored-hash layout is still written in one class and read in another. The allowed versions `a`, `b`, `y` are checked in two places (`format` and `Stored.parse`), and `SALT_START = 7` in the hasher has to match the literal `"$2" + version + "$" + cost + "$"` in `BCrypt.format`. Round 1 MNT-2 was fixed for the constants only.
  evidence: `if (version != 'a' && version != 'b' && version != 'y')` in `BCrypt.format`, and `(version != 'a' && version != 'b' && version != 'y')` in `Stored.parse`. Tests also repeat the offsets (`substring(7, 29)`, `charAt(28)`).
  fix_hint: Put the version check (`BCrypt.isVersion(char)`) and the prefix layout next to each other in `BCrypt`, and let `Stored.parse` use it. This is optional.
- id: MNT-13
  severity: MINOR
  where: /home/user/spola/framework/modules/security/test/spola/security/BlowfishTests.java:111-114
  problem: The "runs rounds(cost) rounds" test matches source text that includes the local variable names. Renaming `round` or `rounds` in `BCrypt.hash` fails the test with a message about the loop, not about the rename.
  evidence: `code.contains("for(longround=0;round<rounds;round++){")` and `count(code, "round<rounds")`. Whitespace and comments no longer matter, but names do.
  fix_hint: Add a short comment in `BCrypt.hash` at the loop saying that `BlowfishTests` reads this text. Or match with a regex on the `long` loop variable.

Overall: all 8 round-1 MINOR findings and the MAJOR BRK-1 are handled. The refactor of `Blowfish` into `expandKey(keyWords)`, `expandKey(keyWords, saltWords)` and one private `replace` is easier to follow than the old `null` switch, and the salt continuity from P into S is explained in the Javadoc and pinned by the vectors. `toBase64`/`fromBase64`, `acceptedBytes`, the named vector constants and the shared `checkCost` read well. I found no reflection, no hidden wiring and no naming conventions. Javadoc, the README, PROGRESS.md and ARCHITECTURE.md agree with the code and with each other. The BRK-1 advice (give the hasher at least the highest stored cost) appears in about eight places. They are consistent now, but a later change to `needsRehash` has to update all of them; this is tolerable and I do not count it as a finding. No BLOCKER or MAJOR issue in clarity, structure or "magic".
