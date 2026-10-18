# Build, round 1: CSRF protection with XSRF-TOKEN (spola.security.web)

## Changes

Main code (`framework/modules/security-web/src`):
- `spola/security/web/Csrf.java` (new): the public `final class Csrf` with `cookie()`,
  `withCookie(UnaryOperator<ResponseCookie.Builder>)`, `COOKIE_NAME` (`XSRF-TOKEN`),
  `HEADER_NAME` (`X-XSRF-TOKEN`) and `toString()`. Class Javadoc covers the pattern and what it
  rests on (XSS and CORS origins not covered), the unchecked methods, the check, where it sits in
  the filter, when the cookie is set and replaced, the cookie, and that the server stores no
  tokens. It has two package-private nested classes:
  - `Csrf.Protection`, one per `WebSecurity`: the cookie template and a `new SecureRandom()`.
    It does the check in `begin`: exactly one well-formed header, equal to one of the well-formed
    cookies through `MessageDigest.isEqual`, with every cookie compared. It also mints tokens
    (32 bytes, Base64url without padding).
  - `Csrf.Exchange`, per request (synchronized): the flags allowed, hasCookie, renew and finished.
    `finish` adds at most one `Set-Cookie`, plus `Cache-Control: no-store` when the response has
    no `Cache-Control`. It holds no token.
  - `protection(problems)` applies the cookie function once to a placeholder and reports each of
    these as a build line: null, `HttpOnly`, another cookie, `Max-Age=0`, and a cookie that
    `build()` rejects.
- `WebSecurity.java`:
  - New builder method `csrf(Csrf)` with Javadoc on the pattern, the unchecked methods, the order,
    when the cookie is set and replaced, and that no tokens are stored. Calling it twice gives a
    line in `build()`'s `IllegalArgumentException`.
  - `secure()` now runs the CSRF step after the 400 for several `Authorization` fields and before
    the identity. The old identity-and-rules body moved unchanged into `authorize()`.
  - `login` and `logout` call `renewToken` after `Sessions.renew` / `Sessions.invalidate`.
  - `toString()` appends `, csrf`.
  - Class Javadoc: a new step 3 in the filter list, a "CSRF protection" section, the 403 text, and
    "step 4" (was "step 3") for the session.
- `module-info.java`: Javadoc only (CSRF intro and a bullet). `requires` and `exports` are
  unchanged.

Tests (`framework/modules/security-web/test`):
- New files: `CsrfExample` (the need's complete code with `.csrf(Csrf.cookie())` and the 11b
  deviations), `CsrfSupport`, `CsrfTests` (suite), `CsrfFlowTests` (crit. 3),
  `CsrfCookieTests` (crit. 4), `CsrfCheckTests` (crit. 5, 30 cases), `CsrfModeTests` (crit. 6),
  `CsrfSetupTests` (crit. 7), `CsrfConcurrencyTests` (crit. 8), `CsrfReadmeTests` (crit. 10) and
  `CsrfLeakTests` (crit. 9, runs last).
- `SecurityWebTests.java`: registers `CsrfTests.tests()` after `SessionLoginTests` and before
  `ReadmeTests`/`LeakTests`.
- `SourceRulesTests.java`: one number changed, `11` to `12` main source files. See Rebuttals /
  deviations.

Docs:
- `modules/security-web/README.md`:
  - New section "CSRF-skydd för en SPA" with intro bullets, Angular `withXsrfConfiguration(...)`,
    the need's Spring code (with `CookieCsrfTokenRepository.withHttpOnlyFalse()`,
    `CsrfTokenRequestAttributeHandler`, `addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)`
    and the `CsrfCookieFilter` class), `CsrfExample.java` verbatim, a comparison table and
    deviation rows. The rows cover: fixed names, no `_csrf`, no masking
    (`XorCsrfTokenRequestAttributeHandler`), no per-path exceptions (`ignoringRequestMatchers`),
    no token in the session (`HttpSessionCsrfTokenRepository`), the check before authentication,
    the cookie on the first response without a filter, a new token with your own login handler,
    and strict format.
  - The intro gains a bullet for `csrf(Csrf.cookie())`.
  - Three old texts saying "CSRF kommer i 11c" / "Inget CSRF-skydd ännu" were replaced.
