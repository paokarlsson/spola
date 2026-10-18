VERDICT: PASS
CRITERIA:
- 1. `java Build.java` in `framework/` exits 0: MET — I built a copy of `framework/` (without `out/`) in the scratchpad with JDK 25, so the real `framework/out/` was left alone. The log ends `BUILD OK in 325670 ms` and `exit=0`. `spola.web` is OK with 1155/1155 tests, `spola.security.web` OK with 286/286, and every other module is OK with 0 failed.
- 2. `requires`/`exports` unchanged, no reflection, no new file types, nothing changed outside `modules/web`, `modules/security-web` and the docs: MET — `git diff` of both `module-info.java` files touches Javadoc only. `git status` shows changes only under `framework/modules/web`, `framework/modules/security-web`, `framework/ARCHITECTURE.md` and `framework/PROGRESS.md`. The three new files are `.java`: `ForwardedWarningTests`, `CaddyExample` and `CaddyExampleTests`. The root `README.md` is untouched. A grep of the changed and new `.java` files for reflection and non-allowed annotations found nothing.
- 3. P1 test: exactly one `WARNING` with the address and without the value, ten requests still give one line, trusted sender and no headers give none, plus a marker test: MET — `ForwardedWarningTests` does this through a real server (the test name suggests a line for `X-Forwarded-Proto: https`):
  - It asserts `List.of(LINE)` for the first request and asserts that the line contains `from 127.0.0.1` and not `https`.
  - Ten requests still give one line.
  - A trusted sender gets no line, with valid headers, with a 400 for invalid ones and without headers. A request without the headers gets no line, including `X-Forwarded-Host`, `Forwarded` and `X-Real-IP`.
  - It also covers one line per filter instance, 200 concurrent requests, a 50-request barrier test (the barrier forces the `compareAndSet` race) and IPv6 formatting.
  - `ForwardedLeakTests` has a marker test for the warning, and its existing test now also checks the warning. They passed in my independent run.
  - The implementation (`ForwardedHeaders.warnOnce`) uses `AtomicBoolean.compareAndSet`, a `warned.get()` fast path, and `Headers.contains` for the presence check only. `warning(InetAddress)` builds the text from `IpLiteral.format(sender.getAddress())` and fixed text, so no header value can reach the line.
- 4. P1/P3: README shows `__Host-SESSION` + `secure(true)` for both cookies, and a compiled example or test shows the `Set-Cookie` lines over plain http: MET —
  - The web README "Sessioner" has the code block. `SessionReadmeTests` asserts that the README contains that exact block, builds it, and gets `__Host-SESSION=<id>; Path=/; Secure; HttpOnly; SameSite=Lax` plus the removal line over plain http.
  - The security-web README "Bakom Caddy" shows `CaddyExample.java` verbatim, and a test compares them.
  - `CaddyExampleTests` runs the flow through a real server over http, directly, from an untrusted sender and from a trusted proxy with https and with http. It gets `XSRF-TOKEN=...; Path=/; Secure; SameSite=Lax` and `__Host-SESSION=...; Path=/; Secure; HttpOnly; SameSite=Lax` every time.
  - The sentence "secure(true) bara behövs ... utan ForwardedHeaders" is gone from the README, and tests assert it is absent. ARCHITECTURE 13b is corrected too.
- 5. P2: README examples bind an address reachable from other containers, and the README tests still compare examples with sources: MET — `WhoamiApp.options()` and `CaddyExample.options()` use `InetAddress.ofLiteral("0.0.0.0")` on port 8080, each with a comment and README bullets that say why. `ForwardedReadmeTests` still compares the README block with `WhoamiApp.java`, and `CaddyExampleTests` does the same for `CaddyExample.java`. Both are green. No other Docker or Caddy example binds loopback.
- 6. P4/P5: no "minnesdump"/"memory dump" claim, and the "Bakom en omvänd proxy" section says a private client address is no authorization: MET —
  - `grep -rniE "minnesdump|memory dump"` over `framework/` (`.java` and `.md`, excluding `out/`) gives 0 hits. A broader grep for "inga giltiga id" and "no valid id" finds only the corrected ARCHITECTURE text that says the claim was wrong.
  - Corrected in `Sessions` Javadoc, web README, ARCHITECTURE and PROGRESS: the store holds only hashes, and the id is a `String` in `Cookie`/`Set-Cookie` while the request is handled.
  - Web README bullet "En privat klientadress är ingen behörighet." is present, and Javadoc on `WebRequest.clientAddress()` and `TrustedProxies.privateNetworks()` says the same.
- 7. Guardrails green and no `REGLER.md` rule broken: MET — My build log shows `guardrails: no violations` and `0 not allowed`. REGLER §1: new code uses only JDK and Spola modules. §2: no annotations. §3 and §4: no reflection, wiring is plain Java, and the logger factory is package-private. §5: Javadoc on the new package-private members, and docs updated.
- 8. `PROGRESS.md` and `ARCHITECTURE.md` updated: MET — ARCHITECTURE has the new section "Säkerhetsgranskning del 2" with decisions, P1–P5, tests and the first-sender limitation. It also corrects the P4 sentence, the 13b sentence and the "oföränderlig" table cell. PROGRESS has a 15b entry, the 15a/"Återstår" lines updated, and new known problems.

FINDINGS:
- id: AUD-1
  severity: MINOR
  where: /home/user/spola/framework/ARCHITECTURE.md:3243 and :3282 (13a decisions)
  problem: Two older 13a sentences are slightly imprecise now that the filter checks whether the headers are present: "huvudena läses inte alls" and "filtret loggar inget".
  evidence: Line 3243 says the headers are not read at all for an untrusted sender. Line 3282 says "filtret loggar inget". The second is still true for the 400 case it describes, but not for the filter as a whole. The Javadoc and README were reworded to "the values are not read", and the new 15b section states the exact behaviour, so only these old sentences remain.
  fix_hint: Add "(sedan 15b: bara om fälten finns, för varningen)" to the first and "(för 400; varningen i 15b gäller obetrodda avsändare)" to the second.
- id: AUD-2
  severity: MINOR
  where: /home/user/spola/framework/modules/security-web/README.md:489
  problem: The snippet under "I produktion startas det så här" still starts the server with `HttpServerOptions.DEFAULT.withPort(8080)`, which listens on loopback only.
  evidence: The paragraph above it was extended with the `secure(true)` and `__Host-SESSION` production advice. It is not a Docker example, so it is outside the spec's P2 wording, which covers only the Docker and Caddy examples. A reader may still copy it into a container.
  fix_hint: Optional. Add a clause such as "bakom en proxy i en annan container: se Bakom Caddy för `0.0.0.0`".
- id: AUD-3
  severity: MINOR
  where: build output of `spola.web` and `spola.security.web` tests (5 lines)
  problem: Tests that use the public `ForwardedHeaders.trusting(...)` with an untrusted sender print the real `WARNING` to the build output.
  evidence: The build log shows `WARNING: X-Forwarded-Proto or X-Forwarded-For from 127.0.0.1 ignored...` at 5 places. The builder lists this in "Known gaps". It does not affect the result.
  fix_hint: Nothing required. Optionally give those tests the package-private logger factory where the visibility allows.
