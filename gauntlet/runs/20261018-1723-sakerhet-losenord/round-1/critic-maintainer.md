VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /home/user/spola/framework/modules/security/src/spola/security/Authenticator.java:132,137,147
  problem: The rule "every failure path costs exactly one dummy check" is spread over three copies of `hasher.matches(..., dummyHash)`, one of them in the new catch block. A fourth failure path could easily forget it.
  evidence: `hasher.matches(password == null ? DUMMY_PASSWORD : password, dummyHash);`, `hasher.matches(password, dummyHash);` (unknown user) and the same call in `catch (IllegalArgumentException e)`.
  fix_hint: Extract a private `refuse(CharSequence password)` that does the dummy check and returns BAD_CREDENTIALS, and use it in all three places.

- id: MNT-2
  severity: MINOR
  where: /home/user/spola/framework/modules/security/src/spola/security/Authenticator.java:127 (the `authenticate` Javadoc)
  problem: After L2 the `@throws` clause is incomplete. It says only "whatever UserStore#find throws". A RuntimeException other than IllegalArgumentException from a custom hasher's `matches` also propagates, and a test (`only IllegalArgumentException is a malformed hash`) pins that behaviour. Only PROGRESS and ARCHITECTURE say so, not the Javadoc.
  evidence: `@throws RuntimeException whatever {@link UserStore#find} throws`. The class-level bullet only says "When matches throws IllegalArgumentException".
  fix_hint: Add one sentence to the bullet "A malformed stored hash is a failed login" and to `@throws`: any other RuntimeException from the hasher or the store propagates unchanged.

- id: MNT-3
  severity: MINOR
  where: /home/user/spola/framework/modules/security/src/spola/security/PasswordUpgrade.java:17,37 and README.md:149
  problem: The SQL placeholders are in a different order from the method parameters, and all three are `String`. Someone implementing `PasswordUpgrade` against a database can swap `verifiedHash` and `newHash` without a compile error. Swapping them would silently store the old hash.
  evidence: The SQL has `-- newHash, username, verifiedHash`, while the method is `upgrade(String username, String verifiedHash, String newHash)`.
  fix_hint: Add a short Java snippet to the Javadoc and README showing the binding in the order of the method signature (`ps.setString(1, newHash); ps.setString(2, username); ps.setString(3, verifiedHash)`). The signature is fixed by the spec, so this can only be handled with documentation.

- id: MNT-4
  severity: MINOR
  where: /home/user/spola/framework/modules/security/src/spola/security/Pbkdf2PasswordHasher.java:79-102 and the class Javadoc around line 55
  problem: The constants that decide what a stored hash may look like are mixed with the ones that decide what `of` accepts. `MAX_SALT_BYTES` and `MIN_HASH_BYTES` are shared by `of` and `Stored.parse`. `MIN_STORED_SALT_BYTES` and `MAX_STORED_HASH_BYTES` are stored-only. Changing a public "creation" constant silently changes the parsing bounds. The Javadoc also repeats the literal 64 instead of referring to the constant.
  evidence: `Stored.parse` uses `salt.length > MAX_SALT_BYTES` next to `hash.length > MAX_STORED_HASH_BYTES`. The class Javadoc says "the hash 16 to 64 bytes".
  fix_hint: Give the parser its own pair of constants (`MIN_STORED_*`, `MAX_STORED_*` for both salt and hash), or add a one-line comment above the constants saying which are shared. Let the Javadoc point to the constant.

- id: MNT-5
  severity: MINOR
  where: /home/user/spola/framework/ARCHITECTURE.md:2394 and 4142-4156; /home/user/spola/framework/PROGRESS.md:1095-1105
  problem: ARCHITECTURE line 2394 still says the `InMemoryUserStore` upgrade is `store.upgrader(hasher)` with no "sedan 15c" marker, while the same decision is corrected two paragraphs later. The test list in "Säkerhetsgranskning del 3" repeats the 15c entry in PROGRESS almost word for word (nine hashes, 200 threads, five mutations). These two copies will drift.
  evidence: ARCHITECTURE:2394 "För `InMemoryUserStore` är det `store.upgrader(hasher)`."
  fix_hint: Add "(sedan 15c `upgrader()`)" at line 2394. Cut the "Tester" bullet in ARCHITECTURE down to the design reason (a per-change check is stronger than an end-state check) and leave the list of tests to PROGRESS.

- id: MNT-6
  severity: MINOR
  where: /home/user/spola/framework/modules/security/test/spola/security/UpgradeRaceTests.java:34-35, MalformedLoginTests.java:32 and 75-77, and the probe lambda at UpgradeRaceTests.java:98-146
  problem: Smaller test-structure points. (a) `BAD` (and `ADA`) are declared again in each test class (AuthenticatorTests, BCryptAuthenticatorTests, MalformedLoginTests, UpgradeRaceTests). (b) The fixtures use unexplained offsets: `bcrypt.substring(0, 59)` for "truncated" and `"{bcrypt}$2$" + bcrypt.substring(4)`. (c) The probe is a 48-line inline lambda, and `loginsAndChanges` is an 85-line method that sets up, runs and checks.
  evidence: `static final AuthResult BAD = new AuthResult.Failed(Failure.BAD_CREDENTIALS);` four times. `new Case("a truncated {bcrypt} hash", "{bcrypt}" + bcrypt.substring(0, 59))`.
  fix_hint: Move `BAD` and `ADA` to `Support`. Name the offsets (`BCRYPT_LENGTH - 1`) or comment them. Extract the probe body to a named method such as `probe(Change)`, and split `loginsAndChanges` into run and verify.

Nothing blocks a takeover. The three source changes (the compare-and-set in `InMemoryUserStore`, the narrow `try`/`catch` around `matches` in `Authenticator`, and `MAX_HASH_BYTES`) each do one thing and are documented where a reader will look. There is no magic: no reflection, no new annotations, no naming conventions, and the wiring is plain Java.

Deviations that I judged against the spec and accepted:
- `upgrader()` takes no hasher parameter. The spec says nothing on this, and the reason is in ARCHITECTURE.
- `StoredHashTests` is a new test file in `security-web`. It is needed for criterion 4, and no main source there changed.
