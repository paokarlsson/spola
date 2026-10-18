VERDICT: PASS
FINDINGS:
- id: BRK-1
  severity: MINOR
  where: framework/ARCHITECTURE.md:3713-3715 ("Gränsen tillämpas före och efter")
  problem: The design doc says the pre-trim means "en ny session gör aldrig användarens sessioner fler än gränsen, inte ens en kort stund". The same section (lines 3737-3738, "högst gränsen plus antalet inloggningar som hanteras samtidigt") and the builder's own test say otherwise.
  evidence: framework/modules/web/test/spola/web/SessionLoginRaceTests.java:106 uses limit 1. A slow login is held while a quick login of the same user creates a new session, and the test then asserts `assertEquals(2, sessions.sessionsOf("anna"), "the slow login's session is held, not ended")`. So a new session does take the user above the limit for a short time.
  fix_hint: Drop "inte ens en kort stund", or add "utan samtidiga inloggningar av samma användare".
- id: BRK-2
  severity: MINOR
  where: framework/modules/security-web/src/spola/security/web/WebSecurity.java:425 (`login` Javadoc: "the user's least recently used ones end, never this one") and :156 ("never the new login")
  problem: When two logins of the same user run at once, another login's release can end this login's session after its 200 with `Set-Cookie` was built. The method-level promise does not say so, although the class Javadoc and Known gaps do.
  evidence: SessionLoginRaceTests.java:113 asserts `assertEquals("nobody", whoami(server, quick), "the limit applied when the slow login was done")`. The quick login finished with 200 and a cookie, and its session was then ended by the slow login's `release()` → `limit(user, 1, keep = slow)`.
  fix_hint: Qualify it, for example "never this one while it is being handled; with logins of the same user at the same time, the response built last decides".
- id: BRK-3
  severity: MINOR
  where: framework/modules/web/src/spola/web/Sessions.java:204-206 ("The index holds only live sessions: a session leaves it when it ... expires")
  problem: An expired session stays in the name → sessions index until it is next looked up, swept or passed over by `limit`, the same as in the store. So the index does not hold "only live sessions".
  evidence: SessionUserTests.java:235 asserts `assertEquals(2, sessions.users(), "cecilia and david: expired but not yet removed")`, and they leave only after `clock.advance(SWEEP_INTERVAL)` plus a request (line 239). This is not a leak, since the cleanup removes them.
  fix_hint: Say "an expired session leaves it when it is next looked up or at the next cleanup", as `size()` already does for the store.

What I tried that found no defect:
- A full `java Build.java` on a copy (round-2/scratch-breaker/fw): BUILD OK, exit 0. spola.web ran 1134/1134 and spola.security.web 276/276. The suites "logins at the same time (15a)", "logins that race on one cookie" and "the Cache-Control rules of the security filter" appear in the reports, and guardrails are clean.
- The round-1 probes against the new jars (round-1/scratch-breaker/probe/Probe.java). G4 checked logged-in /me, `max-age=60, Public`, `cache-control: s-maxage=60`, `private, no-cache`, `max-age=60`, a mapped 404 and the router's 404: all as specified. Anonymous `public` on permitAll is unchanged, and a login with `PUBLIC, max-age=60` gets no-store. Sequential G2 shows no note for bertil and 401 on anna's old id. With the in-flight LRU request, nothing is written into the ended session.
- A new HTTP stress probe, round-2/scratch-breaker/probe/src/Stress.java plus spola/web/Peek.java on the classpath for `sessionsOf`/`users()`. It runs 30-200 virtual-thread clients and 5 users at limits 1, 2, 3 and 10, with random login (with or without a cookie), Sessions.renew, logout, getOrCreate cart, note writes and /who reads. 10-70% of requests use another client's cookie (a shared computer). Afterwards every issued id was checked:
  - every user had at most the limit of working sessions;
  - `sessionsOf(user)` equalled that user's working sessions exactly;
  - `size()` equalled the live sessions (anonymous ones included), and `users()` matched;
  - bertil never saw a note written as anna, either during the run or afterwards;
  - there were no 500s, no deadlock and no stuck holds (3 more sequential logins per user always left exactly the limit);
  - with maxSessions 300 the only refusals were the documented 503s from anonymous carts filling the store.
- Expiry.java, the same stress with a clock advancing 50 ms every 10 ms and idleTimeout 1 s: the index was consistent after the run. After +61 s and one request, `size()=0` and `users()=0`.
- Code review of the hold counter, which balances on every path: renewFor→recordHold→release in finish/abandon, a double renewFor in one request, and G2 inside one request. A negative `logins`, which would make `limit` spin, only arises on an entry that has already ended. The lock order (SessionState → UserSessions → SessionEntry → CHM) holds: no entry-locked code takes a monitor. A stale empty `UserSessions` after `leave` cannot hide live entries. The G2 decision cannot be raced, because a changed owner always bumps the generation. G5's CAS and the `nothingExpiresBefore` skip are in place.
- Scope: module-info has Javadoc-only changes and requires/exports are unchanged. No annotations beyond @Override/@Serial/@SuppressWarnings, and no reflection. Changes are limited to web, security-web, ARCHITECTURE.md and PROGRESS.md. The required "ersätter inte en mänsklig granskning" sentence is in PROGRESS.md.
