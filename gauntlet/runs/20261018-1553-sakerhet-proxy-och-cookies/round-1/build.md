# Round 1 – build

## Changes

**`spola.web` (main code, no public API change; `requires`/`exports` untouched)**
- `ForwardedHeaders.java`: P1 warning. New fields `log` (`System.Logger`, default `System.getLogger("spola.web")`) and `warned` (`AtomicBoolean`). When the sender is not trusted, `warnOnce` checks `warned.get()`, then whether `X-Forwarded-Proto` or `X-Forwarded-For` is present (`Headers.contains`; values are never read), then `compareAndSet(false, true)` and logs one `WARNING`. Text comes from package-private `warning(InetAddress)`: only `IpLiteral.format(sender)` (IPv4 dotted / IPv6 RFC 5952 without zone) plus fixed text. Package-private `trusting(proxies, log)` and `log()` for the tests (same pattern as `HttpServer.start(..., log)` and `Authenticator.of(..., log)`). Javadoc has a new section on the warning, "Immutable" is replaced by "thread-safe; its only state is the flag", and "the headers are not read at all" now says "the values are not read".
- Javadoc: `Sessions` (P4: the store holds only hashes; while a request is handled the id is a `String` in `Cookie`/`Set-Cookie`; `secure(true)` + `__Host-SESSION` for https-only apps), `SessionOptions` (production setting, cookie tossing), `WebRequest.clientAddress()` and `TrustedProxies.privateNetworks()` (P5: a private client address is not an authorization), `module-info.java`.
- `README.md`: "Sessioner" (production bullet rewritten for apps reached only over https, with the code block, why `secure(true)`, why `__Host-` including cookie tossing, the CSRF counterpart; the "Id:t" bullet on P4; the `Secure` bullet says "and trusts the proxy"), "Bakom en omvänd proxy" (WhoamiApp block resynced; the `Secure` bullet corrected; new bullets "En varning, en gång, …", "Appen lyssnar på `0.0.0.0` i Docker." and "En privat klientadress är ingen behörighet."), intro and two table rows.
- Tests: `WhoamiApp.java` gets `options()` = `DEFAULT.withAddress(InetAddress.ofLiteral("0.0.0.0")).withPort(8080)` with a comment on why, and `main` uses it (P2). The comment in `CartApp.java` now says "that ForwardedHeaders … trusts". New `ForwardedWarningTests.java` is registered in `ProxyTests`. `ForwardedLeakTests` gets a marker test for the warning, and its untrusting filter logs to its own captured log. `ForwardedReadmeTests` checks `WhoamiApp.options()`, compares the README's warning text with `ForwardedHeaders.warning(...)`, and checks the new bullets plus the absence of the old sentences. `SessionReadmeTests` checks that the README's production block is the exact code that builds and gives `__Host-SESSION=<id>; Path=/; Secure; HttpOnly; SameSite=Lax` (and the removal) over plain http.

