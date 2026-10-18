# Round 1 – build

## Changes

### `spola.web` (`framework/modules/web/`)
- `src/spola/web/Sessions.java`: new public static `renewFor(WebRequest request, String user, int maxPerUser)`
  (`renew` at a login). It is the only public addition in `spola.web` (justified in ARCHITECTURE.md:
  `WebSecurity` is in another module, so "package-private" is impossible, and only the store knows
  when a session ends, which the no-leak requirement needs). Package-private: an index
  `users: ConcurrentHashMap<String, UserSessions>` (name → live entries), `join`/`leave`,
  `limit(user, max, keep)` (ends the least recently used entries other than `keep`, under the
  user's `UserSessions` lock, conditional on "not used since it was picked"), a use counter
  `uses` (`AtomicLong`, a strict LRU order also with a frozen clock), `users()`/`sessionsOf()` for
  tests. G5: `trySweep` with `AtomicBoolean sweeping.compareAndSet(false, true)` around `sweep`;
  `create()` answers 503 at once when the cleanup is due but another request runs it; the minute
  sweep shares the flag. Class Javadoc: "The cookie out", "The lifecycle", "The limit", new
  section "Sessions per user".
- `src/spola/web/SessionEntry.java`: fields `lastUse` (use order) and `user`; `user(view)`,
  `leave(view)`, `join(view, name)`, `lastUseAs(name)`, `endIfUnusedSince(name, seen)`; `touch`
  stamps the use order; `end()` also leaves the user's index (the single path for invalidate,
  expiry on lookup or sweep, abandon and the limit).
- `src/spola/web/SessionState.java`: `renewFor(user, limit)`: another user's session → `invalidate()`
  then a new one (G2); the same user's session is taken out of the index while the login is
  handled; the user's LRU sessions end down to `limit - 1` before `renew()`; the user is
  "pending" and the session is counted (join + limit again) in `finish`. `finish` applies
  `CacheControl.withoutSharedCache` to every response with a session `Set-Cookie` (G3).
  `invalidate`, `create` and `abandon` clear the pending user.
- `src/spola/web/Session.java`: package-private `entry()` and `user()`.
- `src/spola/web/CacheControl.java` (new, package-private): the G3 rule and a strict parser of
  `Cache-Control` (RFC 9111 §5.2 list of `token ["=" (token / quoted-string)]`, all fields, names
  case-insensitive; malformed → `no-store`). Kept only if `no-store` or unqualified `private` and
  neither `public` nor `s-maxage`; otherwise every `Cache-Control` field becomes one `no-store`.
- `src/module-info.java`: Javadoc only (no `requires`/`exports` change).
- `README.md` ("Sessioner"): new bullets `Cache-Control` and "Sessioner per användare", updated
  "Livscykeln" and "Gränsen"; one comparison row; deviation rows "Gräns för antalet sessioner",
  new "Sessioner per användare", "Städning av utgångna sessioner", "`Cache-Control` för sessionscookien".
- Tests (new): `SessionUserTests` (8: limit and LRU, re-login with own cookie, another user →
  new empty session, anonymous keeps values, at `maxSessions`, index empty after
  expiry/logout/sweep/abandon/limit, bad arguments / no filter / after the response, 200 vthreads
  × 5 users, 50 concurrent logins with limit 1), `SessionCacheTests` (24 cases × create/renew/
  plain/remove + 1), `SessionSweepTests` (3: guard held → 503 without walk; minute sweep skipped
  while held; 200 concurrent refused requests against 100 000 sessions). Registered in
  `SessionTests` before `SessionLeakTests`.

