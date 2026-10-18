# Round 1 build: 13b, `Secure` on the session and CSRF cookies from the scheme

## Changes

### Main code (behavior)
- `modules/web/src/spola/web/Sessions.java`
  - The filter reads `request.scheme().equals("https")` once, when it starts, from the request it got, and passes the result to the request's `SessionState`. Under `WebSecurity`, that is the request the security filter got.
  - `sessionCookie(id, https)` builds the cookie from the template with `.secure(template.secure() || https)`.
  - `removalCookie(https)` returns one of two immutable removals built at startup: `removal` (as before) or `secureRemoval` (the same plus `Secure`).
  - The template is unchanged. Nothing is stored per request outside the request's own state.
- `modules/web/src/spola/web/SessionState.java`: a new `final boolean https`, which `finish` uses.
- `modules/security-web/src/spola/security/web/Csrf.java`
  - `Protection.begin(request)` records `request.scheme().equals("https")` in the request's `Exchange`, as a new `final boolean https`.
  - `Exchange.finish` calls `newCookie(https)`, which uses `.secure(template.secure() || https)`.
  - `WebSecurity` passes `begin` the request the session filter handed on, which has the same scheme as the one the security filter got. `WebSecurity` needed no code change.
- No public types or methods were added. `requires`/`exports` are unchanged, and nothing changed in `Build.java`, `ForwardedHeaders`, `TrustedProxies` or `spola.http`.

### Javadoc
- `Sessions`
  - The class example now shows `ForwardedHeaders` first instead of "`secure(true)` behind a TLS proxy: say so".
  - "The filter" now says to register it after `ForwardedHeaders`.
  - The sentence "never from the scheme" in "The cookie out" is gone.
  - A new section "`Secure`" covers:
    - the rule;
    - which scheme applies, including under `WebSecurity`, and that login and logout don't change it;
    - TLS versus behind a proxy;
    - the `ForwardedHeaders` order;
    - `secure(true)`;
    - that the rule can't be turned off;
    - that the startup checks are unchanged;
    - that a removal over http has no `Secure`.
- `SessionOptions`: the `cookie` param states the rule and the order, replacing "never from the request's scheme". A short paragraph after the example adds the same.
- `Csrf`: the sentence "never from the scheme" is gone. A new section "`Secure`" covers the rule, the statuses, login and logout, which scheme applies, TLS versus proxy, the order, `secure(true)`, and that the rule can't be turned off (unlike Spring's cookie customizer). The `withCookie` example comment "behind a TLS proxy" became "also over http".
- `WebSecurity`
  - "The filter" now says only `ForwardedHeaders` comes before `security.filter()` behind a proxy.
  - The step list notes `Secure`.
  - There is a new bullet in "Login with a session".
  - A new section "`Secure` from the scheme" includes the router snippet.
  - `filter()` and `Builder.csrf` mention the rule.
- Both `module-info.java` files: comment text only. `requires` and `exports` are unchanged.

### README and example comments (example code unchanged)
- `CartApp.java`, `LoginExample.java`, `CsrfExample.java`: the comments that said `Secure` is never set from the scheme now state the rule. The README copies changed identically, and the existing README-vs-source tests stay green.
- `modules/web/README.md`
  - The intro has a 13b sentence.
  - "Cookien ut" no longer claims the removal copies `Secure` only from the function.
  - A new bullet "**`Secure`**" covers the rule, the scheme, the order, localhost, that it can't be turned off, and the removal over http.
  - A new bullet in "Bakom en omvänd proxy".
  - Deviation row "`Secure` på sessionscookien" rewritten: it is now the same rule as Tomcat, and it can't be turned off.
  - 13a's row "`Secure` på cookies efter schemat … Det kommer i 13b." became "`Secure` på cookies efter schemat bakom en proxy", about the order versus `RemoteIpValve`.
- `modules/security-web/README.md`
  - The intro has a note that only `ForwardedHeaders` comes before `security.filter()`, plus a bullet about `Secure`.
  - In "CSRF-skydd för en SPA":
    - the bullet "…och utan `Secure`. Bakom en TLS-proxy skrivs … `Secure` sätts aldrig efter schemat." was replaced by a bullet that states the rule;
    - a new part "### Bakom Caddy" shows `CsrfExample.router(Router.builder().filter(ForwardedHeaders.trusting(TrustedProxies.privateNetworks())), …)` with `CsrfExample.sessions(false, …)`, the three `Secure` lines, and bullets on order, trusted proxy, localhost, `secure(true)` and other cookies;
    - 2 comparison rows;
    - 2 deviation rows: "`Secure` efter schemat kan inte stängas av" and "`ForwardedHeaders` före säkerhetsfiltret".