**`spola.security.web` (no main-code change except Javadoc)**
- Javadoc: `Csrf` (`Secure` section), `WebSecurity` (production snippet for both cookies) and `module-info.java`.
- `README.md`: "Bakom Caddy" rewritten around the compiled example `CaddyExample.java` (P1/P3/P2). It covers why `secure(true)` matters, why `__Host-SESSION` (cookie tossing), the warning, `0.0.0.0`, and "Utan `secure(true)`". That last part corrects the old sentence "secure(true) behövs bara … bakom en proxy utan ForwardedHeaders". A new "I produktion" bullet was added in "CSRF-skydd för en SPA", plus a production sentence in "Inloggning med session" and in the module intro. `LoginExample`/`CsrfExample` `@param secure` now say "that ForwardedHeaders … trusts" and "as an application reached only over https should" (README copies resynced).
- New `CaddyExample.java` (test source, as README shows it). It has `sessions(clock)` (`__Host-SESSION` and `secure(true)`), `security(sessions)` (`Csrf.cookie().withCookie(c -> c.secure(true))`, CsrfExample's rules), `router(proxies, sessions, authenticator)` (`CsrfExample.router` behind `ForwardedHeaders`), `options()` (0.0.0.0:8080) and `start(UserStore)` (trusting only `TrustedProxies.of("172.18.0.5")`).
- New `CaddyExampleTests.java` (registered in `SchemeTests`, before the leak tests).
- Changed test support: `SchemeFlowTests.flow` takes the session cookie's name (the old overload passes `SESSION`). `CsrfSupport` and `CsrfLeakTests` now mask and record `__Host-SESSION` too, so the CSRF leak test covers the new replies. `SchemeReadmeTests` looks for the new "Bakom Caddy" text instead of the old non-compiled snippet.

**Docs:** `framework/ARCHITECTURE.md` has a new section "Säkerhetsgranskning del 2" (decisions: once per filter, per instance, address only, the first-sender limitation, WARNING level, logger injection, production setting, CaddyExample, 0.0.0.0, P4, P5, tests, changed test files). It also corrects the P4 sentence in "Sessioner", the "secure(true) behövs bara…" sentence in 13b, and the "oföränderlig" cell in the 13a API table. `framework/PROGRESS.md` has a 15b entry, updates "Återstår" and the 15a note, amends the 13b claim, and adds known problems (one line per filter: the first untrusted sender takes it; `clientAddress()` is not an authorization; without `secure(true)` `Secure` depends on `TrustedProxies`).

## Verification

`cd framework && export PATH=/opt/jdk-25/bin:$PATH && java Build.java` → exit 0 (run twice. The final run came after every change):

```
guardrails: source       538 .java files
guardrails: source       16 other files: 0 not allowed, 16 documents (0 parse as Java)
guardrails: no violations
spola.web: OK - 1155 tests, 1155 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 15685 ms
spola.security.web: OK - 286 tests, 286 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 9377 ms
... (every module OK, 0 failed)
BUILD OK in 283317 ms
```

New tests in the log (excerpt):
```
    ForwardedHeaders warns once about a sender it does not trust
      ok      X-Forwarded-Proto: https from a sender that is not trusted: exactly one WARNING line, with the sender's address and without the value; ...
      ok      ten such requests: still one line, and every request is ignored
      either header, in any case of the name and with any value: one line for the first request, none for the next  ([1]..[8] ok)
      ok      a trusted sender: no line, with valid headers, with a 400 for invalid ones and without headers
      ok      a sender that is not trusted, without X-Forwarded-Proto and X-Forwarded-For: no line (other proxy headers are not read); ...
      ok      once per filter instance: two filters log a line each, one filter on two routers logs one
      ok      200 concurrent requests with the headers from a sender that is not trusted: one line
      ok      50 requests that reach the check at the same moment (a barrier in TrustedProxies): one line
      ok      the line names an IPv6 sender as TrustedProxies.of takes it (RFC 5952, without a zone)
      ok      ForwardedHeaders.trusting(proxies) logs to the System.Logger named spola.web
      ok      marked values from a sender that is not trusted: the warning holds none, for every value as the first one a filter sees
      ok      15b: the Docker example listens on 0.0.0.0 port 8080, ...
      ok      15b: the section has the warning as the filter writes it, ...
      ok      15b: the production setting as the README shows it builds and gives __Host-SESSION with Secure over plain http
      the production setting behind Caddy (15b)
        ok      directly over http, without a proxy: __Host-SESSION and XSRF-TOKEN with Secure on every response
        ok      X-Forwarded-Proto: https from a sender ForwardedHeaders does not trust (Caddy got a new address): still Secure
        ok      from a trusted proxy with X-Forwarded-Proto: https, and with http: Secure either way
        ok      the lines are exactly those of CsrfExample behind a trusted Caddy with https, but for the session cookie's name
        ok      only __Host-SESSION is read: the logged-in id under the default name SESSION gives 401
        ok      __Host-SESSION needs secure(true): without it Sessions.inMemory fails at startup
        ok      options(): 0.0.0.0 port 8080, plain http; the default listens on loopback
        ok      the README shows CaddyExample.java as it is compiled and tested here
        ok      the part Bakom Caddy: ...
        ok      the CSRF section and the session login recommend the production setting
```

Mutation check. I used a scratch harness that compiles a mutated copy of `spola.web` and runs `ForwardedWarningTests`, `ForwardedLeakTests` and `ForwardedReadmeTests`; nothing in the repo changed. The baseline passed 27/27 seven times. Every mutant was caught:
- no `warnOnce` call: 13 failures
- a line per request: 12
- header value appended to the line: 13
- warning also for trusted senders: 1
- only `X-Forwarded-Proto` counted: 5
- every request counted (no header check): 1
- `get()` + `set()` instead of `compareAndSet`, with yields: caught 5/5 after I added the barrier test (2/3 with only the 200-thread test)

Criterion 6: `grep -rni "minnesdump\|memory dump" framework --include=*.java --include=*.md` (excluding `out/`) → 0 hits.
Criterion 2: `git diff` of both `module-info.java` files touches Javadoc only. No reflection, and no new file types (only `.java` and `.md`). Every change is under `framework/modules/web` and `framework/modules/security-web`, apart from `framework/ARCHITECTURE.md` and `framework/PROGRESS.md`.

## Addressed

Round 1, so there were no findings to address. Spec items:
- P1 warning: `ForwardedHeaders.warnOnce` / `warning`. Criterion 3 tests are in `ForwardedWarningTests` plus the marker test in `ForwardedLeakTests`.
- P1/P3 production setting: README sessions / proxy / session login / CSRF / Bakom Caddy, plus `CaddyExample` and `CaddyExampleTests`; web `SessionReadmeTests` builds the README snippet (criterion 4). The sentence about `secure(true)` only being needed without `ForwardedHeaders` is corrected in the security-web README and in ARCHITECTURE 13b.
- P2: `WhoamiApp.options()` and `CaddyExample.options()` bind `0.0.0.0` with a comment and README bullets on why. The README-vs-source tests are unchanged in mechanism and green (criterion 5).
- P4/P5: Javadoc, README, ARCHITECTURE and PROGRESS are corrected (criterion 6).
- Criterion 8: ARCHITECTURE and PROGRESS are updated.

## Rebuttals

None.

## Known gaps

- The warning is once per filter instance (as the spec says). If the first untrusted sender with the headers is a client hitting the app port directly, a later proxy misconfiguration is not logged. This is documented in Javadoc, README, ARCHITECTURE and PROGRESS. The line does not say which of the two headers came.
- Security-web tests that use the public `ForwardedHeaders.trusting(...)` with an untrusted sender, and the web README test, print the real `WARNING` through JUL to the build output (5 lines). It is the same kind of noise as the existing `INFO` lines from `HttpServer`. Web tests that assert on the warning use the package-private logger factory.
- No real-socket test with an IPv6 sender: the environment may not have `::1`. The IPv6 formatting is tested through `ForwardedHeaders.warning(InetAddress.ofLiteral(...))`, including a zone.
- No test proves that another container can actually connect. The tests check that `options()` is the wildcard address on 8080 and that `DEFAULT` is loopback. On purpose, no test binds `0.0.0.0`.
- `CaddyExample.start(UserStore)` is compiled but never run (it binds 0.0.0.0:8080), like `main` in `WhoamiApp`/`CartApp`.
- The `CaddyExample.security` rules duplicate `CsrfExample.security`'s four rules. `CsrfExample.security` hardcodes `Csrf.cookie()`, is used by about 50 tests, and is shown verbatim in README.
