# Round 1 – build

## Changes

**Main code (`spola.security.web`, no new source files, `requires`/`exports` unchanged)**
- `framework/modules/security-web/src/spola/security/web/WebSecurity.java`
  - `Builder.sessions(Sessions)`: records every call. `build()` adds the line
    `sessions(...) is called N times; call it once, with the one Sessions of the router` to its
    `IllegalArgumentException`, after the rule lines. `null` gives an NPE at once.
  - Filter: checks "security filter ran twice" first, then, with sessions, runs
    `sessions.filter()` itself (outermost) with the remaining steps as its chain. Order inside:
    400 for more than one `Authorization` field, then the identity from the session
    (`Sessions.find(request).flatMap(s -> s.get(LOGGED_IN))`), then the mechanisms (only if the
    session gave no identity), then the rules. A session never yields `Rejected`. If the session
    filter throws `IllegalStateException` before it calls on (it already ran because the app also
    registered `sessions.filter()` before the security filter), that exception is rethrown with
    a message telling the developer to register only `security.filter()`, with the original as
    cause.
  - `static login(WebRequest, Identity)`: requires the request to have passed the filter of a
    `WebSecurity` with sessions (otherwise `IllegalStateException`), then
    `Sessions.renew(request).put(LOGGED_IN, identity)`. `LOGGED_IN` is a private
    `AttributeKey<Identity>`. The 503 at the limit comes from 11a's `renew`.
  - `static logout(WebRequest)`: the same check, then `Sessions.invalidate(request)`.
  - Javadoc: class doc (filter steps incl. sessions, new section "Login with a session" with an
    example, register only `security.filter()`, session before mechanisms, sessions only at
    login, snapshot, expiry), `filter()`, `identity()`, `login`, `logout`, `Builder.sessions`,
    `Builder.authentication`, `build()`. `toString()` appends `, sessions` when enabled.
- `framework/modules/security-web/src/module-info.java`: Javadoc only. It names
  `HttpStatusEntryPoint` and the session login, adds a bullet on sessions/`login`/`logout`, and
  lists `Sessions` among the API types. `requires` and `exports` are unchanged.

**Tests (new files, registered through one line in `SecurityWebTests`)**
- `LoginExample.java`: the need's use case with the agreed deviations. It is the README's Spola
  block. `router(...)` takes a `RouterBuilder` so that the tests can pass one that logs to
  `Support.LOG`, which criterion 9 needs.
- `LoginSupport.java`: users (anna, bertil=ADMIN, cecilia, david, erik; every password holds
  `zPwd`), `Pbkdf2PasswordHasher.of(10_000, 16, 32)` (`Support.HASHER`), a `TestClock`, raw
  socket and `HttpClient`+`CookieManager` helpers. Every reply goes to `Support.REPLIES`, so the
  existing 10b `LeakTests` also checks it. It records every session id seen or made up, every
  exception message and every `toString()`.