### Tests
- New `modules/web/test/spola/web/SessionSchemeTests.java`, with 10 tests registered in `SessionTests` before `SessionReadmeTests`/`SessionLeakTests`. It covers criteria 5 (sessions-only) and 6:
  - `getOrCreate`, a use, `renew`, `invalidate`, `renew` without a session, and created-then-invalidated, with:
    - `X-Forwarded-Proto: https`;
    - `HTTPS`;
    - `http`;
    - no header;
    - an untrusted sender.
  - `secure(true)` over every scheme.
  - A function with Path/Domain/Max-Age/SameSite=Strict: over https it gives exactly the lines that the same function plus `secure(true)` gives over http.
  - The per-request decision: removal over http of a cookie set over https (the known limitation), and the reverse.
  - `ForwardedHeaders` after `sessions.filter()`.
  - 200 virtual threads alternating schemes.
- New in `modules/security-web/test/spola/security/web/`: 18 tests, registered via `SchemeTests` in `CsrfTests` before `CsrfReadmeTests`/`CsrfLeakTests`, so that the leak tests read the new replies, recorded via `CsrfSupport.keep`.
  - `SchemeFlowTests`, criteria 3 and 4: `CsrfExample.router(Router.builder().filter(ForwardedHeaders.trusting(TrustedProxies.privateNetworks())), …)` with `CsrfExample.sessions(false, …)`. It uses a real `HttpServer` (127.0.0.1, port 0) and `HttpClient` without a cookie handler; cookies are sent by hand.
    - The flow is health → login → me → logout → 401 (also with the old id).
    - Lines compare against explicit constants and against a reference run of the same app with `secure(true)` in both functions over http, normalised so that only the id and token differ.
    - `X-Forwarded-Proto: http`, no header, and `TrustedProxies.of("10.0.0.0/8")` with `https` give exactly 11a/11c's `LoginFlowTests.SESSION_COOKIE`, `REMOVAL` and `CsrfSupport.XSRF_COOKIE`.
    - Plus one flow over direct TLS (self-signed key from `keytool`, as `spola.http`'s TlsTests do): `Secure` without any proxy.
  - `SchemeCookieTests`, criteria 5, 7 and 9:
    - `XSRF-TOKEN` with `Secure` on 200, 401, 401 for wrong credentials, 403 from a rule, 403 from the CSRF check, 404, 405 and a mapped 500 (exact raw line). The same exchanges give no `Secure` over http and with no header.
    - A well-formed cookie still gets no new one.
    - `secure(true)` gives one `Secure`.
    - `ForwardedHeaders` after `security.filter()`: the handler sees `https`, and none of `XSRF-TOKEN`, `SESSION` or the removal gets `Secure`.
    - `ForwardedHeaders` after `sessions.filter()` without `WebSecurity`, with the correct order for contrast.
    - A login response with both cookies.
    - `SameSite=None` without `secure(true)` is an IAE for `Sessions` and for `Csrf`; `__Host-`/`__Secure-` too. With `secure(true)` both build, and the lines are `Secure; …SameSite=None` over http.
  - `SchemeConcurrencyTests`, criterion 8: 200 virtual threads run the whole flow. Each request's scheme alternates within and between threads. Every response's lines exactly equal the `Secure` or plain template for that request's scheme. The count is 600 with `Secure` and 600 without.
  - `SchemeReadmeTests`: the "Bakom Caddy" part, the new CSRF bullet, the absence of the old sentences, the new rows, and the intro.
- Changed existing test files, within the three permitted kinds:
  - registration: one line each in `SessionTests` and `CsrfTests`;
  - README tests for the rewritten rows: one string in `SessionReadmeTests` ("Inget automatiskt `Secure` över HTTPS" became the new row text), and one string in `ForwardedReadmeTests` ("Det kommer i 13b." became the new row title and text);
  - comments in `CartApp`, `LoginExample` and `CsrfExample`.

  No existing exact `Set-Cookie` expectation was touched.

### Docs
- `ARCHITECTURE.md`
  - New section "## Bakom en omvänd proxy del 2: `Secure` efter schemat (`spola.web`, `spola.security.web`)". It has a table of internal changes and decisions on:
    - the rule;
    - which scheme and the chain order;
    - direct TLS;
    - why 11a/11c change;
    - why it can't be turned off (Tomcat has no switch; Spring's customizer);
    - `secure(true)`;
    - unchanged startup checks;
    - no shared mutable state;
    - the removal follows the request;
    - tests and mutations;
    - changes to existing test files.
  - The 11a and 11c decisions now say explicitly that 13b replaced them, with a reference to the new section.
  - 13a's intro points to part 2.
