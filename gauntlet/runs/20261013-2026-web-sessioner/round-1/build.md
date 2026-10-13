# Build, round 1: Sessions part 1 (spola.web)

## Changes

New main code in `framework/modules/web/src/spola/web/`:
- `Sessions.java` (public): `inMemory(options[, InstantSource])`, `filter()`, `size()`, and the static
  `find`/`getOrCreate`/`renew`/`invalidate`. It holds the store (`ConcurrentHashMap<Key, SessionEntry>`,
  where the key is SHA-256 of the id as four longs), a `new SecureRandom()` per instance (32 bytes,
  Base64url without padding, 43 characters), a CAS counter for the limit, the once-a-minute cleanup and
  the filter. The class Javadoc covers where to register the filter, the id format and random source,
  the lifecycle, expiry and cleanup, the limit and 503, the cookie attributes and `Cache-Control`, and
  what happens without the filter.
- `Session.java` (public): `get`/`put`/`remove` with `AttributeKey<T>`. There is no `id()`, and
  `toString` gives `Session[n attributes]` or `Session[ended]`. It is a view per request that carries
  the generation it knows.
- `SessionOptions.java` (public record): `DEFAULT` (30 min, 10 000, `SESSION`, identity) and `withX`.
  The canonical constructor reports every problem in one IAE, one line each. A `null` gives NPE.
- `SessionEntry.java` (internal): one stored session with attributes, key, generation, last use and
  the ended flag. All of it is guarded by the entry's lock.
- `SessionState.java` (internal): the per-request state the filter puts as an attribute. It holds the
  request's view plus the final cookie outcome (new id or removal). The id field is cleared when the
  response is built.
- `module-info.java`: one Javadoc bullet about sessions. `requires`/`exports` are unchanged.

Tests, all new except the one registration line in `WebTests.java`. `SessionTests` aggregates them and
runs the leak test last:
- `SessionCookieTests`: criterion 3 (the need's use case with a local `boolean secure`, raw socket,
  exact `Set-Cookie`, `Cache-Control`, `__Host-SESSION`) and criterion 8 (20 constructed values, plus
  two `SESSION` values).
