VERDICT: PASS
FINDINGS:
- id: BRK-1
  severity: MINOR
  where: framework/modules/web/src/spola/web/Sessions.java:87-88 ("The id never appears in a {@code toString()}, an exception message, a log line or a problem response."); framework/modules/web/README.md:770-771 ("id:t syns aldrig i `toString()`, undantag, loggrader eller problem-svar")
  problem: The paragraph this round rewrote still ends with an unscoped promise that the code does not keep. The public `Response.toString()` (a record without its own toString) and `Headers.toString()` print every header value, so a valid session id shows up in the `toString()` of the response that `sessions.filter()` returns and of the request's headers. A logging filter that prints `response` or `request.http().headers()` writes the id to the log.
  evidence: round-2/scratch-breaker/probe/src/ToStr.java, compiled against this round's jars from a scratch build. The router is a plain logging filter first, then `sessions.filter()`, then `POST /login` (`Sessions.renew`) and `GET /me`. Output:
    LOG response: Response[status=200, headers=Headers[Content-Type: text/plain; charset=utf-8, Set-Cookie: SESSION=C5mJrmzrU-UpZaZWsoSN5o9OL4RpJ2qO-WydvTfyvuA; Path=/; HttpOnly; SameSite=Lax, Cache-Control: no-store], body=Bytes[2 bytes]]
    issued id: C5mJrmzrU-UpZaZWsoSN5o9OL4RpJ2qO-WydvTfyvuA
    LOG request headers: Headers[..., Cookie: SESSION=C5mJrmzrU-UpZaZWsoSN5o9OL4RpJ2qO-WydvTfyvuA]
    GET /me with the cookie: yes
    The map from `WebRequest.cookies()` and `ResponseCookie.toString()` (which returns `headerValue`) hold it the same way. ARCHITECTURE.md already limits the sentence to the session classes ("`Session` visar antalet attribut, `Sessions` antal, ..."). The Javadoc and README do not. I rate it MINOR because the sentence predates 15b (11a) and is not the memory claim in P4.
  fix_hint: Limit it to the session layer, as ARCHITECTURE does. For example: "never appears in the toString() of Session, Sessions or their internal classes, in an exception message, a log line or a problem response from Spola". Optionally add that `Response`/`Headers.toString()` and `WebRequest.cookies()` show it.
- id: BRK-2
  severity: MINOR
  where: framework/modules/web/README.md:769 ("när trafiken upphör tills `idleTimeout` (60 s som standard) stänger den")
  problem: In the "Sessioner" section, `idleTimeout` (60 s) means `HttpTimeouts.idleTimeout` but never names the record. Forty lines further down, the same section uses `idleTimeout` for `SessionOptions.idleTimeout` ("standard 30 minuter", line 809), and the example sets `withIdleTimeout(Duration.ofMinutes(30))` (line 696). A reader of the new P4 text cannot tell which setting bounds how long the buffers keep the id, and could take it to be the session's 30-minute timeout.
  evidence: `awk 'NR>=589 && NR<=870 && /idleTimeout/' framework/modules/web/README.md` → 696 `.withIdleTimeout(Duration.ofMinutes(30))`, 769 "`idleTimeout` (60 s som standard)", 809 "`idleTimeout` (standard 30 minuter)". `SessionOptions.DEFAULT` has `Duration.ofMinutes(30)` (SessionOptions.java:80) and `HttpTimeouts.DEFAULT` has 60 s (HttpTimeouts.java:46). The Javadoc (Sessions.java:86) names `HttpTimeouts.idleTimeout` correctly.
  fix_hint: Write `HttpTimeouts.idleTimeout` (or "serverns `idleTimeout` i `HttpTimeouts`") on README line 769.

What I also tried, with no defect found:
- A full `java Build.java` in a scratch copy of this round's `framework/` (round-2/scratch-breaker/build.log): BUILD OK, exit 0. Guardrails reported no violations. spola.web had 1156/1156 tests ok and spola.security.web 286/286. Exactly 5 `WARNING: X-Forwarded...` lines appear, one per filter, which matches the new ARCHITECTURE/PROGRESS "Varningar i ett grönt bygge" text.
- I checked every new P4 statement against the code. `SessionState.finish` and `abandon` null `issued`, `Session` and `SessionEntry` hold no id string, and `Issued` is transient. `ConnectionInput.buffer`/`line` and `Connection.wire` are never cleared, `HttpTimeouts.DEFAULT.idleTimeout` is 60 s, and the idle wait uses `idleTimeout` after the first exchange. `spola.http` has no limit on requests per connection.
- The `warnOnce` refactor has the same semantics as before (early return on `warned`, presence check, then CAS). The header-name check is case-insensitive.
- The 13a amendments (ARCHITECTURE, security-web README:952, PROGRESS) now say only the values are not read. Grepping for remnants of "secure(true) bara behövs utan ForwardedHeaders", "minnesdump" and "memory dump" finds no stale claim.
- The `SchemeFlowTests` switch to `CaddyExample.security(sessions)` is the same configuration as the removed copy. The security-web README "Inloggning med session" note and its `#bakom-caddy` anchor are correct. `git diff --stat` touches nothing outside `framework/modules/web`, `framework/modules/security-web` and the docs, and no `module-info.java` changed.
