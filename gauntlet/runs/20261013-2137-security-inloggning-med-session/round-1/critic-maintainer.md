VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /home/user/spola/framework/modules/security-web/test/spola/security/web/LoginExample.java:55 (same text in README.md:381 and the production snippet at README.md:447)
  problem: The README's production snippet and the Javadoc of LoginExample.sessions use `config.booleanValue("cookie.secure")`, but `Config` has no such method. `booleanValue` is on `Section`, and `Config` is a final class that does not extend it.
  evidence: `Config.java` public methods are `builder, get, sourceOf, isSensitive, keys, profiles, describe, toString, bind(String prefix, Function<Section,T>)`. The README snippet reads `var sessions = LoginExample.sessions(config.booleanValue("cookie.secure"), InstantSource.system());`. The snippet is not compiled, and the Javadoc line is part of the verbatim-compared block.
  fix_hint: Write `config.bind("cookie", s -> s.booleanValue("secure"))`, or drop the config claim from the Javadoc and the snippet. Change LoginExample and the README block together, because a test compares them.

- id: MNT-2
  severity: MINOR
  where: /home/user/spola/framework/modules/security-web/src/spola/security/web/WebSecurity.java:374-388 (`apply`)
  problem: "The session filter already ran" is detected by catching any `IllegalStateException` thrown before the chain is entered, using a `boolean[] reached` flag. This ties spola.security.web to the exact failure behaviour of `Sessions.apply`.
  evidence: `catch (IllegalStateException e) { if (reached[0]) { throw e; } throw new IllegalStateException("the session filter already ran for the request: ...", e); }`. `Sessions.apply` throws this exception only for "ran twice" today (Sessions.java:342). A custom `InstantSource` throwing `IllegalStateException` in `clock.instant()` would get the same, wrong, message, though the original is kept as cause.
  fix_hint: Prefer a way to ask whether the session filter has already run. A small predicate in spola.web is allowed by the spec, or a dedicated exception subclass. Failing that, add a comment saying the `reached` flag is a deliberate coupling to Sessions.java:342.

- id: MNT-3
  severity: MINOR
  where: /home/user/spola/framework/modules/security-web/test/spola/security/web/LoginFlowTests.java:37-62, FixationTests.java:35-51, LoginRuleTests.java:38-55
  problem: Shared test helpers live in a test class, and the example's rule list is copied three times. `assertPlain401`, `REMOVAL` and `SESSION_COOKIE` sit in `LoginFlowTests` but are used by FixationTests, LoginExpiryTests, LoginMisuseTests, LoginRuleTests and LoginConcurrencyTests. The four rules (login, logout, health, anyRequest) are repeated in `LoginExample.security`, `FixationTests.router` and `LoginRuleTests.router`. If the example's rules change, the copies drift silently.
  evidence: `LoginFlowTests.assertPlain401(...)` is called from four other classes. `.rule(RequestMatcher.exactly("/api/auth/login"), AccessRule.permitAll())` appears in three files.
  fix_hint: Move the helpers and constants to `LoginSupport`. Let `LoginExample.security` take extra rules, or have a test helper add them, instead of copying the list.

- id: MNT-4
  severity: MINOR
  where: /home/user/spola/framework/modules/security-web/test/spola/security/web/LoginLeakTests.java:58-59,87 and LoginMisuseTests.java:126-136
  problem: Sanity thresholds and an exact log-line count depend on other tests and on shared global state. `ids.size() > 210`, `replies.size() > 700` and `ownCookies > 210` are only true because `LoginConcurrencyTests.THREADS` is 200. Someone lowering THREADS to speed the suite up gets an unexplained red leak test. `assertEquals(3, lines.size())` over `Support.LOG` breaks as soon as the router logs one more line, or if tests ever overlap.
  evidence: `assertTrue(replies.size() > 700, "only " + replies.size() + " replies recorded");` and the comment "200 logins in the concurrency test alone".
  fix_hint: Derive the thresholds from `LoginConcurrencyTests.THREADS`, for example `THREADS + 10`. Count only lines that contain the expected message instead of the size of the sublist.

- id: MNT-5
  severity: MINOR
  where: /home/user/spola/framework/modules/security-web/README.md:232 and :485 versus WebSecurity.java:208-213
  problem: The README says that registering `sessions.filter()` as well gives the request 500. The Javadoc and ARCHITECTURE.md are precise: only before `security.filter()` does every request get 500. After it, requests the rules deny still get their normal 401/403. Also the formLogin deviation appears in two tables with different wording (README.md:209 and :480), and "register only security.filter()" is repeated in about eight places.
  evidence: README:232 "registreras `sessions.filter()` också ger förfrågan 500"; WebSecurity.java:210-213 "registered before this filter, every request; registered after it, every request that the rules let through".
  fix_hint: Give the README the same qualified wording or a link to the Javadoc. Keep one formLogin row and link to it from the other.

- id: MNT-6
  severity: MINOR
  where: /home/user/spola/framework/modules/security-web/test/spola/security/web/LoginReadmeTests.java:69
  problem: The comparison-row check has a redundant first condition and is looser than it reads. `row.startsWith("| " + topic) || row.contains(" " + topic)` always passes when the first part does. The second part also accepts the topic appearing anywhere in the row, such as the Spola column.
  evidence: `comparison.stream().anyMatch(row -> row.startsWith("| " + topic) || row.contains(" " + topic))`
  fix_hint: Check only the first column, with `row.startsWith("| " + topic)`, as the deviation check below it does.

- id: MNT-7
  severity: MINOR
  where: WebSecurity.java:480 (`Builder.sessions` list), LoginSupport.java:64,76,209 versus Support.java:73,86,262
  problem: Several names are close enough to trip a later reader. In `Builder`, `sessions` is a `List<Sessions>`, a method and a parameter, and `WebSecurity.sessions` is a single `Sessions` or null. `LoginSupport.USERS`, `LoginSupport.authenticator()` and `LoginSupport.send(HttpServer, ...)` sit beside `Support.USERS`, `Support.authenticator()` and `Support.send(int, ...)`, but hold different users and signatures. `LOGIN`, `Login` and `login` also coexist in LoginExample, LoginSupport and WebSecurity. The ARCHITECTURE.md bullet "Säkerhetsfiltret kör sessionsfiltret självt" is about 18 lines and mixes five decisions.
  evidence: `this.sessions.add(Objects.requireNonNull(sessions, "sessions"))` and `if (sessions.size() > 1)`.
  fix_hint: Rename the builder list to `sessionCalls`. Rename the LoginSupport users to `LOGIN_USERS`, or document the difference in the class Javadoc. Split the ARCHITECTURE bullet into "who runs the session filter" and "what a double registration gives".

Summary: I found no BLOCKER or MAJOR issues. The change is cohesive and fits REGLER.md: no reflection or annotations, explicit wiring, `requires` and `exports` unchanged, and no new main source files. The Javadoc, README, ARCHITECTURE.md and PROGRESS.md explain why and where the limits are. The only hidden coupling is MNT-2, and it is documented in ARCHITECTURE.md and the code comment. The rest are doc-accuracy and test-structure nits.
