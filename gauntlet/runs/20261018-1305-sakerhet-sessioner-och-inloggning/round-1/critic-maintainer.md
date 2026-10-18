VERDICT: FAIL
FINDINGS:
- id: MNT-1
  severity: MAJOR
  where: /home/user/spola/framework/modules/web/src/spola/web/CacheControl.java:59-122 and /home/user/spola/framework/modules/security-web/src/spola/security/web/WebSecurity.java:697-760 (the nested `WebSecurity.CacheControl`); tests /home/user/spola/framework/modules/web/test/spola/web/SessionCacheTests.java:43-74 and /home/user/spola/framework/modules/security-web/test/spola/security/web/CacheHeaderTests.java:49-67
  problem: The security-critical `Cache-Control` parser (`directives`, `argumentEnd`, `noStore`, `isTokenChar`, `withoutSharedCache`, about 100 lines) exists twice, and the two copies are tested against different tables. A fix or tweak in one copy will not be caught in the other.
  evidence: The `spola.web` copy runs 24 cases, including an escaped quote (`no-cache="a\"b", private`), empty list elements and whitespace (`,  private ,\t,max-age=60`), an open quoted string, an empty argument (`max-age=`), `=private` and `no-store, public`. The `spola.security.web` copy, which guards `XSRF-TOKEN` and logged-in responses (G3/G4), runs only the 15 cases in `CacheHeaderTests.CASES`. None of those five parser branches appears in them; I grepped the security-web tests and found no match. The builder's mutation list covers only "cookie-rule-old" and "no-identity-rule", not the parser internals. The only link between the copies is a Javadoc sentence ("the modules share no internal code").
  fix_hint: Run the same case table against both copies (copy `SessionCacheTests.CASES` into the security-web test so every parser branch is hit through `XSRF-TOKEN` and through `forIdentity`). Better, expose one public helper in `spola.web`; `security.web` already has `requires transitive spola.web`, so `exports` and `requires` stay unchanged. Then delete the copy.

- id: MNT-2
  severity: MINOR
  where: /home/user/spola/framework/modules/security-web/src/spola/security/web/WebSecurity.java:652-783, /home/user/spola/framework/modules/security-web/src/spola/security/web/Csrf.java:395,402, /home/user/spola/framework/modules/security-web/test/spola/security/web/SourceRulesTests.java:42
  problem: The class structure was bent to keep a file counter at 12: a 130-line parser is nested in `WebSecurity` (now 994 lines), and `Csrf.Exchange` reaches into `WebSecurity.CacheControl`.
  evidence: ARCHITECTURE.md says "ligger som nästlad klass i `WebSecurity`, så att antalet huvudkällfiler (som `SourceRulesTests` räknar) är oförändrat", and build.md says "nested so that `SourceRulesTests`' count of 12 main files stays". A separate package-private `CacheControl.java` plus `assertEquals(13, ...)` is the natural structure.
  fix_hint: Make `CacheControl` a top-level package-private class and change the counter to 13. Note the change in build.md as a deliberate test change.

- id: MNT-3
  severity: MINOR
  where: /home/user/spola/framework/modules/web/src/spola/web/SessionEntry.java:27-29 (`lastUsed` and `lastUse`)
  problem: Two fields with nearly identical names mean different things: `lastUsed` is an `Instant` for expiry, `lastUse` is a `long` for the LRU order. `lastUseAs` and `touch` update both.
  evidence: `private Instant lastUsed;` and `private long lastUse;`. A wrong edit (comparing an expiry against `lastUse`) is easy to make and compiles.
  fix_hint: Rename `lastUse` to `useOrder`, and `lastUseAs` to `useOrderAs`.

- id: MNT-4
  severity: MINOR
  where: /home/user/spola/framework/modules/web/src/spola/web/Sessions.java:662
  problem: The local `int count` in `limit` shadows the field `count` (`AtomicInteger`, the total number of sessions), which this class uses everywhere for the session limit.
  evidence: `int count = 0;` inside `limit`, next to `private final AtomicInteger count` at line 255. A later edit in that method that means the field would silently use the local.
  fix_hint: Rename the local to `live` or `userSessions`.

