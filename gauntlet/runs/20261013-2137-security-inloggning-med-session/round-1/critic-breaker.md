```
VERDICT: PASS
FINDINGS:
- id: BRK-1
  severity: MINOR
  where: framework/PROGRESS.md:743
  problem: The spola.security.web known-problems bullet says session login "finns sedan 11b (se nedan)", but the 11b bullet it points to is above it, at line 695 (first bullet under "Kända problem och begränsningar").
  evidence: grep -n on PROGRESS.md -> "694:## Kända problem och begränsningar", "695:- **Inloggning med session (spola.security.web, 11b):** ...", "743:  finns sedan 11b (se nedan), men ingen formulärinloggning ..."
  fix_hint: Change "se nedan" to "se ovan", or point to the 11b bullet by name.
```

What I tried (none of it broke):
- Full build of a copy (scratch-breaker/fw, JDK 25): BUILD OK, exit 0. spola.security.web: 68 tests, all ok. Guardrails green. git status: only security-web, the docs and the root README changed. No Build.java change. Of the existing test files, only SecurityWebTests changed. requires/exports are unchanged.
- Raw-socket harness against the shipped jars (scratch-breaker/src/Exp1.java, Exp2.java), all behaving correctly:
  - Logging in as bertil on top of anna's live session gives a new id. The old id gets 401, the new one is bertil, and /api/admin gives 200.
  - A failed login on a logged-in session sends no Set-Cookie, and the session stays logged in.
  - HEAD /api/auth/me with a session gives 200.
  - A session plus a wrong Basic header: the session wins, as designed and documented.
  - Logout sends Max-Age=0. The old id then gets 401, and size() is 0.
  - A unicode username and password log in fine.
- Code review of WebSecurity.apply/secure/login/logout/requireSessions against the Sessions/SessionState/SessionEntry code from 11a:
  - Fixation race: a request on the planted old id that runs during the renew cannot see LOGGED_IN. Its view's generation is checked under the entry lock in get/use.
  - The reached[0] wrapping only rewraps an IllegalStateException the session filter throws before calling on. The router Steps map handler and inner-filter exceptions, so only body failures and Errors pass through, and the session filter abandons on those.
  - AttributeKey compares by identity, so LOGGED_IN, OWNER and CONTEXT cannot be forged.
  - Identity is an immutable record, so the snapshot claim holds.
  - JSON codec and parser errors never repeat password values (only types or single characters), and WebRequest.toString holds no cookies.
- Things that come from 11a's design rather than 11b, not reported:
  - A concurrent getOrCreate on the pre-login id, while a login is running, creates a new anonymous session whose cookie can overwrite the login cookie.
  - More than 200 cookies gives 400 on every path once sessions(...) is on.
  - A response with its own Cache-Control keeps it on Set-Cookie responses.
- CSRF / login-CSRF are out of scope (11c).