- `framework/PROGRESS.md`:
  - New 11c section: the need delivered in full (11a–11c), security-critical and should be
    reviewed by a human.
  - "Återstår" updated.
  - New known-problems entry with XSS, CORS origins, fixed names, no form parameter and more.
  - The CSRF sentences under 10b, 11b and 08c corrected, and the historical "11c återstår"
    sentences corrected.
- `framework/ARCHITECTURE.md`: new section "CSRF-skydd (`spola.security.web`)" covering the
  pattern and why nothing is stored, what it does not cover, the token, the constant-time
  comparison, the unchecked methods, the order and why the check comes before authentication,
  when the cookie is set, when it is replaced, `Cache-Control`, the cookie function, the fixed
  names, where the code lives, the `SourceRulesTests` change, and the tests.
- `framework/MONSTER.md` (catalog row) and root `README.md` (module table): CSRF and `Csrf` added.

`Build.java` and other modules' `src/` and `test/` are untouched.

## Verification

Full build (`cd framework && export PATH=/opt/jdk-25/bin:$PATH && java Build.java`), last lines:
```
guardrails: source       452 .java files
guardrails: source       15 other files: 0 not allowed, 15 documents (0 parse as Java)
guardrails: no violations
...
spola.web: OK - 823 tests, 823 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 17751 ms
spola.security: OK - 103 tests, 103 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 4702 ms
spola.security.web: OK - 130 tests, 130 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 7614 ms
spola.demo: OK - 43 tests, 43 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 398 ms
...
BUILD OK in 253074 ms        (exit 0)
```
A second full build after the final Javadoc touch is reported under "Final build" at the end.

New tests in `spola.security.web` (62, about 3 s): run under "CSRF protection" in
`SecurityWebTests`. Excerpt:
```
  CSRF protection
    the use case with CSRF protection
      ok      health gives XSRF-TOKEN, a login without the header 403, with it 200 and new cookies, me, a logout with the old token 403 and with the current one 200, then me 401
      ok      an application whose first request is a POST: 403 with the cookie, then the same POST with the header logs in
    the XSRF-TOKEN cookie
      ok      exactly XSRF-TOKEN=<token>; Path=/; SameSite=Lax, without HttpOnly and Secure
      ok      Csrf.cookie().withCookie(c -> c.secure(true)) adds ; Secure
      ok      the first response gets the cookie whatever its status: 200, 401, 403 from a rule, 403 from the check, 404, 405 and a mapped 500
      ok      a request with a well-formed cookie gets no new one, whatever the status
      ok      a request with only a malformed cookie gets a new one
      ok      a response has at most one Set-Cookie for XSRF-TOKEN, also when login runs on a first response
      ok      Cache-Control: no-store is added only when the response sets the cookie and has none
    the check
      403 without the handler or the Authenticator   [1]..[30] all ok
      ok      with a matching header every method of the table reaches the Authenticator and the handler
      ok      GET, HEAD, OPTIONS and TRACE pass without a header and get what the router and the rules give
      ok      a header that matches the first or the second of two XSRF-TOKEN values is accepted
    with and without the protection  (2 ok)
    a wrong setup                    (9 ok)
    concurrency
      ok      200 virtual threads with their own HttpClient and CookieManager run the flow as one of five users (~1.8 s)
    README CSRF protection           (5 ok)
    secrets of the CSRF protection   (2 ok)
```

Mutation check. I ran a script that compiles the module (javac with the build's lint and doclint
flags) and runs `SecurityWebTests` through `spola-test`. Each mutation was applied to the main
code, the suite was run, and the file was restored. Every mutation turned the suite red:
```
M1  no check (always allowed)                  38 failed
M2  case-insensitive unchecked methods          1 failed
M3  first of several X-XSRF-TOKEN accepted      1 failed
M4  no new token at login                       3 failed
M5  cookie on every response                    6 failed
M6  401 instead of 403 on a failed check       38 failed
M7  no Cache-Control: no-store                  3 failed
M8  HttpOnly on the cookie                     10 failed
M9  standard Base64 '+' '/' accepted            3 failed
M10 case-insensitive token comparison           2 failed
M11 no new token at logout                      5 failed
M12 no cookie on the first response            12 failed
M13 no HttpOnly check in build()                2 failed
M14 mechanisms run before the check            34 failed
```

## Addressed
Round 1: no findings yet.

