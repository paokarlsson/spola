VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /home/user/spola/framework/modules/web/src/spola/web/Sessions.java:230 and :563 (helper `attributes` at :598)
  problem: `attributes(...)` already copies `secure(template.secure())`, and both new call sites then overwrite it (`.secure(true)` for secureRemoval, `.secure(template.secure() || https)` for sessionCookie). A reader has to notice that call order decides the result.
  evidence: `attributes(ResponseCookie.of(options.cookieName(), ""), template).secure(true).maxAge(Duration.ZERO).build()` and `attributes(ResponseCookie.of(options.cookieName(), id), template).secure(template.secure() || https).build()`. The helper's Javadoc says "every attribute of template", with no hint that Secure is meant to be overridden.
  fix_hint: Give `attributes` a `boolean secure` parameter (the template value OR https) and drop the overrides at the call sites. Or say in the helper's Javadoc that callers override Secure.

- id: MNT-2
  severity: MINOR
  where: /home/user/spola/framework/modules/security-web/src/spola/security/web/WebSecurity.java:475-498 (and Csrf.java:295)
  problem: The cookie scheme is read in two places, `Sessions.apply` and `Csrf.Protection.begin`. The rule that both cookies see the same scheme depends on an invariant that no code comment states. The session filter must hand on a request with the same `scheme()`, and the comment on this is only in ARCHITECTURE.md and build.md. A later filter or wrapper that changes the request between the two reads would make SESSION and XSRF-TOKEN disagree about Secure, and nothing at the call site warns about it.
  evidence: `return sessions.filter().filter(request, withSession -> { ... return secure(withSession, next); })` followed by `csrf.begin(request)` with `request.scheme().equals("https")` inline in Csrf.java:295.
  fix_hint: Add a one-line comment at `csrf.begin(request)` in `secure()`: "the scheme is the same as the session filter read, because the session filter only adds an attribute". Optionally, give `Csrf` a small named helper for the `isSecure()` idiom, as `Sessions.isHttps` does.

- id: MNT-3
  severity: MINOR
  where: /home/user/spola/framework/modules/security-web/test/spola/security/web/SchemeFlowTests.java (helpers `template`, `SECURE_*`, `send`, `selfSigned`, `trusting`), used from SchemeCookieTests.java:70/75 and SchemeConcurrencyTests.java:42-51
  problem: The shared constants and helpers for the 13b tests live in a test class (SchemeFlowTests, about 300 lines). Two sibling test classes depend on it. The module's convention is a `*Support` class (CsrfSupport, LoginSupport). The file mixes keytool/TLS plumbing, a request recorder, cookie templating and the test list.
  evidence: `SchemeFlowTests.template`, `SchemeFlowTests.SECURE_XSRF` and `SchemeFlowTests::template` are called from SchemeCookieTests and SchemeConcurrencyTests.
  fix_hint: Move the constants, `template`, `templates` and the TLS helpers to a `SchemeSupport`. Keep only the flow tests in SchemeFlowTests.

- id: MNT-4
  severity: MINOR
  where: /home/user/spola/framework/modules/security-web/test/spola/security/web/SchemeCookieTests.java:51-61
  problem: `routes(...)` copies the route table of `CsrfCookieTests.router`, and the Javadoc says so ("as CsrfCookieTests.router has them"). If a route is added to the original, `CsrfCookieTests.exchanges()` and this copy drift apart. The 13b status test then fails in a confusing way, or silently covers less.
  evidence: `.get("/open/hello" ...).post("/open/echo" ...).get("/open/fail" ...).get("/admin/users" ...).get("/me" ...)` repeated here.
  fix_hint: Let `CsrfCookieTests.router` take a `RouterBuilder` and reuse it. That changes an existing test file, which the spec only allows for registration, README tests and comments. Failing that, keep the copy and add a test that the two route sets are equal.

- id: MNT-5
  severity: MINOR
  where: /home/user/spola/framework/modules/security-web/test/spola/security/web/SchemeConcurrencyTests.java:58-94
  problem: `flow` reuses one mutable `https` variable and a post-incremented counter (`boolean https = k++ % 2 == 0;`, then `https = k++ % 2 == 0;` four more times). Which request gets which scheme can only be seen by counting the `k++` calls.
  evidence: `int k = thread; boolean https = k++ % 2 == 0; ... https = k++ % 2 == 0; ... https = k % 2 == 0;`
  fix_hint: Use a small `IntPredicate` or a helper `boolean https(int thread, int step)`, so each request names its step. Or make `https` a `final` local per step.

- id: MNT-6
  severity: MINOR
  where: Rule text across /home/user/spola/framework/modules/web/src/spola/web/Sessions.java, SessionOptions.java, both module-info.java files, Csrf.java, WebSecurity.java (five places), both README.md files and ARCHITECTURE.md/PROGRESS.md
  problem: The Secure rule, the ordering with ForwardedHeaders and the "cannot be turned off" point are each restated in full in about 15 places. A future change, such as the switch that ARCHITECTURE.md says would be built "om ett behov kommer", needs edits in all of them, and one forgotten sentence leaves a false promise. The spec required most of these mentions, so this is a cost, not a defect.
  evidence: WebSecurity.java:44-47, 78-82, 158-160, 193-215, and the Builder.csrf and filter() Javadoc all mention it. Sessions, Csrf, SessionOptions and both READMEs each carry their own full bullet list.
  fix_hint: In WebSecurity, SessionOptions and the module-info files, keep one sentence plus a link to the owning section in `Sessions` or `Csrf`. Keep the full text only there and in ARCHITECTURE.md.

Overall: the behavior change is small and explicit. A boolean is read once per request and stored as a `final` field in the request's own state. Two prebuilt removal variants and `template.secure() || https` are the whole mechanism. There is no new reflection, no hidden wiring, no new public API, and no shared mutable state. The builder's startup-check decisions, the order limitation and the known limitations are all documented in the places a maintainer would look. The old "never from the scheme" text is replaced, with an explicit note in ARCHITECTURE.md that 13b replaced the decision. No finding reaches MAJOR.