- id: MNT-5
  severity: MINOR
  where: /home/user/spola/framework/modules/web/src/spola/web/SessionEntry.java:184-193 and Sessions.java:677
  problem: `endIfUnusedSince` returns a boolean whose caller ignores it. The value is also muddled: `true` means both "ended" and "left as it is because it was not the user's or had ended".
  evidence: Javadoc says "@return {@code false} if it was used since {@code seen}", but `oldest.endIfUnusedSince(user, oldestUse);` discards it, and the `while (true)` loop re-reads the state instead.
  fix_hint: Make it `void` (the loop already looks again), or make the return value mean "ended" and use it.

- id: MNT-6
  severity: MINOR
  where: /home/user/spola/framework/modules/web/src/spola/web/Sessions.java:410-411 (`renewFor` Javadoc) versus /home/user/spola/framework/modules/security-web/src/spola/security/web/WebSecurity.java:441
  problem: The `renewFor` Javadoc says a second call for the same user in the same request "changes nothing more". The code runs `renew()` again and issues another id, and the `login` Javadoc says "(and renews the id again)".
  evidence: `SessionState.renewFor` always ends in `renew()`, which sets `issued = id` again. The two Javadocs contradict each other.
  fix_hint: Write "gets a new id again; the user's count does not change".

- id: MNT-7
  severity: MINOR
  where: /home/user/spola/framework/modules/web/src/spola/web/SessionEntry.java:9-19 and Sessions.java:232-239 (comments), versus ARCHITECTURE.md "Låsordningen"
  problem: The in-code description of the lock order contradicts the real order. A maintainer who follows the comments can introduce a deadlock.
  evidence: The `SessionEntry` Javadoc says "the locks of the store and of the index of users' sessions are taken inside it, never the other way round". `Sessions.limit` takes the `UserSessions` monitor first and then the entry locks (`lastUseAs`, `endIfUnusedSince`). ARCHITECTURE.md says "SessionState före UserSessions före en SessionEntry". `UserSessions`' own comment does not mention the order. Adding `synchronized (sessions)` to `leave` (called from `end()` under the entry lock) would be a natural edit and would deadlock.
  fix_hint: Put the full order (`SessionState`, then `UserSessions`, then `SessionEntry`, then the map-internal locks) in one comment on `UserSessions`. Reword the `SessionEntry` sentence to say that only the map-internal locks are taken inside the entry lock.

- id: MNT-8
  severity: MINOR
  where: /home/user/spola/framework/modules/security-web/test/spola/security/web/ReviewSupport.java:156-235, ReviewTests.java, ReviewLeakTests.java, ReviewReadmeTests.java, and the `CacheControl.java:10-11` / `WebSecurity.java:647` Javadoc
  problem: The new names are tied to a one-off event ("Review", "uppdrag 15a, G3", "(15a)"), and the raw-socket helper is copied a third time. Production Javadoc points at "G3/G4", which are defined only in `gauntlet/granskningar/`, outside `framework/`.
  evidence: `ReviewSupport.raw` and `exchange` repeat `CsrfSupport.raw` and `exchange` (and `LoginSupport.raw`). Existing tests (`LoginConcurrencyTests`, `CsrfConcurrencyTests`, `SchemeConcurrencyTests`) now depend on `ReviewSupport.loginSecurity`. 15b to 15d will be added to `ReviewTests` or to new "part 2" suites.
  fix_hint: Name the helpers and suites after the behavior (`UserLimitSupport`, "sessions per user"). Reuse `LoginSupport.raw` instead of a third `exchange`. Describe the rule in Javadoc ("cookie responses must not be storable by a shared cache") instead of "G3".

Considered and not raised: the 503-on-sweep logic in `Sessions.create` is dense but has an explanatory comment. The size of the Javadoc and the `Sessions`/`SessionState` split follow the existing style. ARCHITECTURE.md, PROGRESS.md and the READMEs are consistent with each other and with the code. The design choices are documented with reasons. No magic, reflection or naming conventions were introduced.
