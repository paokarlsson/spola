VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /home/user/spola/framework/modules/web/src/spola/web/Sessions.java:638 (`release(Key, SessionEntry)`), SessionEntry.java:184 (`release()`), SessionState.java:155 (`release()`)
  problem: Three package-private methods named `release` mean three different things in one package, so a maintainer can easily call or edit the wrong one.
  evidence: `Sessions.release(key, entry)` removes an ended entry from the store and counts it out. `SessionEntry.release()` is `logins--`, the end of a login hold. `SessionState.release()` ends the hold and then applies `owner.limit`. `SessionEntry.end()` has `owner.release(key, this)` and the `release()` method next to each other.
  fix_hint: Rename the store method to `Sessions.remove` or `drop`. Optionally rename `SessionEntry.release` to `unhold`, to pair with `hold`.

- id: MNT-2
  severity: MINOR
  where: /home/user/spola/framework/modules/web/src/spola/web/SessionEntry.java:172-176 (the `user != null && !name.equals(user)` branch of `hold`)
  problem: The branch that moves an entry out of another user's index looks unreachable. It is untested defensive code in a class that depends on lock order.
  evidence: `user` only changes in `hold`. `hold` is only reached through `renewFor` (after `renew` has raised the generation, so a stale view returns `null`) or on a freshly created entry. `SessionState.renewFor` invalidates a session of another user before it gets there. build.md "Known gaps" says it is "kept so that the index stays consistent under any call order", and no mutation or test covers it.
  fix_hint: Remove the branch and make `hold` throw `IllegalStateException` for a different user, so a wrong call is loud. Or keep it, and say in the comment that nothing reaches it today.

- id: MNT-3
  severity: MINOR
  where: /home/user/spola/framework/modules/web/src/spola/web/SessionState.java:31-36 (and `recordHold`/`release`)
  problem: One concept, the request's hold, is spread over three parallel nullable fields (`held`, `heldUser`, `heldLimit`) that must always change together.
  evidence: `recordHold` sets all three. `release()` reads `held` and, after clearing only `held`, reads `heldUser` and `heldLimit`. A later edit that sets one of them and not the others compiles.
  fix_hint: Use a private `record Hold(SessionEntry entry, String user, int limit)` and a single `Hold held` field.

- id: MNT-4
  severity: MINOR
  where: /home/user/spola/framework/modules/security-web/src/spola/security/web/WebSecurity.java:153, 424, 700, 714; /home/user/spola/framework/modules/security-web/src/module-info.java:40; the READMEs, ARCHITECTURE.md and PROGRESS.md
  problem: The default of 10 is written as a literal in at least five Javadoc places in code, next to `DEFAULT_MAXIMUM_SESSIONS = 10`. Changing the default means searching all of them.
  evidence: "(10 unless set)" in `login`, `Builder.sessions`, `Builder.maximumSessions` and the class documentation. The constant is package-private, so `{@value}` cannot be used.
  fix_hint: Give the number once, in `Builder.maximumSessions`, and let the other places say "the default of `maximumSessions`".

- id: MNT-5
  severity: MINOR
  where: /home/user/spola/framework/modules/security-web/test/spola/security/web/CacheControlTests.java:94-123
  problem: The drift guard for the two `CacheControl` copies fails with an unreadable message. Its comment stripping is also naive.
  evidence: `assertEquals(original, without(copy, ...))` compares two single-line strings of about 2,500 characters. A failure gives no hint where the copies differ. `code()` removes `//[^\n]*`, which would also cut a string literal containing `//`.
  fix_hint: Compare method by method or line by line (split on `;` and `}`), or report the index of the first difference in the message.

- id: MNT-6
  severity: MINOR
  where: /home/user/spola/framework/modules/web/test/spola/web/SessionLoginRaceTests.java:12-14 and /home/user/spola/framework/modules/security-web/test/spola/security/web/UserLimitRaceTests.java:75-112
  problem: Test fixtures are shared between test classes through static imports (`SessionUserTests.app/login/whoami`) rather than a support class. The race helpers `atOnce`, `loggedIn`, `login` and `me` are written twice, and `UserLimitRaceTests.login/me` partly repeat `UserSessionSupport.login/me`.
  evidence: `import static spola.web.SessionUserTests.app;` and the same names in both modules' race tests. `UserSessionSupport` already has `login` and `me` against `/api/auth/*`.
  fix_hint: Move `app/login/whoami` to `SessionSupport`. Move `atOnce` and `loggedIn` into the support classes where the modules allow it.

Notes (not findings): the round-1 MAJOR MNT-1 (the `Cache-Control` parser in two copies) is handled in the way its fix_hint named. `CacheControlTests` runs the full 24-case table against the copy and compares the code of the two files. ARCHITECTURE.md gives the reason ("Två kopior av tolkningen"), and `requires`/`exports` stay unchanged as the spec demands. MNT-2 to MNT-8 of round 1 are fixed as described in build.md: `CacheControl` is a top-level class, `useOrder`, `live`, a `void` `endIfUnusedSince`, the `renewFor` Javadoc, the lock order written once above `UserSessions` and consistent with ARCHITECTURE.md, and the test renames. I found no finding IDs left in production Javadoc or in test Javadoc. There is no reflection, no new annotations and no naming conventions. ARCHITECTURE.md, PROGRESS.md and the README sections agree with the code (the hold, G2 decided by the persistent `user`, the cache rules). The lock order in the comment matches the calls I traced: `SessionState`, then `UserSessions`, then `SessionEntry`, then the map-internal locks.