### `spola.security.web` (`framework/modules/security-web/`)
- `src/spola/security/web/WebSecurity.java`: `Builder.maximumSessions(int)` (default
  `DEFAULT_MAXIMUM_SESSIONS = 10`, last call wins; `build()` reports `< 1` and "without
  `sessions(...)`"); `login` calls `Sessions.renewFor(request, identity.name(), maximumSessions)`;
  `authorize` applies `CacheControl.forIdentity` to the response of an allowed request with an
  identity (G4: missing → `no-store`; `public`/`s-maxage`/malformed → `no-store`; other handler
  values kept). Nested package-private `WebSecurity.CacheControl` (copy of the parser with
  `withoutSharedCache` for `XSRF-TOKEN` and `forIdentity`), nested so that `SourceRulesTests`'
  count of 12 main files stays. Class Javadoc: filter step 8, "Login with a session" bullets,
  new section "Caches"; `login`, `Builder.sessions`, `Builder.maximumSessions`, `build` Javadoc.
- `src/spola/security/web/Csrf.java`: `Exchange.finish` uses `WebSecurity.CacheControl.withoutSharedCache` (G3); Javadoc.
- `src/module-info.java`: Javadoc only.
- `README.md`: intro list; "Inloggning med session" (bullets for the limit, the new session for
  another user and cached responses; a `maximumSessions(3)` snippet next to Spring's; new part
  "Cachar" with a table); two comparison rows; deviation rows (general table: `Cache-Control` på
  inloggade svar; login table: default 10, ended at once, new empty session; CSRF table:
  `Cache-Control` när cookien sätts); a bullet in "CSRF-skydd för en SPA".
- Tests (new, suite `ReviewTests`, registered in `SecurityWebTests` before `ReadmeTests`/`LeakTests`):
  `UserLimitTests` (6), `UserLimitConcurrencyTests` (1), `UserSwitchTests` (2), `CacheHeaderTests`
  (15 cases × 4 + 1), `ReviewReadmeTests` (2, including that the "Cachar" table matches the test
  cases), `ReviewLeakTests` (2, criterion 9), helper `ReviewSupport` (records masked replies for
  `LeakTests`, as `CsrfSupport` does).

### Existing tests changed on purpose (criterion 2)
- `CsrfCookieTests` "Cache-Control: no-store is added only when …": a response that sets
  `XSRF-TOKEN` with the handler's `max-age=60` now gets `no-store` (was: kept). Title and message
  updated. Behavior change G3 (see deviation below).
- `LoginConcurrencyTests`, `CsrfConcurrencyTests`, `SchemeConcurrencyTests`: they log in each of
  five users 40 times at once and expect every session to stay alive; with the new default of 10
  per user the oldest end (they failed: threads saw 401 at `/api/auth/me`). They now use the
  example's rules with room for every thread (`ReviewSupport.loginSecurity(sessions, THREADS)` /
  `csrfSecurity(...)`); nothing else changed. Behavior change G1.
- `SecurityWebTests`, `SessionTests`: registration of the new suites only.

### Documentation
- `framework/ARCHITECTURE.md`: new section "Säkerhetsgranskning del 1: sessioner och inloggning"
  (API, why the index lives in `spola.web`, default 10, LRU by use order, limit before and after,
  counting at response time, no leak, lock order, G2, G3/G4 rules and parser, two copies, G5,
  changed tests, tests and mutations); "15a" notes in the superseded decisions of 11a, 11b, 11c.
- `framework/PROGRESS.md`: entry "Uppdrag 15a" (G1–G5 fixed, the default 10, the review does not
  replace a human review, the "en människa bör granska" sentence kept); "Återstår" (15b–15d); new
  known-problems bullet for 15a; updated 10b/11a/11b/11c lines that said there is no per-user limit
  or described the old `Cache-Control` rule.

## Deviations from the mission (also in ARCHITECTURE.md)
- **G3 is stricter than the enumeration.** The mission: missing → `no-store`, `public`/`s-maxage`
  → `no-store`, `private`/`no-store` kept. It does not name `max-age=60`, `no-cache` etc. Those let
  a shared cache store the response (RFC 9111 §3; `Set-Cookie` does not inhibit storage), which
  contradicts the requirement's own sentence "svaret ska inte kunna sparas av en delad cache". So
  for cookie responses only `no-store` or unqualified `private` (and no `public`/`s-maxage`) is
  kept; everything else becomes `no-store`. `private="Set-Cookie"` counts as not private (a
  shared cache may store the rest, RFC 9111 §5.2.2.7). G4 follows the mission literally (only
  `public`/`s-maxage` replaced), plus malformed values → `no-store` (fail closed).
- **G2 lives in `Sessions.renewFor`** (the session's user ≠ the new user → invalidate + new
  session) rather than in `WebSecurity` reading `LOGGED_IN`. Equivalent for `WebSecurity.login`
  (it always assigns the user through `renewFor`), and it also protects apps that use
  `spola.web` directly. AC5 is tested through `WebSecurity`.

## Verification

