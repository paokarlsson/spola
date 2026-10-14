VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /home/user/spola/framework/modules/security/src/spola/security/PasswordHasher.java:44-54
  problem: The `needsRehash` contract on the interface still says "made with other settings than this hasher's", but BCryptPasswordHasher returns false for a higher cost and for another version, so the interface text no longer describes every implementation.
  evidence: Interface: "Whether a stored hash was made with other settings than this hasher's, so that it should be replaced". BCryptPasswordHasher.java:86-89: "`true` only when the stored cost is lower"; "That differs on purpose from Pbkdf2PasswordHasher". The difference is only documented on the implementation and in README/ARCHITECTURE.
  fix_hint: Reword the interface Javadoc to "whether this hasher wants the hash replaced; each implementation defines when (Pbkdf2: any difference, BCrypt: lower cost only)".
- id: MNT-2
  severity: MINOR
  where: /home/user/spola/framework/modules/security/src/spola/security/BCrypt.java:25-29, 74, 212-216; BCryptPasswordHasher.java:94-96, 103-105, 133-135, 270-272
  problem: The bcrypt string layout and cost limits each have more than one source of truth. `MIN_COST`/`MAX_COST` and the "cost must be 4 to 31" check exist in both BCrypt and BCryptPasswordHasher. `format` writes `$2a$NN$` in one class and `Stored.parse` reads it in another, with its own `SALT_START = 7` and `ENCODED_CHARS = 60`. `BCrypt.HASH_CHARS` is declared and never used.
  evidence: `static final int HASH_CHARS = 31;` has no use. `ENCODED_CHARS = 60` is not derived from `SALT_START + SALT_CHARS + HASH_CHARS`. Tests repeat the offsets (`hash.substring(7, 29)`, `encoded.charAt(28)`).
  fix_hint: Define `BCryptPasswordHasher.MIN_COST`/`MAX_COST` as `= BCrypt.MIN_COST`/`MAX_COST`. Derive `ENCODED_CHARS` from the other constants. Delete `HASH_CHARS` or use it.
- id: MNT-3
  severity: MINOR
  where: /home/user/spola/framework/modules/security/test/spola/security/DelegatingTests.java:27; BCryptVectorTests.java:201-202; BCryptMalformedTests.java:103
  problem: Tests pick fixtures by list position in another file's table (`LIBXCRYPT.get(10)`, `.get(3)`, `.get(4)`, `CASES.get(5)`). Inserting or reordering a vector breaks unrelated test classes with a failure that does not point at the cause.
  evidence: `static final String BCRYPT_HASH = BCryptVectorTests.LIBXCRYPT.get(10).expected();   // "hemligt", $2a$04$`. BCryptVectorTests ends with an assertion on the exact list `List.of(13, 6, 4, 2, 3, 71, 72, ...)`.
  fix_hint: Give those vectors named constants (`HEMLIGT_COST_4`, `COMPOSED_VECTOR`, `DECOMPOSED_VECTOR`) and use the constants instead of indexes.
- id: MNT-4
  severity: MINOR
  where: /home/user/spola/framework/modules/security/test/spola/security/BlowfishTests.java:118-144 and 147-157; SourceRulesTests.java:37-50
  problem: The module's source rules are split over two test classes. The scan for Cipher and reflection over all main files, together with its file walker and comment-stripping regex, sits in BlowfishTests, while SourceRulesTests holds the other source rules with a copy of the same walker and regex. A maintainer looking for the rules will find only half of them in SourceRulesTests.
  evidence: BlowfishTests:130 `text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "")` is identical to SourceRulesTests:44. BlowfishTests also carries the test "main code uses no Cipher, no reflection and no table of Blowfish constants" for every file.
  fix_hint: Move the shared walker and stripping into one helper in SourceRulesTests, and call it from BlowfishTests (or move the whole check there). Add a pointer comment if the placement is kept.
- id: MNT-5
  severity: MINOR
  where: /home/user/spola/framework/modules/security/test/spola/security/BCryptExampleTests.java:40-48 and ReadmeTests.java:26-33
  problem: The "README shows the example file unchanged" check is copied (header string, `block` construction, `contains`). A third example would copy it again.
  evidence: Both build `"```java\n" + source.substring(header.length()) + "```\n"` after `header = "package spola.security;\n\n"`.
  fix_hint: Extract `ReadmeTests.assertShows(Path exampleSource)` and call it from both.
- id: MNT-6
  severity: MINOR
  where: /home/user/spola/framework/modules/security/src/spola/security/Blowfish.java:202-206
  problem: In the `Pi` holder, `WORDS = fraction(...)` is declared before `GUARD_BITS`, and `fraction` uses `GUARD_BITS`. It works only because `GUARD_BITS` is a compile-time constant and is inlined. If a maintainer turns it into a non-constant, `fraction` silently runs with guard bits 0 and the tables are wrong, with no hint in the code.
  evidence: `static final int[] WORDS = fraction(P_WORDS + S_WORDS);` comes before `private static final int GUARD_BITS = 64;`.
  fix_hint: Declare `GUARD_BITS` before `WORDS`.
- id: MNT-7
  severity: MINOR
  where: /home/user/spola/framework/modules/security/src/spola/security/BCrypt.java:147 and BCryptPasswordHasher.java:219
  problem: Two different private/package methods are both named `encode` in the same bcrypt code path. `BCrypt.encode(byte[])` is bcrypt Base64 and `BCryptPasswordHasher.encode(CharSequence)` is the password to UTF-8 with the acceptance rules, which returns null for refused passwords. The name hides that the second one decides whether the password is accepted.
  evidence: `var bytes = encode(password); if (bytes == null) {...}` against `BCrypt.encode(salt)` in `format`.
  fix_hint: Rename them to `base64(byte[])` and `acceptedBytes(CharSequence)` (or `passwordBytesOrNull`).
- id: MNT-8
  severity: MINOR
  where: /home/user/spola/framework/modules/security/src/spola/security/Blowfish.java:109-132
  problem: `expand` has two almost identical loops (P-array, then S-boxes) that each do the salt XOR, encrypt and store. `saltWords == null` also switches between two algorithms (bcrypt's ExpandKey and Blowfish's ordinary key schedule), which is easy to miss at the call sites `state.expand(key, null)`.
  evidence: The blocks `if (saltWords != null) { l ^= ...; salt ^= 2; } long block = encrypt(l, r); ...` appear twice.
  fix_hint: Run one loop over `p` and `s` with a shared helper, or add two named methods (`expandWithSalt`, `expand`) that call a common private one.

Overall: the code is well documented, explicit and free of reflection and hidden wiring. Javadoc, README, ARCHITECTURE.md and PROGRESS.md agree with each other and with the code. The test files are large but have a clear structure. I found no BLOCKER or MAJOR issue in clarity, structure or "magic". The findings above are all small things to tidy up.
