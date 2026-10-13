VERDICT: PASS

I ran `java Build.java` with JDK 25 on a copy of the repo in the scratchpad, so I did not touch `framework/out/`. It ended with `BUILD OK in 257214 ms` and `exit=0`. `spola.web` reported `OK - 823 tests, 823 ok, 0 failed`, and guardrails reported `no violations` (15 other files, 0 not allowed). I counted 93 `ok` lines in the `sessions` suite, which matches build.md. I also ran a small probe against the built jars for HEAD, a lowercase `cache-control`, a mapped 404 after `getOrCreate`, a held `Session` after expiry, and 201 cookies. Everything behaved as the Javadoc says.

CRITERIA:
- 1. Build exit 0 and the new tests run in `spola.web`'s suite: MET. My copy build is green. `WebTests.java` has the single added line `SessionTests.tests(),`. The log shows `sessions` with 93 ok tests, and the `spola.web` total is 823.
- 2. `requires`/`exports`, `SourceRulesTests`, no reflection, no changes outside scope: MET. The `module-info.java` diff only adds a Javadoc bullet. `git status` shows `SourceRulesTests.java` and `Build.java` unmodified and no changes under other modules' `src/` or `test/`. The only modified existing test file in `spola.web` is `WebTests.java`. `grep` finds no `java.lang.reflect`, `java.lang.invoke` or `ThreadLocal` in the `Session*.java` main files, and the `SourceRulesTests` "no reflection" test is green.
- 3. The need's use case over a raw socket: MET.
  - `SessionCookieTests.needsUseCase(boolean secure)` is checked in three settings, each asserting the exact `Set-Cookie`: `; Path=/; Secure; HttpOnly; SameSite=Lax` with `secure=true`, no `; Secure` with `false`, and the same with `DEFAULT`.
  - Each setting also checks the id against `[A-Za-z0-9_-]{43}` and `Cache-Control: no-store`.
  - A handler's `private, max-age=60` is kept.
  - `__Host-SESSION` with `secure(true)` works.
  - My probe C showed that a lowercase `cache-control` from a handler is also respected.
- 4. HttpServer with HttpClient and CookieManager: MET.
  - `SessionFlowTests` covers the plain route, the counter at 1, 2, 3 with one `Set-Cookie`, a second client with its own session and counter, and `find` alone never creating a session.
  - The well-formed unknown id and the malformed values are sent over a raw socket rather than `HttpClient`. It is the same real server, so I read this as a reasonable interpretation and did not raise a finding.
- 5. Lifecycle: MET. The flow tests cover every sub-point:
  - `renew` (old id gives `find` empty, new id differs, attributes kept, `size()` unchanged) and `renew` without a session.
  - The exact removal string, with `Secure`/`Path`/`Domain` from the function, and `invalidate` without a session giving no `Set-Cookie`.
  - Create+invalidate gives only the removal, and invalidate+`getOrCreate` gives only the new id.
  - An ended `Session` (`get` empty, `remove` a no-op, `put` throws `IllegalStateException`).
  - `put` of a wrong type through a raw key gives `ClassCastException`, and `null` gives `NullPointerException`.
- 6. Controlled `InstantSource`: MET. `SessionExpiryTests` shows alive at timeout minus 1 s and gone at exactly the timeout. A `/plain` request with the cookie moves the expiry. A request without a cookie after timeout plus 1 minute and 1 s brings `size()` to 0.
- 7. `maxSessions` 3: MET.
  - The fourth create gives 503 with `application/problem+json`, the exact body, and no `Set-Cookie`.
  - The three sessions keep working, and `/plain` and `find` give 200.
  - `renew` works at the limit and `size()` stays 3.
  - An expired session makes room at once, before the minute cleanup.
- 8. Constructed cookie values: MET. There are 20 table values: empty, 42, 44, `+`, `/`, `=`, quoted, one extra character, unknown, invalidated, expired, replaced by `renew`, from another `Sessions`, and others. Each gives 200, "none", no `Set-Cookie` and no session. A separate test with two `SESSION` values shows the live one is used in any order.
- 9. 10 000 ids: MET. The test creates 10 000 sessions over 16 virtual threads, checks all ids are distinct and match the pattern, and checks the 10 001st gets 503. Reading the code: `new SecureRandom()` per `Sessions` (`Sessions.java:176`), 32 bytes (`newId`), key = `SHA-256` as `Key(long×4)` (`keyOf`), and the id is only held in `Issued` and `SessionState.issued` until `finish()`/`abandon()` null it.
- 10. Table-driven `SessionOptions`/`Sessions.inMemory` test: MET.
  - 34 refused cases cover every listed value, plus extras.
  - The canonical constructor with three bad values gives a three-line message.
  - Both `null` and the other refusals give the exception classes the spec names.
  - The cookie function runs once, and an exception from it passes unchanged.
