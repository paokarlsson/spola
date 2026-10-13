VERDICT: PASS
FINDINGS:
- id: BRK-1
  severity: MINOR
  where: framework/modules/security-web/src/spola/security/web/WebSecurity.java:399-404 (logout: Sessions.invalidate, then renewToken -> Csrf.Exchange.renew throws)
  problem: Turning on csrf(...) changes a late WebSecurity.logout (a thread that outlives the request) from a normal return into an IllegalStateException, but the session has already been removed when it throws, so the call has its side effect and still reports failure. ARCHITECTURE.md says it throws "som Sessions.renew i 11a", and Sessions.renew throws before it changes anything.
  evidence: scratch-breaker/exp/Exp3.java (GET /login, then GET /keep stores the request, then WebSecurity.logout(kept) after the response):
      csrf=false sessions before late logout: 1 / logout returned normally / sessions after late logout: 0
      csrf=true  sessions before late logout: 1 / logout threw java.lang.IllegalStateException: WebSecurity.logout was called after the response of the request was built; ... / sessions after late logout: 0
    It only happens when the request is used from a thread that outlives it (misuse), so MINOR. The logout Javadoc @throws does not say the session is already gone.
  fix_hint: In logout, check the exchange (or call renew) before Sessions.invalidate, or say in the Javadoc and ARCHITECTURE.md that the session is removed before the exception.
NOTES (what was tried, no other defect found):
- Full build of a copy of framework/ with JDK 25: BUILD OK, exit 0, spola.security.web 130/130.
- The spola.security.web suite run on its own 40 times in a row (fresh module layer each time): 40/40 green, 0 flaky failures.
- A model-based fuzz over the real HttpServer with a raw socket: 1,500 random requests with 12 methods (including post, get, Get and PURGE), 0-2 X-XSRF-TOKEN fields in mixed case, and 0-3 XSRF-TOKEN cookies in one or several Cookie fields. The values were valid, 42-char, quoted, padded, upper-cased, empty or with trailing space. Status (200/403) and the number of XSRF Set-Cookie headers matched the spec model in all 1,500 cases (bad=0).
- First-response cookie on 200, a mapped handler WebException 400, a 401 from security.require on a group, login on a GET first response (one XSRF plus one SESSION cookie, one Cache-Control) and 404: all correct.
- Pipelined keep-alive: a POST rejected by CSRF with an unread body that looks like a request, followed by a GET. The body is skipped and no request is smuggled. Chunked bodies, OWS and tabs around the header value, and an extra empty X-XSRF-TOKEN field (403) all behave correctly.
- JDK CookieManager with withCookie(c -> c.maxAge(...)) always gets 403, because the cookie comes back quoted. This is documented in the Javadoc, PROGRESS.md and the spec ("annat format räknas som saknat"), so it is not reported.
- Several things are documented and so not reported: no cookie on exceptions mapped outside the filter, a handler-set XSRF-TOKEN cookie, and the login window on a late thread. No bypass found: there is no method override, the method is compared exactly, and the router's HEAD fallback only goes to GET routes.
