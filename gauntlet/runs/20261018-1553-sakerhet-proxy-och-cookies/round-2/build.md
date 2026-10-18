# Round 2 – build

## Changes

**BRK-1 (MAJOR): the P4 text now says only what the code does.** I made no code change for P4. Criterion 2 forbids changes in `spola.http`, which is where the buffers live.
- `modules/web/src/spola/web/Sessions.java` (Javadoc "The id"): the session layer keeps no id. That covers the store and `Session`, and the request's session state drops a new id once the filter has built the `Set-Cookie` (or the request failed). The store holds only hashes. A new paragraph says this is all the class promises, and that a heap dump of a running server can still hold valid ids as text that nothing clears. The `String`s in `Cookie`/`Set-Cookie` stay on the heap until the GC reclaims them. The bytes are also in the connection's read and write buffers in `spola.http`, which keep the last request and response, and a later request overwrites them only as far as it reaches. The buffers can therefore hold the id as long as the connection is open: for the life of the app on a keep-alive connection a proxy keeps reusing, and once traffic stops, until `HttpTimeouts.idleTimeout` (60 s) closes it and the GC reclaims them. I removed "any field that outlives the response" and "While a request is handled".
- `modules/web/README.md` ("Sessioner", the "Id:t" bullet): the same content in Swedish.
- `framework/ARCHITECTURE.md`: the "Uppslagningen på SHA-256" decision now says the same thing and names `ConnectionInput` and `Connection`'s `BufferedOutputStream`. It notes that the first correction was also too strong, and why the buffers are not cleared (`spola.http` is outside 15b, and `String`s can't be cleared at all). I rewrote the 15b decision "Id:t i minnet (P4)" to match. In 11c, "Token finns inte i något fält som lever efter svaret" was narrowed to `Csrf`/`WebSecurity`, plus a note that the CSRF token sits in the same headers and buffers. It is the same kind of over-promise.
- `framework/PROGRESS.md`: the 15b P4 entry was rewritten. The known problems for sessions (11a) now say that the connection buffers are not cleared either and that a heap dump can hold valid ids.
- `modules/web/test/spola/web/SessionReadmeTests.java`: a new test, "15b: the id bullet promises what the code holds …". It checks the corrected statements in the README with whitespace normalised, and checks that "Medan en förfrågan hanteras finns id:t" is gone.

