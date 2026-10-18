VERDICT: PASS
FINDINGS:
- id: BRK-1
  severity: MINOR
  where: framework/modules/security/src/spola/security/Authenticator.java:21-33 ("Old hashes can be told apart"), PasswordHasher.java "Time" section of delegating, framework/modules/security/README.md:281-288, framework/PROGRESS.md:1072-1078 and 1384-1390
  problem: The new L3 caveat says users still on an old algorithm can be told apart only "until the user has logged in once" and names two exceptions (no PasswordUpgrade, a higher bcrypt cost). It leaves out a third: a {pbkdf2} user whose password is longer than 72 bytes (or contains U+0000) is never moved to the bcrypt default. bcrypt's hash() refuses that password, so the upgrade fails on every login, and the user stays distinguishable for good with a timing signal that has no noise at all. ARCHITECTURE.md:3207 (from mission 12) already records that the upgrade fails for these users. The new user-facing caveat contradicts that by suggesting one login is enough.
  evidence: The probe /home/user/spola/gauntlet/runs/20261018-1723-sakerhet-losenord/round-1/scratch-breaker/probe/probe/LongPw.java uses delegating("bcrypt", {bcrypt cost 10, pbkdf2 defaults}) with store.upgrader() and a user "ada" stored as {pbkdf2} with an 82-byte passphrase. Output: "password bytes: 82". Three logins each print "WARNING: password upgrade failed: java.lang.IllegalArgumentException" and then "login N: Authenticated[...]". After every login, "stored prefix after login: {pbkdf2}$pbkdf2-sha256...". Wrong-password timing after the 3 logins: "unknown user: 0.0 ms, ada (logged in 3 times): 148.9 ms".
  fix_hint: Add this case to the exceptions in the Authenticator rule, the delegating "Time" section, README and PROGRESS: a password the default hasher's hash() refuses (bcrypt: over 72 bytes or U+0000) is never upgraded, so that user stays distinguishable.
- id: BRK-2
  severity: MINOR
  where: framework/ARCHITECTURE.md:2394 (bullet "Uppgradering som inte stoppar inloggningen" in "Säkerhet del 1")
  problem: This line still states the removed API in the present tense, "För `InMemoryUserStore` är det `store.upgrader(hasher)`", and has no "sedan 15c" note. Every other old mention in ARCHITECTURE and PROGRESS got one. upgrader(PasswordHasher) no longer exists.
  evidence: grep -n "upgrader(hasher)" framework/ARCHITECTURE.md gives line 2394 "`UserDetailsPasswordService`). För `InMemoryUserStore` är det `store.upgrader(hasher)`." InMemoryUserStore.java:296 defines only "public PasswordUpgrade upgrader()".
  fix_hint: Change it to `store.upgrader()` (or add "(sedan 15c `store.upgrader()`)") and mention that the verified hash is passed.

What I tried that held up:
- I copied framework/ to scratch (scratch-breaker/fw) and ran java Build.java there with JDK 25. Guardrails reported no violations, all 14 modules were OK (security 346, security.web 287), BUILD OK, exit=0.
- I fuzzed 18 000 mutated stored hashes, seeded from valid bcrypt, PBKDF2 and delegating hashes, through matches and needsRehash on all three hashers. Mutations included non-ASCII, U+0000, U+FFFF, lone surrogates, truncation and stray $ { } =. Only IllegalArgumentException came out, so there is no stored hash that escapes the L2 catch as a 500.
- Timing with bcrypt cost 8, 60 runs each: unknown user, {noop} row, truncated {bcrypt} row and real user with a wrong password all took 18.5–18.7 ms min and about 20–21 ms avg. Same work, as claimed.
- Mutation: I changed InMemoryUserStore.upgrader() back to the review's needsRehash condition (bcrypt cost 5). In 3 of 3 runs, the probe cases with the change hashed by the old instance and the 200-thread test failed.
- I ran the original UpgradeRaceTests 8 times normally and 4 times with -XX:ActiveProcessorCount=1. It passed every time; the 1-CPU runs took about 12 s, within the 60 s timeout.
- Checked by reading:
  - The CAS uses the exact string that went to matches.
  - computeIfPresent keeps the roles and the enabled flag.
  - The log line carries only the escaped class name, with no Throwable attached.
  - A disabled user with a malformed hash gets BAD_CREDENTIALS.
  - HttpBasic's CharBuffer password is not consumed before the dummy check.
  - L4: of(...,33..64) is refused, stored hashes of 33–64 bytes are still parsed, and needsRehash is true for them.
  - No stale upgrade(username, newHash) or upgrader(hasher) calls remain in code. Demo and security-web main code do not use PasswordUpgrade. module-info files are unchanged.