- `SessionFlowTests`: criterion 4 (HttpClient with CookieManager) and criterion 5 (lifecycle over a raw
  socket, removal with the function's attributes, outcomes within one request, ended `Session`,
  `put` validation, and the old-id view ending after another request's `renew`).
- `SessionExpiryTests`: criterion 6 (controlled clock, the cleanup and its once-a-minute limit) and
  criterion 7 (`maxSessions` 3, plus a check that refused requests do not walk the store each time).
- `SessionSetupTests`: criterion 10 (34 refused settings, the three-line message, the limits accepted,
  the function runs once and its exceptions pass unchanged) and criterion 11 (no filter, doubled filter,
  two `Sessions`, a group). It also covers filter order, calls after the response was built, and a
  `RequestBodyException` passing the filter.
- `SessionScaleTests`: criterion 9 (10 000 sessions over HTTP) and criterion 12 (200 virtual threads
  with their own HttpClient/CookieManager; 200 against `maxSessions` 100).
- `SessionReadmeTests`: criterion 14 (the README block equals `CartApp.java`, Spring markers, table
  rows, and the example run through a server).
- `SessionLeakTests`: criterion 13. It checks every id seen in a `Set-Cookie` (more than 10 000) against
  every router log line, body, `toString` and exception message recorded through `SessionSupport`, with
  a sliding 43-character window.
- `SessionSupport.java` holds the helpers and the recording. `CartApp.java` is the README example.

Docs:
- `framework/modules/web/README.md`: the section "Sessioner" (Spring Boot vs Spola, `CartApp.java`,
  bullets, the `__Host-SESSION` recommendation), new comparison rows, new deviation rows, and one
  intro sentence.
- `framework/ARCHITECTURE.md`: the new section "Sessioner (`spola.web`)", covering every topic
  criterion 16 lists plus the decisions below.
- `framework/PROGRESS.md`: the 11a delivery, "Återstår" (11b and 11c), the security-critical note,
  known limitations (all four required ones plus others), and adjusted 08c/10b lines that said "no
  sessions".
- `framework/MONSTER.md`: the `spola.web` row (adds `HttpSession`, `Sessions`, `Session`).
- `README.md` (repo root): the `spola.web` row in the module table adds "sessioner". This is outside
  `framework/`. Criterion 16 explicitly requires "modultabellen i `README.md`", and the only module
  table is in the root README (MONSTER.md: "modultabellen i `README.md`"), so I followed the criterion
  over the general assumption "Inga ändringar utanför `framework/`".

## Decisions beyond / interpreting the mission (also in ARCHITECTURE.md)

1. **`Session` is a per-request view with a generation.** Another request's `renew` ends the views of
   requests that came with the old id, including requests still being handled. A request's own
   `renew` keeps its view working. The lookup also checks that the entry is still stored under the
   key it was found by. This is the fail-closed reading of "det gamla id:t slutar gälla direkt": it
   stops an in-flight request on a fixated id from reading or writing the session after login. The
   cost, documented as a known limitation, is that a concurrent old-id request that then calls
   `getOrCreate` gets a new session and its own `Set-Cookie`.
2. **The cookie function is applied once, in `inMemory`, to `ResponseCookie.of(cookieName, <43×A>)`.**
   Every session cookie and the removal are built from its attributes. For a pure function the result
   is identical to applying it per response. Running it once guarantees startup-time errors and no
   user code per response. It also adds two fail-early checks: a builder for another name or value is
   refused, and so is `Max-Age=0`.
3. **Cleanup at the limit is skipped only when nothing can have expired.** Each cleanup records a lower
   bound on the next expiry. Without this, a flood of refused anonymous requests would each walk the
   whole store. The observable behaviour of criterion 7 is unchanged (tested).
4. **An exception that passes the filter** (`RequestBodyException`, `Error`) ends a session the
   request created or renewed, because its id can never reach the browser. `getOrCreate` (when it must
   create) and `renew` after the response was built throw ISE.
5. A request with more than 200 cookies gets the existing 400 from `cookieAll` in the filter
   (fail-closed). This is documented.
6. `put` with a wrong type gives `ClassCastException` (as `WebRequest.withAttribute` does), with no
   value in the message. `null` gives NPE.

## Verification

Full build with JDK 25, run twice (the second after the final edits):
```
$ cd framework && export PATH=/opt/jdk-25/bin:$PATH && java Build.java ; echo exit=$?
guardrails: source       429 .java files
guardrails: source       15 other files: 0 not allowed, 15 documents (0 parse as Java)
guardrails: bytecode     1210 .class files
guardrails: dependencies 26 module-info.class files
guardrails: files        1691 files
guardrails: no violations
spola.guardrails: OK - 17 tests ...
spola.test: OK - 77 tests ...
spola.di: OK - 48 tests ...
spola.config: OK - 68 tests ...
spola.util: OK - 80 tests ...
spola.codegen: OK - 41 tests ...
spola.json: OK - 417 tests ...
spola.http: OK - 283 tests ...
spola.validation: OK - 251 tests ...
spola.web: OK - 823 tests, 823 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 14549 ms
spola.security: OK - 103 tests ...
spola.security.web: OK - 47 tests ...
spola.demo: OK - 43 tests ...
BUILD OK in 263018 ms
exit=0
```
There are 93 session tests, about 7 s in total: 10 000 sessions in about 3.5 s, 200 HttpClients in
about 1.3 s.

Scope checks:
- `git status` shows no changes in `Build.java` and none under other modules' `src/` or `test/`.
- The only changed existing test file in `spola.web` is `WebTests.java`, with one added line
  (`SessionTests.tests(),`).
- `requires`/`exports` diff in `module-info.java`: none.
- `SourceRulesTests` is unchanged and green. The new main files contain no `java.lang.reflect` or
  `java.lang.invoke`.

## Addressed
Round 1 has no findings.

## Rebuttals
None.

## Known gaps
- These known limitations are documented in PROGRESS.md:
  - sessions are in-memory only;
  - nothing is logged when the limit is reached;
  - two concurrent `renew` calls (or an old-id request that creates a session after a `renew`) give
    competing `Set-Cookie` headers;
  - the cleanup walks every session on one request thread at most once a minute, which is O(n) and
    fine at 10 000;
  - a persistent cookie (`maxAge` set by the function) is read back quoted by `java.net.CookieManager`
    and is then never looked up (browsers are unaffected);
  - the id `String` cannot be zeroed.
- The lower bound used to skip the cleanup at the limit can be off by the duration of one cleanup when
  sessions are created during it. Such a session can then be removed slightly later at the limit, but
  never later than at lookup or the minute cleanup. This is documented in ARCHITECTURE.md.
- The code is security-critical. A human review is recommended, as PROGRESS.md says.