- `LoginFlowTests` (crit. 3, plus `login`/`logout` leave the request's own identity unchanged),
  `FixationTests` (crit. 4), `LoginRuleTests` (crit. 5 + snapshot), `LoginExpiryTests`
  (crit. 6), `LoginMisuseTests` (crit. 7), `LoginConcurrencyTests` (crit. 8), `LoginReadmeTests`
  (crit. 10), `LoginLeakTests` (crit. 9, last) and the aggregator `SessionLoginTests`.
- `SecurityWebTests.java`: one added line, `SessionLoginTests.tests()`, before `ReadmeTests`
  and `LeakTests` (so 10b's leak test still runs last and sees the new replies too). No other
  existing test file changed.

**Docs**
- `framework/modules/security-web/README.md`: intro and bullets mention sessions. The old
  deviation row "Ingen formulärinloggning eller utloggning ännu" now reads "Ingen
  formulärinloggning (`formLogin()`)", and the existing `ReadmeTests` still finds it. New section
  "## Inloggning med session" holds the Spring `SecurityConfig` (with `sessionManagement`,
  `HttpStatusEntryPoint`, `formLogin`/`httpBasic` disabled, `formLogin().disable()` in a comment)
  and an `AuthController` (`changeSessionId()`, `invalidate()`), followed by `LoginExample.java`
  verbatim, a production snippet, a comparison table and a deviation table.
- `framework/PROGRESS.md`: new section for 11b. "Återstår" now says 11c remains. The
  `spola.security.web` sentence "Ingen formulärinloggning, utloggning, …" is corrected. A new
  known-problems bullet covers 11b: security critical with human review, snapshot, `login` does
  not change the request's own identity, not logged, no CSRF until 11c, and the other gaps.
- `framework/ARCHITECTURE.md`: new section "Inloggning med session (`spola.security.web`)" with
  the API and decisions: why the security filter runs the session filter, the filter order, why
  the session comes before the mechanisms and never gives `Rejected`, `login` with `renew`,
  `logout` with `invalidate`, the snapshot, sessions only at login, no `unauthenticated(...)`,
  no logging, why the example takes a `RouterBuilder`, and the tests. The 10b sentence "Uppdrag
  11 lägger till … sessioner som fler mekanismer" now points to the new section.
- `framework/MONSTER.md` (module catalogue row) and the root `README.md` module table: they now
  list the session login, `WebSecurity.login`/`logout`.

No changes to `Build.java` or to any other module's `src/` or `test/`. The "small addition in
`spola.web`" that the spec allowed was not needed.

## Verification

Full build with JDK 25 (`cd framework && export PATH=/opt/jdk-25/bin:$PATH && java Build.java`):
```
guardrails: source       440 .java files
guardrails: source       15 other files: 0 not allowed, 15 documents (0 parse as Java)
guardrails: no violations
...
spola.web: OK - 823 tests, 823 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 13332 ms
spola.security: OK - 103 tests, 103 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 4120 ms
spola.security.web: OK - 68 tests, 68 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 4528 ms
spola.demo: OK - 43 tests, 43 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 319 ms
exit guard:
  ok - after the decision: exit 0, other hooks and deleteOnExit ran
  ok - before the decision: System.exit(0) ends red (exit 1)
BUILD OK in 241039 ms
exit=0
```
New tests in the build (all `ok`, 21 new, 47 existing unchanged):
```
  login with a session
    the use case
      ok  health, 401 without WWW-Authenticate, failed logins, login, me, logout and the old id
      ok  login and logout leave the request's own identity as the filter found it
    session fixation
      ok  a session from before the login gets a new id; the old id is anonymous, the new one is anna and keeps the value
      ok  an id the client made up never becomes the session: the login gives another id, the made-up one stays unknown
    rules, HttpBasic and the snapshot
      ok  hasRole(ADMIN) on /api/admin: 403 for a logged-in user without the role, the handler with it
      ok  HttpBasic next to the sessions: the session first, Basic without a session as in 10b, 401 with WWW-Authenticate
      ok  the session holds a snapshot: a changed or disabled user stays logged in until the logout
    expiry
      ok  /api/auth/me works 30 minutes minus a second after the last request and gives 401 at 30 minutes; a login with the expired cookie gives a new session
    a wrong setup
      ok  login and logout with a WebSecurity without sessions(...): 500 (IllegalStateException)
      ok  login and logout without the security filter: 500 (IllegalStateException)
      ok  sessions.filter() registered next to security.filter(): 500
      ok  sessions(...) twice: IllegalArgumentException from build() with a line for it
      ok  null arguments: NullPointerException (500)
      ok  login at the session limit: 503 and no login; renewing a session there still works
    concurrency
      ok  200 virtual threads log in as one of five users, see their own name, log out and get 401 (~1.7 s)
    README login with a session  (4 tests ok)
    secrets of the login with a session
      ok  the login example's body never shows the password in toString()
      ok  no password and no session id in a response (but its own Set-Cookie), a log line, an exception message or a toString()
```

Mutation checks (scratch copies of the module, compiled against `out/classes` and run with the
module's suite; the real sources were not touched). Each mutation is caught:

| Mutation | Failing tests |
|---|---|
| `login` uses `getOrCreate` instead of `renew` | fixation, the request's own identity, the session limit (3) |
| mechanisms tried before / instead of the session identity | 10 tests |
| filter never reads the session identity | 10 tests |
| `logout` does not invalidate | 6 tests |
| `login` allowed without `sessions(...)` | misuse test + leak test's sanity check (2) |
| `sessions(...)` twice accepted | the build() test (1) |
| an id echoed in the health body | flow test + `LoginLeakTests` |
| an id in a logged exception message | concurrency test + `LoginLeakTests` |
| a password in a logged exception message | `LoginLeakTests` + 10b `LeakTests` (+2 flow tests) |
| no clear message when `sessions.filter()` is registered first | misuse test (1) |

Other checks: `git diff` touches no `Build.java` and no other module's `src/`/`test/`.
`SourceRulesTests` (unchanged) still sees exactly 11 main source files and no reflection,
logging or `ThreadLocal`. `module-info.java` `requires`/`exports` are byte-identical.

## Addressed
Round 1: no findings yet.

## Rebuttals
None.

## Known gaps
- "Handler not called" (crit. 3) is shown in two ways. On the example's own router, a
  `WebException` mapper counts whether the `/api/auth/me` handler's `requireIdentity` ever
  threw; it stays 0. The same router has a sibling route `/api/other` with a counting handler,
  and that count also stays 0. There is no counter inside the example's `/me` lambda, which
  would have meant putting test code in the README example.
- With `sessions.filter()` registered **after** `security.filter()`, only requests that the
  rules let through get 500. Denied ones get their normal 401/403, because the security filter
  cannot see filters inside it. This is documented in Javadoc, ARCHITECTURE.md and PROGRESS.md.
  In that order the message is 11a's ("register one Sessions.filter(), once"). The clearer
  message only appears when `sessions.filter()` is registered before the security filter.
- A handler that calls `login` and then throws (e.g. 406 from `Responses.json` with an `Accept`
  that excludes JSON) returns an error response that still sets the new cookie, so the user is
  logged in. This is consistent with 11a ("a handler that created a session and then threw gets
  the cookie") and is listed in PROGRESS.md.
- Two concurrent logins in one session give two `Set-Cookie`, and the last one wins. This is an
  11a limitation and is referenced in PROGRESS.md. There is no "log out all sessions of a user"
  and no per-user session limit (out of scope).
- The Spring code in the README is not compiled, since Spring is not available. Its key strings
  are checked by `LoginReadmeTests`.
