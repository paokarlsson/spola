Code is clear and documented; a few minor points.

```
VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /workspace/framework/modules/security/src/spola/security/InMemoryUserStore.java:58 and :85
  problem: Both `updatePassword(String, String)` and `upgrader(hasher)` can be passed as a `PasswordUpgrade`, but only the second is safe, and the compiler won't catch the wrong choice.
  evidence: The builder's known gaps say "Wiring `store::updatePassword` as a `PasswordUpgrade` still compiles and would reintroduce the race."
  fix_hint: Add a one-line warning to the `updatePassword` Javadoc ("not a PasswordUpgrade"). Optionally change its signature so it can't be used as a method reference for the functional interface.
- id: MNT-2
  severity: MINOR
  where: /workspace/framework/modules/security/src/spola/security/Authenticator.java:84-93
  problem: The public `of` overloads rely on a package-private 4-argument overload to reject a null `upgrade`, while the constructor accepts null. The null-handling is spread over three places.
  evidence: The constructor comment says "the public factories decide that".
  fix_hint: Keep as is, or use a private static `requireUpgrade` helper. This is optional.
```