- 11. Missing or doubled filter: MET. All four methods give 500 without the filter and the log lines show `IllegalStateException`. A filter registered twice, two `Sessions` on one router, and a group each give 500.
- 12. 200 virtual threads: MET. 200 threads each with their own `HttpClient` and `CookieManager` run create, count ×10, `renew`, count, `invalidate`, `find`. Each sees its own sequence, 400 ids are distinct, and `size()` is 0. The second test with `maxSessions` 100 gives exactly 100 created, 100 refused with 503 and `size()` = 100.
- 13. Markers for ids: MET. `SessionLeakTests` runs last and checks every recorded id (over 10 000) against all router log lines, bodies, `toString()` values and exception messages, using a sliding 43-character window. It has a positive self-check and `LogTests.assertClean`.
- 14. README "Sessioner": MET. The section has the Spring Boot code (`@RestController`, `HttpSession`, `getAttribute`/`setAttribute`, `changeSessionId`, `invalidate`, `timeout=30m`, `cookie.secure=true`) before the Spola code. `SessionReadmeTests` asserts the Spola block equals `CartApp.java` minus its package line, and the new comparison and deviation rows are checked. `__Host-SESSION` with `secure(true)` is recommended. The rows for `getId`, 503, `SESSION` vs `JSESSIONID`, no automatic `Secure`, persistence, cleanup without a background thread and `Cache-Control` are in the existing comparison and "Avvikelser" tables, which I read as correct placement.
- 15. Annotations, dependencies, file types, REGLER: MET.
  - Guardrails report 0 violations and 0 disallowed files.
  - All new files are `.java` or `.md`.
  - The only annotations are `@Override` and `@SuppressWarnings`.
  - There is no reflection and no new `requires`.
  - REGLER §1–§5: SHA-256, `SecureRandom` and `Base64` are JDK primitives, there is no custom crypto, wiring is explicit, and the public API has Javadoc. I found no broken rule.
- 16. `PROGRESS.md`, `ARCHITECTURE.md`, `MONSTER.md`, root `README.md`: MET.
  - PROGRESS.md has the delivery, "11b/11c återstår", the security-critical and human-review note, and the known limitations: in-memory only and not shared, no log line at the limit, and two concurrent `renew` giving two `Set-Cookie`.
  - ARCHITECTURE.md has the section "Sessioner (`spola.web`)", which covers why `spola.web`, the static-method API, id/`SecureRandom`/SHA-256 lookup, cookie in/out and `Cache-Control`, lifecycle/expiry/cleanup without a thread, and the 503 limit.
  - The `spola.web` row in MONSTER.md (`HttpSession`, `Sessions`, `Session`) and the root README.md module table ("sessioner") are updated. The root README.md is outside `framework/`, but criterion 16 asks for it explicitly, and build.md explains the choice.

FINDINGS:
- id: AUD-1
  severity: MINOR
  where: framework/modules/web/src/spola/web/SessionEntry.java (generation / `visible`), SessionState.java:104 (`current()`)
  problem: "The old id stops being valid immediately" is implemented as per-request views that end for in-flight requests too, which the mission does not ask for; an in-flight old-id request that then calls `getOrCreate` gets a new session and its own `Set-Cookie`.
  evidence: The behaviour is tested in "a request with the old id loses the session when another request renews it" and is documented in the class Javadoc, the README deviation row, ARCHITECTURE.md and PROGRESS.md. Tomcat lets such a request keep its `HttpSession`. In a rare race, for example a login `renew` racing a concurrent `getOrCreate` poll from the same browser, the late response could overwrite the logged-in cookie.
  fix_hint: Spec-ambiguity (`spec-ambiguity`). The builder's fail-closed reading is reasonable and documented. The planner could say explicitly in 11b/11c whether a request already in flight may keep its view.
- id: AUD-2
  severity: MINOR
  where: framework/modules/web/src/spola/web/Sessions.java:347 (`request.cookieAll(...)` in the filter)
  problem: A request with more than 200 cookies gets 400 on every route behind the filter, including routes that never touch the session, and no test covers it.
  evidence: My probe with 201 cookies against `GET /plain` returned `HTTP/1.1 400 Bad Request`. The behaviour is documented as fail-closed in the Javadoc and ARCHITECTURE.md, but `grep` for 201 / "200 cookies" in `Session*.java` tests finds nothing. The mission says a bad cookie value gives "inget 400", though the 201-cookie case is not a bad `SESSION` value. Browsers do not send that many cookies.
  fix_hint: Add a one-line test that pins the 400, or catch the `WebException` in the filter and treat it as "no session".
- id: AUD-3
  severity: MINOR
  where: framework/modules/web/test/spola/web/SessionLeakTests.java:70-75
  problem: The leak test asserts more than 10 000 recorded ids and 10 000 recorded texts, so it only passes when it runs after the scale tests in the same JVM.
  evidence: The static `SessionSupport.IDS` and `TEXTS` are filled by earlier tests, and `SessionTests` fixes the order with the leak test last. Run alone, the suite would fail.
  fix_hint: Leave as is, since the order is documented in the Javadoc. Optionally note in `SessionTests` that the order matters.
- id: AUD-4
  severity: MINOR
  where: framework/modules/web/src/spola/web/Sessions.java:132-134
  problem: The class-Javadoc paragraph on the limit has one over-long, badly wrapped line ("...if the limit is still reached, {@link #getOrCreate} (and {@link #renew} without a session) throws a").
  evidence: Visible at line 133. It is cosmetic only and does not affect the build.
  fix_hint: Re-wrap the paragraph.

Relevant files, all absolute:
- /home/user/spola/framework/modules/web/src/spola/web/Sessions.java
- /home/user/spola/framework/modules/web/src/spola/web/Session.java
- /home/user/spola/framework/modules/web/src/spola/web/SessionOptions.java
- /home/user/spola/framework/modules/web/src/spola/web/SessionEntry.java
- /home/user/spola/framework/modules/web/src/spola/web/SessionState.java
- /home/user/spola/framework/modules/web/test/spola/web/SessionTests.java, and the other `Session*Tests.java`, `SessionSupport.java` and `CartApp.java` in the same directory
- /home/user/spola/framework/modules/web/README.md
- /home/user/spola/framework/ARCHITECTURE.md
- /home/user/spola/framework/PROGRESS.md
- /home/user/spola/framework/MONSTER.md
- /home/user/spola/README.md
