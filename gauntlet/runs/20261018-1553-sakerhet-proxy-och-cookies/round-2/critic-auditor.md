VERDICT: PASS
CRITERIA:
- 1. `java Build.java` in `framework/` exits 0: MET — I built an exact copy of `framework/` (without `out/`) in my own scratchpad with JDK 25, so I did not touch the shared `framework/out`. Result: `BUILD OK in 332258 ms`, `EXIT 0`. Every module is OK with 0 failed: spola.http 290, spola.web 1156, spola.security 305, spola.security.web 286, spola.client 330, spola.demo 43, and the rest. Test counts match build.md.
- 2. `requires`/`exports` unchanged, no reflection, no new file types, no changes outside the allowed places: MET — `git diff 668445d -- framework` (the base before round 1) touches only `.java` and `.md` files under `modules/web`, `modules/security-web`, `ARCHITECTURE.md` and `PROGRESS.md`. The two `module-info.java` diffs change Javadoc only. A grep of the added lines finds no `reflect`, `Class.forName` or annotations. Nothing changed in `spola.http` or at the repo root.
- 3. P1, test for the warning: MET — `ForwardedWarningTests` has:
  - one test with `X-Forwarded-Proto: https` from an untrusted sender. It asserts `List.of(LINE)`, where the line holds `from 127.0.0.1` and no `https`, and the router log stays empty.
  - a test with ten requests that still gives one line.
  - a test for a trusted sender (no line, a 400 for invalid headers) and one for requests without the headers (no line).
  - a concurrency test with 200 requests and a barrier test with 50, both giving one line.
  - `ForwardedLeakTests` has the marker test: marked values, and `https` and the value are never in the line. In the build, `ForwardedHeaders.warnOnce` prints the real line exactly 5 times (build.log lines 2801, 3364, 3558, 4513 and 4582), matching the 5 tests ARCHITECTURE and PROGRESS name.
- 4. P1/P3, README and compiled example: MET — web README ("Sessioner", lines 843-869) shows `withCookieName("__Host-SESSION")` with `withCookie(cookie -> cookie.secure(true))`. security-web README shows `CaddyExample` with both cookies and the CSRF section. `SessionReadmeTests` "15b: the production setting ..." builds the setting and asserts `__Host-SESSION=<id>; Path=/; Secure; HttpOnly; SameSite=Lax` over plain http. `CaddyExampleTests` runs the whole flow over http, directly, from an untrusted sender and from a trusted one, and asserts `XSRF-TOKEN=...; Path=/; Secure; SameSite=Lax` plus the `__Host-SESSION` lines. Both are `ok` in my build. The line "secure(true) only needed without ForwardedHeaders" is gone, and a test checks it.
- 5. P2, Docker examples: MET — `WhoamiApp.options()` and `CaddyExample.options()` bind `0.0.0.0:8080` and the README says why. `ForwardedReadmeTests` and `CaddyExampleTests` still compare the README blocks character for character with the sources (ok in the build). The `::` sentence now says that IPv6 is needed in the kernel and that `HttpServer.start` otherwise throws `SocketException`, and a test checks it.
- 6. P4/P5, wording: MET — `grep -rniE "minnesdump|memory dump"` in `framework/` (excluding `out/`) gives 0 hits. The corrected texts say the session layer keeps no id (and the request's session state drops a new id: `SessionState.issued = null` at :121, :138 and :176), and that a heap dump can still hold valid ids. They name the `String`s and the connection buffers in `spola.http`. I checked the buffer claim in the code. `ConnectionInput.fill` resets `pos`/`end` without clearing, `Connection.run` creates one `BufferedOutputStream(…, 8_192)` per connection, and the default `idleTimeout` is 60 s. The web README "Bakom en omvänd proxy" ends with "**En privat klientadress är ingen behörighet.**" (lines 1013-1019). The `WebRequest.clientAddress()` and `TrustedProxies.privateNetworks()` Javadoc say the same.
- 7. Guardrails green, no REGLER rule broken: MET — build.log: `guardrails: no violations`, 538 `.java` files, 16 other files all documents, and the guardrails self-test is ok.
  - REGLER §1: only JDK classes (`System.Logger`, `AtomicBoolean`).
  - REGLER §2: no annotations added, and guardrails pass.
  - REGLER §3/§4: no reflection, explicit wiring (the package-private `trusting(proxies, log)` is plain Java).
  - REGLER §5: PROGRESS and ARCHITECTURE updated, and the new package-private methods have Javadoc.
- 8. `PROGRESS.md` and `ARCHITECTURE.md` updated: MET — PROGRESS "Uppdrag 15b" (:983 and on) has the warning, the production setting, P2, P4/P5 and the "Väntade varningar i bygget" note. ARCHITECTURE has the "Säkerhetsgranskning del 2" section (:3873 and on) with decisions for P1-P5, and the 13a, 11a and 11c passages are adjusted. The 13a sentences "huvudena läses inte alls" and "filtret loggar inget" are now corrected.

Round 1 findings, checked in this round:
- BRK-1 (MAJOR) is fixed in all four places plus the ARCHITECTURE 15b decision, and a README test guards the wording.
- AUD-1, AUD-2, AUD-3, BRK-2, BRK-3, MNT-1, MNT-2, MNT-3, MNT-4, MNT-6 and MNT-7 are fixed as the builder describes.
- MNT-5 is partly done, and the rebuttal for the overload is reasonable. The README and ARCHITECTURE explain why.

FINDINGS:
- id: AUD-1
  severity: MINOR
  where: framework/modules/security-web/test/spola/security/web/CaddyExampleTests.java (the last test, "the CSRF section and the session login recommend the production setting")
  problem: One new 15b assertion still fixes a Markdown line-wrap position (`"... rekommenderas\n" + "  `Csrf.cookie()..."`), which is what MNT-3 asked to remove in `ForwardedReadmeTests`.
  evidence: The needle `"- **I produktion**, för en app som bara nås över https, rekommenderas\n  `Csrf.cookie().withCookie(c -> c.secure(true))` för CSRF-cookien"` fails if the README is re-wrapped. The same file's other checks do not depend on wrapping.
  fix_hint: Normalise whitespace the way `ForwardedReadmeTests.flat` does before `contains`.
- id: AUD-2
  severity: MINOR
  where: framework/modules/security-web/src/spola/security/web/Csrf.java:45, WebSecurity.java:771, framework/modules/security-web/README.md:620
  problem: The wording "The server keeps no tokens, not in memory ..." / "Servern sparar inga token, varken i minnet ..." has the same shape as the P4 over-promise, while the CSRF token also sits as text in `Cookie`, `X-XSRF-TOKEN`, `Set-Cookie` and the connection buffers.
  evidence: The builder narrowed ARCHITECTURE 11c ("Token finns inte i något fält i `Csrf` eller `WebSecurity` ...") and says it "is the same kind of over-promise", but these three places keep the old sentence. They mean "no server-side store", as in Spring, so this is only a consistency gap.
  fix_hint: Add "(no store; the token is still text in the headers and buffers, see Sessions)" or say "no stored tokens" instead of "not in memory".
