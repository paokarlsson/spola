```
VERDICT: PASS
FINDINGS:
- id: BRK-1
  severity: MINOR
  where: framework/modules/security-web/README.md:740 (the "Bakom Caddy" snippet)
  problem: The snippet that shows the app behind Caddy starts the server with HttpServerOptions.DEFAULT.withPort(8080), which binds the loopback address. In the need's deployment (Caddy forwarding over Docker's internal network) the proxy can't reach the app.
  evidence: README.md:740 `HttpServer.start(HttpServerOptions.DEFAULT.withPort(8080), router)`; HttpServerOptions.java:34-35 `DEFAULT = new HttpServerOptions(InetAddress.getLoopbackAddress(), 8080, ...)`; uppdrag/behov/klara/2026-10-13-proxyhuvuden.md:7-8 "Caddy ... skickar vidare med vanlig HTTP över Dockers interna nät". This is the same pattern as 13a's MINOR BRK-1, and the builder lists it under known gaps. It fails loudly (Caddy gets 502), not silently.
  fix_hint: Use `.withAddress(InetAddress.getByName("0.0.0.0"))` in the snippet, or add a note that Caddy in another container needs withAddress(...).
```

What I tried, besides the finding above:
- I copied framework/ to scratch-breaker/fw and ran `java Build.java` there with JDK 25: EXIT 0, BUILD OK. spola.web had 1093/1093 and spola.security.web 151/151, matching the builder's counts.
- I compiled the modules separately and ran my own HttpServer and HttpClient program (scratch-breaker/exp/Exp1.java). With ForwardedHeaders first, WebSecurity with sessions and csrf, and X-Forwarded-Proto: https:
  - a login followed by an exception that an onException mapper turns into 409 gave both SESSION and XSRF-TOKEN with Secure;
  - a login followed by a WebException 422 gave both with Secure;
  - HEAD gave XSRF-TOKEN with Secure, and so did the automatic OPTIONS 204.
  - An invalid or duplicated X-Forwarded-Proto from a trusted proxy gave 400 and no Set-Cookie. That is 13a's behaviour and a consequence of the documented order, not a 13b defect.
- Code review:
  - Sessions.apply reads isHttps(request) once, before next.proceed. SessionState.https is final, and finish uses it for both sessionCookie and removalCookie.
  - The secureRemoval and removal objects are built once at startup.
  - Csrf.begin reads the scheme from the request after the session filter. Its scheme is the same, because WebRequest.withAttribute keeps it.
  - newCookie uses `template.secure() || https`, so the rule never removes Secure.
  - Only ForwardedHeaders can change scheme() (withClient is package-private), so login and logout can't change which scheme applies.
  - Nothing is shared or mutable per request.
- Criteria checks:
  - Criterion 10: the grep has no hits (exit 1).
  - git status shows changes only under framework/: ARCHITECTURE.md, PROGRESS.md, modules/web and modules/security-web. All new files are .java.
  - The module-info diffs touch comments only. No new public types or methods.
  - The changed test files contain only the registration lines, the README-test strings for the rewritten rows, and the example comments.
  - Old claims grepped for: "never from the scheme", "set only when", "Inget automatiskt Secure", "bara uttryckligen", "Det kommer i 13b", "Bakom en TLS-proxy skrivs". The only remaining hits are the explicit "13b ersatte det beslutet" notes, which criterion 10 allows.
- Factual claims checked:
  - Tomcat ApplicationSessionCookieConfig always sets Secure when request.isSecure().
  - CookieCsrfTokenRepository uses `secure(request.isSecure())` and then the customizer.
  - RFC 6265bis "leave secure cookies alone" matches the documented removal limitation.
- Tests checked for being vacuous:
  - SchemeFlowTests uses a real server on 127.0.0.1 port 0, HttpClient with no cookie handler, and cookies sent by hand. It compares against a secure(true) reference run and the exact 11a/11c constants.
  - SchemeCookieTests covers 200, 401 (twice), 403 from a rule, 403 from the CSRF check, 404, 405 and a mapped 500, plus both wrong orders and the startup IAEs.
  - The concurrency test asserts exact lines per request, and counts 600 lines with Secure and 600 without.
