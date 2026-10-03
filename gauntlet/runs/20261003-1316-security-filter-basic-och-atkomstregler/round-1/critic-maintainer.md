```
VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /workspace/framework/modules/security-web/src/spola/security/web/Matchers.java:101-117 and HttpBasic.java:151
  problem: The RFC 9110 token helpers (`isToken`, `isTchar`) sit in `Matchers`, which is about path matching, and `HttpBasic` reaches into it for scheme parsing.
  evidence: `Matchers.isTchar(value.charAt(schemeEnd))` in HttpBasic
  fix_hint: Move the token helpers into `Text` or a small `Tokens` class, or into `HttpBasic` if it is the only user.

- id: MNT-2
  severity: MINOR
  where: /workspace/framework/modules/security-web/src/spola/security/web/Rules.java:13-14 and :68-86
  problem: The role limit and the role character rules are copied from `spola.security`. A change there would silently leave the two out of sync.
  evidence: "/** The longest role, as in {@code spola.security}. */ static final int MAX_ROLE_CHARS = 100;"
  fix_hint: Reuse a public constant or validator from `spola.security`, or add a test that pins the two limits to each other.

- id: MNT-3
  severity: MINOR
  where: /workspace/framework/modules/security-web/src/spola/security/web/WebSecurity.java:195-202 and :264-279
  problem: `requireIdentity` needs a second hidden attribute (`OWNER`) and a private `WebException` subclass that overrides `toResponse`, just to return the filter's own 401. This is a lot of machinery for one error path.
  evidence: `request.attribute(OWNER).orElseThrow(...)` followed by `throw new Unauthorized(owner)`
  fix_hint: Keep it, since the builder documented it. Alternatively, fold `OWNER` into `SecurityContext` as a package-private field, so there is one attribute and not two.

- id: MNT-4
  severity: MINOR
  where: /workspace/framework/modules/web/src/spola/web/RouterBuilder.java (`log`)
  problem: A method was made public API only so another module's tests could capture log output. This widens the public surface of `spola.web` for a test need.
  evidence: build.md: "`log(System.Logger)` was package-private and is now public ... criterion 11 requires the router's log lines to be captured"
  fix_hint: This is documented in ARCHITECTURE.md. Revisit if `Router` gets a proper logging option.

- id: MNT-5
  severity: MINOR
  where: /workspace/framework/modules/security-web/src/spola/security/web/HttpBasic.java:146 and WebSecurity.java:215
  problem: The duplicate-`Authorization` check exists in two places, because `HttpBasic` can be used alone.
  evidence: Both sites call `WebSecurity.duplicateAuthorization()`.
  fix_hint: The shared factory already limits the duplication, and the Javadoc explains it. No action is needed beyond keeping the factory.
```
