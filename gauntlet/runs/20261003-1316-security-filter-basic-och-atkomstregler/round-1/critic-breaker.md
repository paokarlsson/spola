```
VERDICT: PASS
FINDINGS:
- id: BRK-1
  severity: MINOR
  where: framework/modules/security-web/src/spola/security/web/RequestMatcher.java (method factory) and Matchers.java:650 (Method.matches)
  problem: `RequestMatcher.method` accepts any token, lowercase included, but compares case-sensitively. So `method("get", under("/admin"))` builds without error and never matches a real `GET` or `HEAD`. Before `anyRequest().permitAll()` that rule silently leaves `/admin` open.
  evidence: `Matchers.Method.matches` does `actual.equals(method) || (method.equals("GET") && actual.equals("HEAD"))`. `RequestMatcher.method` only checks `Matchers.isToken(method)`, and "get" is a token. Chain: rule `method("get", under("/admin"))` + hasRole("ADMIN"), then `anyRequest().permitAll()`. `GET /admin` reaches `method.equals` as "GET" != "get", the rule is skipped, `permitAll` matches, and the handler runs anonymously. The spec asks for an exact method, so this is a fail-open trap for a typo, not a spec violation.
  fix_hint: Reject methods that contain lowercase letters in `method(...)`, or at least warn about them in the Javadoc.
```

I found no defect at BLOCKER or MAJOR level.

**Build:** I copied `framework/` to `/workspace/gauntlet/runs/20261003-1316-security-filter-basic-och-atkomstregler/round-1/scratch-breaker/fw` and ran `java Build.java` there. It exits 0 with BUILD OK, and `spola.security.web` passes 46 of 46 tests. Guardrails are green. My first run went red only because I had put my own `build.log` inside the copy.

**Live checks:** `Probe.java` in the same scratch directory starts a real server with PBKDF2 users and sends raw socket requests. Every answer was as specified:
- **Paths under a protected rule:** `/admin`, `HEAD /admin`, `/admin/x` and `/%61dmin` give 401 with the exact `WWW-Authenticate` and `Cache-Control: no-store` headers.
- **Wrong role:** gives 403 with no challenge.
- **Right role:** the handler sees the correct `Identity`.
- **Path the router rejects:** `//admin` and `/admin%2f` give 400 and never reach a handler.
- **GET-only rule:** `GET` and `HEAD /files/a` are protected. `OPTIONS` and `POST` fall through to the later `permitAll` rule.
- **Scheme spelling:** lowercase `basic` with several spaces is accepted.
- **Bad tokens:** a token with extra padding, or `Basic` with no token, gives 401.
- **Other forms:** `OPTIONS *` and absolute-form targets are handled correctly.

**Code review:**
- **Basic parsing:** checked against the spec. That covers token length before decoding, the padding and canonical Base64 checks, strict UTF-8, splitting at the first `:`, control characters, an empty username, and zeroing the decoded bytes and characters.
- **The filter:** checked the duplicate-`Authorization` 400, the order of mechanisms, `Rejected` giving 401 even on open paths, first-matching-rule with deny as the default, and the guard against the filter running twice.
- **Identity:** the identity attribute uses a private, identity-based key, so other code cannot forge it.
- **Missing filter and routing:** `require`/`identity`/`requireIdentity` throw `IllegalStateException` without the filter. Router dispatch and the matchers use the same decoded segments.
- **Interplay with `spola.security`:** `Authenticator` takes the same path for empty, too-long or unknown credentials.

**Process criteria:**
- The `Build.java` diff is only the new `MODULES` line.
- The `RouterBuilder.log` change, from package-private to public, is explained in ARCHITECTURE.md.
- The README comparison and deviation rows, the PROGRESS limitations and the ARCHITECTURE bullets are all present.
- The concurrency test uses 200 virtual threads, and the leak and control-character test exists.

(Orchestrator note: the scratch copy was moved out of the run directory to /tmp/scratch-breaker-20261003-1316; Probe.java is kept as round-1/breaker-Probe.java.)