**MINOR findings (all addressed, MNT-5 partly):**
- `framework/ARCHITECTURE.md` 13a: "huvudena läses inte alls" became "huvudenas värden läses inte (sedan 15b kontrolleras bara om fälten finns, för varningen)". "filtret loggar inget" became "… inget för 400-fallet (sedan 15b … en varning …)". The same amendment went into `modules/security-web/README.md` ("Bara från en betrodd proxy") and the 13a entry in `PROGRESS.md` (AUD-1/BRK-3).
- `modules/security-web/README.md` "Inloggning med session": after the "I produktion startas det så här" snippet, one sentence says that `DEFAULT` listens on loopback and that in a container the app listens on `0.0.0.0` as in "Bakom Caddy" (AUD-2).
- `framework/ARCHITECTURE.md` (15b, Tester) and `PROGRESS.md` (15b) now list the five tests that print the real `WARNING` line in a green build and say why: they use the public `trusting(proxies)` or README examples, and `spola.security.web` can't reach the package-private factory (AUD-3/MNT-7).
- `modules/web/README.md`: "`InetAddress.ofLiteral("::")` tar också IPv6, men bara där kärnan har IPv6; annars misslyckas `HttpServer.start` med `SocketException`". ARCHITECTURE says why `0.0.0.0` was chosen over `::`. `ForwardedReadmeTests` checks the sentence (BRK-2).
- `modules/web/src/spola/web/ForwardedHeaders.java`: `warnOnce` is split into `if (warned.get()) return;` and `boolean sent = …; if (sent && warned.compareAndSet(false, true))`, which behaves the same. The Javadoc of `warning()` says the fixed text must not contain `https` and why (MNT-1).
- `SessionReadmeTests`: a comment says that `PRODUCTION` (the README block as a string) and the compiled copy are kept equal by hand (MNT-2).
- `ForwardedReadmeTests`: a new helper `flat(String)` collapses whitespace. The 15b checks use it, so they no longer depend on where Markdown wraps lines. The `"\n  "` in the needles is gone (MNT-3).
- `modules/security-web/test/.../CaddyExample.java`: the `Set-Cookie` comment moved from `start` to the Javadoc of `sessions` and `security`. I resynced the README copy, and the README test still compares it character for character (MNT-4).
- `SchemeFlowTests` (13b's "character for character" test) now uses `CaddyExample.security(sessions)` instead of its own copy of the four rules, so 3 copies became 2 (MNT-5, partly; see Rebuttals).
- `SchemeFlowTests.flow(..., String cookie)` was renamed to `sessionCookieName` (MNT-6).

## Verification

`cd framework && export PATH=/opt/jdk-25/bin:$PATH && java Build.java` → exit 0. I ran it twice, and the final run came after every change:
```
guardrails: source       538 .java files
guardrails: source       16 other files: 0 not allowed, 16 documents (0 parse as Java)
guardrails: no violations
spola.http: OK - 290 tests, 290 ok, 0 failed ...
spola.web: OK - 1156 tests, 1156 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 16582 ms
spola.security.web: OK - 286 tests, 286 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 8640 ms
... (every module OK, 0 failed)
BUILD OK in 271181 ms
```
Relevant lines:
```
      ok      15b: the production setting as the README shows it builds and gives __Host-SESSION with Secure over plain http
      ok      15b: the id bullet promises what the code holds: only hashes in the store, and the id as text in the headers and in the connection's buffers for as long as the connection is open
      ok      15b: the Docker example listens on 0.0.0.0 port 8080, ... the default listens on loopback
      ok      15b: the section has the warning as the filter writes it, ...
        ok      character for character the lines that withCookie(c -> c.secure(true)) gives, but for the id and the token
        ok      the README shows CaddyExample.java as it is compiled and tested here
        ok      the part Bakom Caddy: ...
```
`grep -c "^WARNING: X-Forwarded" build.log` → 5. They come from `SessionSchemeTests`, `ForwardedChainTests` and `ForwardedReadmeTests` (web), and from `SchemeFlowTests` and `CaddyExampleTests` (security-web). I traced each line to its test in the log, and that list is what ARCHITECTURE and PROGRESS now say.

**My own probe for the BRK-1 wording.** The server is the breaker's `Heap.java`, compiled against this round's `framework/out` jars in my scratchpad. Each heap dump holds only live objects, after a full GC. On one keep-alive connection: `GET /me` with `Cookie: SESSION=<id>`, then a shorter `GET /me` without the cookie, then close.
```
id length 43
1st response: HTTP/1.1 200 OK
keep-alive open after GET /me with the cookie: 2 matching lines
2nd response (no cookie, same connection): HTTP/1.1 200 OK
same connection after a shorter request without the cookie: 2 matching lines
after close: 0 matching lines
```
This backs "a later request … overwrites them only as far as it reaches" and "as long as the connection is open". I also checked the code: `ConnectionInput.fill` resets `pos`/`end` to 0 without clearing, the `line` array is reused, and `Connection.wire` is a `BufferedOutputStream(…, 8_192)` that is never cleared. `spola.http` has no limit on requests per connection.

- Criterion 6: `grep -rni "minnesdump\|memory dump" framework --include=*.java --include=*.md` (excluding `out/`) → no hits.
- Criterion 2: `git diff` touches neither `module-info.java`. This round changed only `.java` and `.md` files under `framework/modules/web`, `framework/modules/security-web`, `framework/ARCHITECTURE.md` and `framework/PROGRESS.md`. No reflection was added.

## Addressed

- **BRK-1** → I narrowed the docs in all four places named in the finding, plus the 15b decision in ARCHITECTURE, following the fix_hint. The promise is now limited to the session layer. The `String`s and the connection buffers are named, with how long the buffers keep the id (as long as the connection is open, `idleTimeout` once traffic stops). Nothing says or implies "only while a request is handled". The buffers are not cleared, because criterion 2 forbids that and the fix_hint says not to. A new README test guards the wording.
- **AUD-1 / BRK-3** → the 13a sentences in ARCHITECTURE (two places), security-web README:949 and PROGRESS 13a now say "the values" are not read, and that since 15b the filter checks only whether the fields are present, for the warning.
- **AUD-2** → after the production snippet in "Inloggning med session", one sentence points to "Bakom Caddy" for `0.0.0.0` in a container.
- **AUD-3 / MNT-7** → ARCHITECTURE (15b, "Varningar i ett grönt bygge") and PROGRESS (15b, "Väntade varningar i bygget") name the five tests and say why.
- **BRK-2** → the README says that `::` needs IPv6 in the kernel and that `HttpServer.start` otherwise throws `SocketException`. ARCHITECTURE has the same, and a README test checks it.
- **MNT-1** → the early return is split, and the Javadoc of `warning()` states the `https` constraint.
- **MNT-2** → the comment in `SessionReadmeTests` says the copy is kept equal by hand.
- **MNT-3** → `ForwardedReadmeTests.flat` normalises whitespace in the 15b checks. The new P4 test reuses it.
- **MNT-4** → the `Set-Cookie` lines moved to the Javadoc of `CaddyExample.sessions`/`security`, and the README copy is resynced.
- **MNT-5** → partly done: the third copy, in `SchemeFlowTests`, now calls `CaddyExample.security`. See Rebuttals for why the second copy stays.
- **MNT-6** → renamed to `sessionCookieName`.

## Rebuttals

- **MNT-5 (MINOR), on the overload `CsrfExample.security(Sessions, Csrf)`.** I did not add it. This is a choice about how to teach, not a defect, and the finding is optional. "Bakom Caddy" exists to show where the production setting goes. Mission/spec: "README-avsnitten för sessioner, CSRF och "Bakom Caddy" rekommenderar … `Csrf.withCookie(c -> c.secure(true))` för CSRF". With the overload, the compiled example the README shows would read `CsrfExample.security(sessions, Csrf.cookie().withCookie(...))`, and the reader would have to open another example to see `.csrf(csrf)` in the builder. The overload would also change `CsrfExample`, which the README shows as the basic SPA example. I removed the copy in the test code instead (3 → 2), and the reasoning is in ARCHITECTURE (15b, "`CaddyExample` i stället för ett utdrag").

## Known gaps

- The connection buffers in `spola.http` are still not cleared after each exchange. The docs now say so. Clearing them would mean changing `spola.http`, which this mission's criterion 2 rules out.
- The heap probe is manual (in my scratchpad), not a build test. A test that takes heap dumps would be slow and environment-dependent. The README test guards the wording only.
- I did not rerun round 1's mutation harness after the `warnOnce` refactor. The refactor keeps the semantics (`warned.get()` first, then the presence check, then `compareAndSet`), and the warning, leak and README tests (`ForwardedWarningTests`, `ForwardedLeakTests`, `ForwardedReadmeTests`) all pass in the build.
- The other gaps from round 1 remain: one warning per filter (the first untrusted sender gets it), no IPv6 real-socket test, and `CaddyExample.start` is compiled but never run.
