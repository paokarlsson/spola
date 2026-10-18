# Round 2 – build

## Changes

### `spola.web` (`framework/modules/web/src/spola/web/`): the fix for BRK-1 and BRK-2
In round 1, a login took the session out of the user's index (`SessionEntry.leave`, `user = null`) while the login was handled. A concurrent `Sessions.renew` on the same cookie could leave the session logged in but out of the index for good. The new design never clears the owner while the session lives:
- `SessionEntry.java`
  - `user` is set when a login **holds** the entry, and is cleared only by `end()`. It is set either in one step with the renewal (`renewFor(view, name)`, under the entry's lock) or right after the login created the entry, before any other request can know its id (`hold(view, name)`).
  - New `logins` counter with `hold`, `release` and `held()`. `endIfUnusedSince` never ends a held entry. Removed `leave(view)` and `join(view, name)`.
  - MNT-3: `lastUse`/`lastUseAs` renamed to `useOrder`/`useOrderAs`.
  - MNT-5: `endIfUnusedSince` is now `void`. Its condition is one line: alive, the same user, unused since `seen`, not held.
- `SessionState.java`
  - `renewFor(user, limit)`: G2 is decided from the entry's persistent `user`. Another user's session is invalidated. A session that is anonymous or already the user's is renewed and held in one step. Otherwise (none, or it went stale) the user's oldest sessions are trimmed to `limit - 1` and a new session is created and held.
  - `recordHold` notes the hold, and releases an earlier hold from the same request (that entry is the same one, or it has ended).
  - `release()` runs in both `finish` and `abandon`. It ends the hold and applies `limit(user, limit, keep = entry)`. Every hold is released exactly once.
  - Removed `pendingUser`/`pendingLimit`/`countPending`. Removed the round-1 pre-trim of `limit - 1` before renewing an existing session, which a mutation showed to be redundant (see Verification).
- `Sessions.java`
  - `limit` skips held entries as candidates but counts them.
  - MNT-4: the local `count` is renamed to `live`.
  - MNT-7: the lock order is written once, as a comment above `UserSessions` (SessionState → UserSessions → SessionEntry → internal locks of the maps and sets). The `SessionEntry` doc points to it.
  - Javadoc "Sessions per user" and `renewFor` describe ownership from login to end, held sessions, and the limit before creation and at the response.
  - MNT-6: the `renewFor` doc now says "renews the id once more" instead of "changes nothing more".
- `Session.java`: Javadoc of `user()`.
- `CacheControl.java` (MNT-8): no finding IDs in the Javadoc; it points to the security-web copy.

### `spola.security.web` (`framework/modules/security-web/src/spola/security/web/`)
- `CacheControl.java` (new, package-private, MNT-2): the copy of the parser moved out of `WebSecurity` (the nested `WebSecurity.CacheControl` is removed). The code is the same as `spola.web.CacheControl` plus `forIdentity`. The Javadoc has no finding IDs.
- `Csrf.java`: calls `CacheControl.withoutSharedCache`.
- `WebSecurity.java`
  - Nested class removed, and the `Locale` import with it.
  - The class Javadoc ("At most maximumSessions…") and `Builder.maximumSessions` Javadoc now say what happens during concurrent logins.

### Tests
- `spola.web`
  - New `SessionLoginRaceTests`, registered in `SessionTests` after `SessionUserTests`:
    - a deterministic held-login test: limit 1, a slow login parked in its handler while another login of the same user completes;
    - login vs `Sessions.renew` on one cookie, 400 rounds: anna has at most the limit of working sessions, `sessionsOf` equals exactly those, and bertil over each sees nothing;
    - anna and bertil logging in with anna's cookie, 400 rounds, 0 inherited;
    - 100 random clients (login/renew/logout/count): afterwards, per user, the working sessions equal `sessionsOf`, are ≤ limit, `size()` equals the live sessions, and `users()` matches.
  - `SessionUserTests`:
    - new test: a login from an anonymous cart at the limit keeps the cart's values and ends the least recently used session;
    - one message changed ("the abandoned login's session left the index"; it is now in the index briefly, then removed at abandon);
    - Javadoc without finding IDs.
  - `SessionCacheTests`, `SessionSweepTests`: Javadoc only.
- `spola.security.web`
  - MNT-8: `ReviewTests`/`ReviewSupport`/`ReviewLeakTests`/`ReviewReadmeTests` are renamed to `UserSessionTests`/`UserSessionSupport`/`UserSessionLeakTests`/`UserSessionReadmeTests`. Suite title: "sessions per user, another user's login and Cache-Control (15a)".
    - `UserSessionSupport` no longer has its own copy of the raw-socket code and the masking. It sends through the existing `CsrfSupport.raw`, which masks own cookie values into `Support.REPLIES`, and keeps only its own record for the leak test.
    - Note: the renames were done with `git mv`, so they are staged in the index. Nothing was committed.
  - New `UserLimitRaceTests` (BRK-1 and BRK-2 through `WebSecurity.login` and a real `HttpServer`):
    - login vs `Sessions.renew`, 400 rounds, with the default limit: at most 10 working sessions, exactly 10 after three more logins, and bertil over each sees no note;
    - anna and bertil with one cookie, 400 rounds: 0 inherited.
  - New `CacheControlTests` (MNT-1):
    - checks that the security-web copy has the same code as `spola.web`'s, apart from comments, `package` and `forIdentity`;
    - runs `SessionCacheTests`' 24-case table directly against the copy's `withoutSharedCache` and `forIdentity`.
  - `UserLimitTests`, `UserLimitConcurrencyTests`, `UserSwitchTests`, `CacheHeaderTests`: helper rename and Javadoc only.

### Existing tests changed (criterion 2)
- `SourceRulesTests` (security-web): main source file count 12 → 13 (the new `CacheControl.java`, MNT-2).
- `LoginConcurrencyTests`, `CsrfConcurrencyTests`, `SchemeConcurrencyTests`: `ReviewSupport.` → `UserSessionSupport.` on the line changed in round 1 (the rename only).
- `SecurityWebTests`: registration `ReviewTests` → `UserSessionTests`. `SessionTests` (web): registration of `SessionLoginRaceTests`.

### Documentation
- `ARCHITECTURE.md` (15a section):
  - the internal fields;
  - "the limit before and after" rewritten;
  - new decision "the session belongs to the user from the login, and the login holds it until the response", with a sub-bullet on why round 1's "count at the response" design was wrong (BRK-1);
  - lock order, G2 (decided by the persistent `user`), two copies (top-level class plus `CacheControlTests`), changed tests, and the test and mutation list.
- `PROGRESS.md`: the 15a entry (holding, the round-2 fix, tests, the new file in security-web), and the known-problem bullet on in-flight logins (now also covering a single login from an anonymous session).
- `modules/web/README.md` ("Sessioner per användare"): new bullet on ownership and held logins.
- `modules/security-web/README.md` ("Inloggning med session"): one sentence on concurrent logins.

## Verification

Full build, after the last edit (`cd framework && export PATH=/opt/jdk-25/bin:$PATH && java Build.java`):
```
guardrails: no violations
spola.guardrails: OK - 17 tests ...
spola.web: OK - 1134 tests, 1134 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 18953 ms
spola.security: OK - 305 tests ...
spola.security.web: OK - 276 tests, 276 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 8294 ms
spola.client: OK - 330 tests ...   spola.demo: OK - 43 tests ...
  ok - after the decision: exit 0, other hooks and deleteOnExit ran
  ok - before the decision: System.exit(0) ends red (exit 1)
BUILD OK in 293382 ms        (exit 0)
```
Round 1 had 1129 tests in web and 225 in security-web; now there are 1134 and 276. The new suites appear in the reports: "logins at the same time (15a)" in `spola.web`; "logins that race on one cookie" and "the Cache-Control rules of the security filter" inside "sessions per user, another user's login and Cache-Control (15a)" in `spola.security.web`.

**The new race tests fail on round-1 code.** I wrote them first and ran them against the unchanged round-1 sources. For security-web I used the breaker's round-1 `spola.web.jar`.
```
web:  a login of anna and Sessions.renew on her cookie at once, 400 times ... -> "19 working sessions of anna, limit 3"
web:  anna and bertil log in at once with anna's cookie, 400 times ...       -> rounds with anna's value: expected 0, actual 13
sw:   ... with the default limit ...                                          -> "30 working sessions of anna"
sw:   anna and bertil log in at once ...                                      -> expected 0, actual 17
```
All of them pass with the fix: in both full builds, in 4 runs of both suites with a scratch script (compile web/security-web against the `out/` jars, run `spola-test`), and in one more `spola.web` run after the last test edit. All runs were green.

**The breaker's probe** (`round-1/scratch-breaker/probe/Probe.java`, unchanged), against the jars from the final build:
```
renewrace rounds=1500 limit=10 logged-in mallory sessions that work: 10 size=97
after 3 more sequential logins: mallory sessions that work: 10 (limit 10)
sequential bertil login over each: bertil sees mallory's note in 0 of 10
renewrace rounds=3000 limit=2 ... work: 2 ... after 3 more ...: 2 (limit 2) ... note in 0 of 2
g2race rounds=1500 (limit 1000) bertil saw anna's note: 0
g2race rounds=3000 (limit 10)   bertil saw anna's note: 0
basic: login CC=[no-store]; /pub,/smax -> no-store; /priv -> private, no-cache; /maxage -> max-age=60; anon /open unchanged; bertil over anna: note=none anna old=401
slow:  in-flight request on the ended LRU session: 200 slow false
```
Before the fix the probe gave 77 working sessions and 46 inherited notes. `size=97` in `renewrace` counts the anonymous sessions that the probe's `/renew` creates when it loses the race. They have no login and are not a user's, which is the documented "anonymous sessions" gap.

**Mutations.** Each was applied to a scratch copy of `framework/`, then both suites were run. All were run against the final code, and all are caught:
```
no-hold            (held() false, ends held entries)   web 1 (the deterministic held-login test)
no-release-limit   (no limit when the hold ends)       web 4 (anonymous cart at the limit, 50 logins/limit 1, held-login test, 100 clients)
no-switch          (no G2)                             web 3, security-web 4
no-pretrim         (no limit before creation)          web 2, security-web 1
no-limit           (limit does nothing)                web 9, security-web 8
lru-by-creation    (touch does not stamp useOrder)     web 2, security-web 1
no-leave-on-end    (end() keeps the index entry)       web 9
no-hold-on-create  (a created session is not held)     web 12, security-web 11
renew-not-atomic   (renewFor renews but does not hold) web 4, security-web 1
```
In the first pass two more mutations survived: no limit right after the hold, and (in that version) no limit at release. Each was covered by the other. I removed the redundant post-hold call, together with the pre-trim before renewing an anonymous session, and added the two deterministic tests that now catch `no-release-limit` and `no-hold`.

**Other checks:**
- `git diff HEAD -U0 -- framework/modules/*/src/module-info.java | grep -E '^[+-]' | grep -E 'requires|exports|opens|module '` prints nothing.
- The only change outside `framework/` is `gauntlet/.../state.md` (the orchestrator's file).
- The new files have no annotations other than `@Override` and no reflection.

## Addressed
- **BRK-1 [MAJOR]**:
  - The owner (`SessionEntry.user`) is never cleared while the session lives. It is set atomically with the renewal (`SessionEntry.renewFor`) or right after creation.
  - The login's protection is now a hold (`logins`). `limit` counts a held session but never ends it.
  - After all requests finish, every live session holding U's login is in U's index, so U has at most the limit. This is shown by the web index assertion `sessionsOf == working` and the 100-client invariant test.
  - The guarantee that a concurrent login of the same user never ends the session being logged in is kept. It is now tested deterministically.
  - Regression tests through a real `HttpServer` in both modules: login racing `Sessions.renew`, 400 rounds, then at most the limit of working sessions and another user's login over each sees no attribute.
- **BRK-2 [MINOR]**: covered by the same fix. G2 reads the persistent owner, which a concurrent login no longer clears. Tested in both modules (400 rounds each, 0 inherited; on round-1 code 13 and 17).
- **MNT-1**: `CacheControlTests` runs `SessionCacheTests`' full 24-case table against the security-web copy, for both `withoutSharedCache` and `forIdentity`. It also checks that the copy has the same code as the original, so they cannot drift.
- **MNT-2**: `CacheControl.java` is a top-level package-private class in `spola.security.web`. `SourceRulesTests` now counts 13 files (named above).
- **MNT-3**: `useOrder`/`useOrderAs`.
- **MNT-4**: the local is renamed to `live`.
- **MNT-5**: `endIfUnusedSince` is `void`, with a single condition.
- **MNT-6**: the `renewFor` Javadoc now says a second call for the same user "renews the id once more (the attributes stay)".
- **MNT-7**: the full lock order is stated once, in `Sessions`. `SessionEntry` refers to it, and the contradicting sentence is gone.
- **MNT-8**: the `Review*` test classes are renamed after the behaviour (`UserSession*`). There are no finding IDs in production Javadoc, and they are removed from the 15a test Javadoc too. The third raw-socket copy is replaced by a call to `CsrfSupport.raw`.

## Rebuttals
None.

## Known gaps
- While logins of one user are handled, the user can have more sessions than the limit: the limit plus the logins in flight. This now includes a single login from an anonymous session, which counts from the login but trims the oldest only when its response is built. Once responses are built, the user has at most the limit. With limit 1 and two simultaneous logins, the browser can keep the cookie of the session that was ended (documented, unchanged).
- When a same-user re-login's renewal goes stale because another request renewed the id first, the login creates a new session. If `maxSessions` is then full, the pre-trim makes room only from the user's unheld sessions.
- Anonymous sessions (from `getOrCreate`, or from a `Sessions.renew` that lost a race) are not limited per user (unchanged, documented, rate limiting is out of scope).
- `SessionEntry.hold` still moves an entry from another user's index if called for a new name. `renewFor` never reaches that path (it ends another user's session instead). It is kept so that the index stays consistent under any call order.
- The `Cache-Control` parser still exists in two copies (by design). `CacheControlTests` now guards that they stay identical.
