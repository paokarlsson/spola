```
VERDICT: FAIL
FINDINGS:
- id: BRK-1
  severity: MAJOR
  where: framework/modules/security/src/spola/security/Authenticator.java:111-116 together with InMemoryUserStore.java:58-66 (updatePassword), using the wiring the docs recommend: Authenticator.of(store, hasher, store::updatePassword)
  problem: A hash upgrade overwrites the stored hash without any check, so a login that is still running can undo a password change. It writes back a fresh hash of the old password (lost update), and nothing in the Javadoc, README or ARCHITECTURE warns about this.
  evidence: |
    Program: /workspace/gauntlet/runs/20261003-1237-security-losenord-och-autentisering/round-1/scratch-breaker/Exp.java
      User "ada" is stored with Pbkdf2PasswordHasher.of(20_000,16,32).hash("leaked-old").
      auth = Authenticator.of(store, Pbkdf2PasswordHasher.defaults(), store::updatePassword).
      Thread A: auth.authenticate("ada","leaked-old"). This triggers an upgrade, and the 600k-iteration rehash takes about 125 ms.
      After 50 ms, the main thread calls store.updatePassword("ada", <precomputed hash of "new-secret">).
    Run: java -p /workspace/framework/out/spola.security.jar --add-modules spola.security -cp cls Exp
    Output (same in 3 of 3 runs):
      password changed
      attacker login: Authenticated[identity=Identity[name=ada, roles=[USER]]]
      new password: Failed[reason=BAD_CREDENTIALS]
      old password: Authenticated[identity=Identity[name=ada, roles=[USER]]]
    In this run the change to the new password was lost and the leaked password worked again. It happens with ordinary concurrency: a login (by an attacker or by the user on another device) runs at the same moment as a password change, while hashes are being migrated. The window lasts one full defaults() hash. This is security-critical code (REGLER §4).
  fix_hint: Make the upgrade conditional on the hash that was checked. For example, give InMemoryUserStore a compare-and-set (computeIfPresent that replaces only when user.passwordHash() still equals the hash that was verified), and have Authenticator pass the old hash or use that method. If the PasswordUpgrade signature has to stay as it is, at least add a conditional upgrade method to the store, recommend it in the docs, and record the race in Javadoc, README and ARCHITECTURE.
```

Besides BRK-1, everything below checked out and gave no findings:
- **Build:** `java Build.java` exits with 0, and `spola.security` passes 101 of 101 tests.
- **Scope of changes:** `git diff` shows that `Build.java` only gained the new `MODULES` entry, and no other module's `src/` or `test/` changed. `module-info` has no `requires`.
- **PBKDF2:** it matches the RFC 7914 vectors and the JDK's `SecretKeyFactory`.
- **Hash parsing:** I traced the PHC parser for overflow, leading zeros, non-canonical or URL-safe Base64, padding, non-ASCII and the 256-character limit. A string I built with a space in it was rejected without echoing the string.
- **Passwords:** the UTF-8 encoder rejects lone surrogates, and the buffer is sized so it cannot overflow.
- **Authenticator:** I traced the dummy-hash paths for unknown, null, empty and too-long usernames and null passwords. `DISABLED` is only returned after the right password.
- **Upgrade failures:** the upgrade exception is logged with only its class name, and `Error` is not caught.
- **Secrets:** `toString()` of `User`, `Identity` and `AuthResult` never shows the password or hash.
- **Docs:** `PROGRESS.md` and `ARCHITECTURE.md` have the required content (10b, the known limitations and the request for human review).

The experiment is in `/workspace/gauntlet/runs/20261003-1237-security-losenord-och-autentisering/round-1/scratch-breaker/`.