- `PROGRESS.md`
  - New "Uppdrag 13b … — behovet levererat" section. It says the need `2026-10-13-proxyhuvuden` is delivered in full (13a–13b) and that the code is security critical and should be reviewed by a human.
  - Fixed: the 11a delivered line "`Secure` bara uttryckligen", the 11c delivered line, 13a's "13b … återstår", and the "Återstår" 13b item.
  - The 13a known problem "`Secure` … det kommer i 13b" now points to 13b.
  - The 11a known problem "Inget automatiskt `Secure` efter schemat eller `X-Forwarded-Proto` (eget behov)" now says it came in 13b.
  - New known-problems block for 13b: wrong order gives no `Secure`; the rule can't be turned off; a removal over http of a cookie set over https gets no `Secure` and the browser may ignore it; other cookies are not covered.

## Verification

Full build with JDK 25: `cd framework && export PATH=/opt/jdk-25/bin:$PATH && java Build.java`. The final run came after the last edit.
```
EXIT 0
guardrails: no violations
spola.guardrails: OK - 17 tests, 17 ok, 0 failed, ...
spola.test: OK - 77 tests ...
spola.di: OK - 48 tests ...
spola.config: OK - 68 tests ...
spola.util: OK - 80 tests ...
spola.codegen: OK - 41 tests ...
spola.json: OK - 417 tests ...
spola.http: OK - 290 tests ...
spola.validation: OK - 251 tests ...
spola.web: OK - 1093 tests, 1093 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 16635 ms
spola.security: OK - 305 tests ...
spola.security.web: OK - 151 tests, 151 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 10679 ms
spola.demo: OK - 43 tests ...
BUILD OK in 311716 ms
```
`spola.web` has 1093 tests, 10 of them new (`SessionSchemeTests`), and `spola.security.web` has 151, 18 of them new (`SchemeTests`).

Other checks:
- Criterion 10: `grep -rn "never from the scheme\|never set from the scheme\|aldrig efter schemat" framework/modules` finds nothing (exit 1).
- Criterion 2:
  - `git diff` of both `module-info.java` files shows only comment lines; `requires` and `exports` are identical.
  - `git diff -- 'framework/modules/*/src/*' | grep '^+' | grep -w public` finds nothing.
  - `git diff --stat` touches only `framework/ARCHITECTURE.md`, `framework/PROGRESS.md`, and files under `modules/web` and `modules/security-web`. `Build.java` is unchanged.
- Repeated runs: besides the two full builds, the web tests ran 3 more times and the security-web tests 5 more times, directly with a scratch launcher outside `framework/`. All were green, and the concurrency tests were stable.
- Mutation runs (each mutation applied, both modules compiled and tested, then restored; restoration verified with grep):

  | Mutation | Failing tests |
  |---|---|
  | `Sessions.isHttps` → `false` | web 6, security-web 5 |
  | `Csrf.begin` passes `false` | security-web 5 |
  | `removalCookie` ignores `https` | web 5, security-web 3 |
  | `sessionCookie` uses `.secure(https)`, ignoring the template | web 7, security-web 2 |
  | `Csrf.newCookie` uses `.secure(https)` | security-web 5 |

## Addressed
Round 1: no findings yet.

## Rebuttals
None.

## Known gaps
- Ordering cannot be enforced: a filter doesn't know what is outside it. Wrong order gives no `Secure` behind a proxy. This is documented in the Javadoc, both READMEs, ARCHITECTURE and PROGRESS, and tested for both orders.
- A removal over http of a cookie set over https has no `Secure`, and the browser may ignore it. This is a known limitation per the spec; it is tested and documented.
- The README "Bakom Caddy" snippet uses `HttpServerOptions.DEFAULT.withPort(8080)` (loopback), mirroring the existing `LoginExample` production snippet. Caddy in another container would need `withAddress(...)`. This is the same pre-existing style as 13a's MINOR BRK-1, and I did not widen scope to change it.
- The direct-TLS test spawns `keytool` (about 1 s), as `spola.http`'s TLS tests do.
