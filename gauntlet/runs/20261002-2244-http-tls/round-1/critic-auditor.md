VERDICT: FAIL

CRITERIA:
- 1 (`java Build.java` exits 0, 172 07a tests unchanged): NOT MET. My run of `java Build.java` ended with `BUILD FAILED: tests failed in spola.http` and exit 1. The log shows 281 tests, 280 ok, 1 failed. The builder's report says it passed twice, so the failing test is flaky. The 07a tests themselves appear intact.
- 2 (no reflect, invoke, `sun.*` or `jdk.internal`; no system properties; no `requires` on framework modules): MET. A grep of `src` found no such imports or `setProperty` calls. `module-info.java` only has `requires static java.net.http`.
- 3 to 14 (the TLS test criteria): not checked individually. Apart from the one failure above, the TLS test classes ran green in my build, including HttpClient over HTTPS, graceful stop and the 200-client test.
- 15 (test material created at test time): PARTIAL, unverified. `git status` shows no certificate or key files in the repo, and no `/tmp/spola-http-tls-*` directories were left behind. I did not inspect `TestPki.java`.
- 16 to 18 (README, REGLER, PROGRESS and ARCHITECTURE): not checked. Guardrails reported no violations in my run.
- 7 (bad-material table): PARTIAL. The case `[17] invalid Base64 length` fails intermittently, and the table is an explicit acceptance criterion.

FINDINGS:
- id: AUD-1
  severity: BLOCKER
  where: /workspace/framework/modules/http/test/spola/http/TlsMaterialTests.java:101 (`badLength`) and :140; reader in /workspace/framework/modules/http/src/spola/http/Pem.java and TlsMaterial.java
  problem: The "invalid Base64 length" case fails intermittently, which turns the verification command red.
  evidence: In my run the failure was `message: private key .../bad-length.pem is not an unencrypted PKCS#8 RSA, EC, Ed25519 or Ed448 private key`. The test expected `is not valid Base64`. The Base64 decoder accepted the corrupted input, so the error came from key parsing instead. The builder's two earlier runs passed. The mutation drops one character from the first body line of a freshly generated random key (`replaceFirst(... "\n$1")`). Whether that is rejected as invalid Base64 depends on the key's content, and padding and alignment may let the decoder succeed.
  fix_hint: Make the corruption deterministic and always invalid, for example by appending a lone character to the body or by truncating the text to a length that is 1 mod 4 with no `=` padding. Alternatively, have `Pem` check the Base64 length and padding itself and report "not valid Base64" every time. Then run the build several times.