## Rebuttals / deviations (motivated, also in ARCHITECTURE.md)
- **`SourceRulesTests` changed from 11 to 12 files (criterion 2 says existing test files stay
  unchanged).** The mission's binding design decision makes `Csrf` a public class in
  `spola.security.web`. Java requires a public top-level class to live in its own file, so
  `Csrf.java` is a 12th main source file, and 10b's `SourceRulesTests` asserts exactly 11 files.
  Without that one-number change the build is red. All of the test's checks are unchanged and now
  also cover `Csrf.java` (no reflection, method handles, logging, `ThreadLocal`, `ScopedValue` or
  `java.net.http`). The alternative, a nested `WebSecurity.Csrf`, would break the design decision
  and the need's code (`.csrf(Csrf.cookie())` with a plain import). Recorded in ARCHITECTURE.md
  ("Ett tillägg i en befintlig testfil").
- **The cookie function runs once, in `build()`, not per token.** The mission describes the cookie
  as `ResponseCookie.of("XSRF-TOKEN", token).httpOnly(false)` followed by the function, and says
  `build()` test-builds it. As with 11a's session cookie, the function is applied once to a
  well-formed placeholder. Its attributes (`Path`, `Domain`, `Max-Age`, `Secure`, `SameSite`) are
  then copied onto every `ResponseCookie.of("XSRF-TOKEN", token).httpOnly(false)`. For a pure
  function the header is identical. Mistakes show at startup, and no user code runs per response.
  Two extra checks follow 11a: a builder for another cookie, and `Max-Age=0`.
- **Recorded copies of replies.** Every reply of the CSRF tests goes to `Support.REPLIES`, so 10b's
  `LeakTests` sees it. The recorded copy has the values of the response's own `XSRF-TOKEN` and
  `SESSION` cookies replaced by `<own value>`. The test that receives a reply sees it unchanged
  and checks the value's form. The reason: `LeakTests`, an existing file I may not change,
  searches whole replies for the 4-character markers `zPwd` and `zUsr`. With about 1,300 random
  43-character values per run, a value contains a marker by chance in roughly 0.5% of runs. This
  happened once during development: one quick run failed with `response 403 holds zPwd`, and 55
  later runs passed. `CsrfLeakTests` asserts the masking invariant (every recorded `XSRF-TOKEN`
  value is `<own value>`). It also still checks every reply (outside its own `Set-Cookie`), log
  line and recorded message for every token, session id and password.

## Known gaps
- 11b's own tests still record about 300 unmasked random session ids in `Support.REPLIES`, so
  `LeakTests` can still fail by chance in about 1 run in 1,000. This was there before 11c. Fixing
  it needs changes to 11b's `LoginSupport` or 10b's `LeakTests`, which criterion 2 forbids. It is
  noted in PROGRESS.md.
- Exceptions the router maps outside the security filter get no `XSRF-TOKEN` cookie: the 400 for
  several `Authorization` fields or for more than 200 cookies, and an exception from a mechanism,
  matcher or rule. Request body failures get none either. This is documented in the Javadoc,
  README, ARCHITECTURE.md and PROGRESS.md. Mapping them inside the filter would bypass the
  application's exception mappers.
- A handler that sets `XSRF-TOKEN` itself could produce a second `Set-Cookie`. The Javadoc says the
  cookie belongs to the security filter. This is not enforced.
- If `login` or `logout` is called from a thread that outlives the request, it throws
  `IllegalStateException`. A `login` that renewed the session in the tiny window before the
  request thread finished does not rotate the token. This is documented as a known problem.
- In criterion 3 the example's handler cannot be instrumented without changing the need's code.
  So "the handler does not run" is shown through the counting `UserStore` (0 finds, 0 password
  checks), 0 sessions and the 403 problem body. With a valid JSON body the handler always calls
  the `Authenticator`. Criteria 5 and 6 count handler calls directly.

## Final build
Second full build after the last edit (a Javadoc sentence in `Csrf.java`), same command:
```
guardrails: no violations
spola.guardrails: OK - 17 tests ... spola.test: OK - 77 ... spola.di: OK - 48 ... spola.config: OK - 68
spola.util: OK - 80 ... spola.codegen: OK - 41 ... spola.json: OK - 417 ... spola.http: OK - 283
spola.validation: OK - 251 ... spola.web: OK - 823 ... spola.security: OK - 103
spola.security.web: OK - 130 tests, 130 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 7918 ms
spola.demo: OK - 43 tests
BUILD OK in 234704 ms
exit=0
```