Full build with JDK 25 (`cd framework && export PATH=/opt/jdk-25/bin:$PATH && java Build.java`), run
twice; the last run after every edit:
```
guardrails: no violations
spola.guardrails: OK - 17 tests, 17 ok, 0 failed ...
...
spola.web: OK - 1129 tests, 1129 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 11697 ms
spola.security: OK - 305 tests, 305 ok, 0 failed ...
spola.security.web: OK - 225 tests, 225 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 6713 ms
spola.client: OK - 330 tests, 330 ok, 0 failed ...
spola.demo: OK - 43 tests, 43 ok, 0 failed ...
exit guard:
  ok - after the decision: exit 0, other hooks and deleteOnExit ran
  ok - before the decision: System.exit(0) ends red (exit 1)
BUILD OK in 302506 ms      (exit 0)
```
New tests in the reports: `sessions per user (15a)`, `Cache-Control with the session cookie (15a)`,
`one cleanup at a time at the limit (15a)` in `spola.web` (36 tests); `security review part 1
(15a)` in `spola.security.web` (74 tests; 151 → 225).

Before the fixes, with only the code changed and the old tests: 4 of 151 security-web tests
failed — the three 40-logins-per-user concurrency tests (401 at `/me`) and `CsrfCookieTests`'
`max-age=60` — which is why those four were changed (above).

Stability: both suites run 5 more times with a scratch script (compile web/security-web against
`out/` jars, run the `spola-test` tool): all OK every time, including the concurrency tests and
the 200-refused-requests test (which asserts 1–2 cleanups).

Mutations (each applied to the source, both suites run, source restored) — all caught:
```
no-limit (limit never ends a session)      web 5 failed, security-web 7 failed
lru-by-creation (touch does not stamp)     web 1, security-web 1 (the LRU tests)
no-switch (no new session for other user)  web 1, security-web 2
cookie-rule-old (keep any Cache-Control)   web 15, security-web 24
no-identity-rule (no G4)                   security-web 9
no-cas (sweep without compareAndSet)       web 3 (incl. the 200-concurrent test)
no-leave-on-end (index not cleaned)        web 5
join-at-once (count at renewFor, not at response) web 1 (50 concurrent logins, limit 1: 500s)
no-pretrim (no limit before creation)      web 1, security-web 1
```

The reviewers' probes against the new jars:
```
LockoutProbe login: mode=login attacker requests ok=10000 other=0 in 10.6s; sessions.size()=10
                    alice login -> 200 ... ok alice          (before: 503)
Probe switch:       mallory (same browser, no logout) reads /notes -> 200 who=mallory note=-   (before: anna's note)
Probe sessions:     authenticated GET /api/auth/me head: ... Cache-Control: no-store             (before: none)
```

`requires`/`exports`: `git diff -U0 framework/modules/*/src/module-info.java | grep -E '^[+-]' | grep -E 'requires|exports|module '`
prints nothing. All changed files are under `framework/modules/web`, `framework/modules/security-web`,
`framework/ARCHITECTURE.md` and `framework/PROGRESS.md`.

## Addressed
Round 1: no findings yet.

## Rebuttals
None.

## Known gaps
- Per-user limit counts only logins (`renewFor`); anonymous `getOrCreate` sessions (a cart on an
  open path) can still fill `maxSessions`, and many accounts together can (no rate limiting:
  out of scope). In PROGRESS.md.
- During concurrent logins of the same user, live sessions can briefly exceed the limit (limit +
  logins in flight); after they finish it is exactly the limit. With limit 1 and two simultaneous
  logins the browser may keep the cookie of the session that was ended (the last `Set-Cookie`
  wins). Documented.
- If the user's oldest sessions were ended before the new session is created and creation still
  gets 503 (`maxSessions`), they are gone; likewise another user's session on a switch.
- G4 keeps `max-age=60` on logged-in responses without a cookie (as the mission says), so a
  shared cache may store such a response. Targeted fields (`CDN-Cache-Control`,
  `Surrogate-Control`) are not changed. Both documented in Javadoc, README and PROGRESS.md.
- The `Cache-Control` parser exists in two copies (`spola.web.CacheControl`, `WebSecurity.CacheControl`).
- `Sessions.sweeping` is a package-private field so that tests can hold the cleanup.
- The test that the name → sessions index becomes empty is in `spola.web` (it needs package access);
  `spola.security.web` tests show the effect through `WebSecurity` (counts, 401 for ended sessions).
