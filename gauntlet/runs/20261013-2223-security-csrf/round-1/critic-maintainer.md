VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /home/user/spola/framework/modules/security-web/src/spola/security/web/Csrf.java:194-221 and 286-297
  problem: The "apply the cookie function once to a placeholder, validate name and value, copy the attributes" logic is a second copy of the logic in spola.web Sessions, so a new ResponseCookie attribute must be added in two modules or one of them silently drops it.
  evidence: Csrf.newCookie() copies path, secure, httpOnly, sameSite, domain and maxAge one by one. Sessions.java:530-562 (`template(...)`, `attributes(...)`, `PLACEHOLDER`) has the same structure. ARCHITECTURE.md only says "Som i 11a körs funktionen en gång" and does not name the duplication. Criterion 2 allowed "ett litet tillägg" in another module, but build.md does not weigh a shared helper such as `ResponseCookie.Builder` copying from a template. The "the function runs once" test covers today's attributes only, so a future attribute would go unnoticed.
  fix_hint: Note the mirroring in a comment on `newCookie()` ("keep in step with Sessions.attributes"), or add one shared copy helper to spola.web in a later mission. Dropping `Secure` by accident would be a security bug.

- id: MNT-2
  severity: MINOR
  where: /home/user/spola/framework/modules/security-web/src/spola/security/web/WebSecurity.java:615-660 (also the Csrf.java class Javadoc, WebSecurity.java:155-183, module-info.java:39-45, README.md:496-527, ARCHITECTURE.md:2855+)
  problem: The same CSRF contract (unchecked methods, check, order, when the cookie is set and replaced, no stored tokens) is written out in full in about eight places, so one rule change means editing all of them.
  evidence: `Builder.csrf` Javadoc restates the five bullets of the `Csrf` class Javadoc almost word for word. The list "GET, HEAD, OPTIONS and TRACE" appears in `Csrf.UNCHECKED`, the Csrf Javadoc, WebSecurity step 3, the WebSecurity "CSRF protection" section, Builder.csrf, module-info, README (twice) and ARCHITECTURE. Nothing but review keeps these in step.
  fix_hint: Make `Csrf` the single full description. Give `Builder.csrf` and the WebSecurity section a two-line summary with `{@link Csrf}`. Keep the full text in README and ARCHITECTURE, which the task asks for.

- id: MNT-3
  severity: MINOR
  where: /home/user/spola/framework/modules/security-web/src/spola/security/web/Csrf.java:243 and 322; WebSecurity.java:212 and 566; /home/user/spola/framework/modules/security-web/test/spola/security/web/CsrfCookieTests.java:91
  problem: The names do not tell the config type `Csrf` apart from the runtime object `Csrf.Protection`, and "Exchange" means two different things.
  evidence: The `Csrf` Javadoc says `@return the protection` for the config object. The field `WebSecurity.csrf` is a `Csrf.Protection`, while `Builder.csrf` is a `List<Csrf>`. The Javadoc "The CSRF protection, or null without Builder#csrf" does not say which of the two it means. The test record `CsrfCookieTests.Exchange` (a request and the status it must get) shares its name with the main-code `Csrf.Exchange` (per-request state).
  fix_hint: Rename the field to `protection`, or the nested class to something like `Csrf.Engine`. Rename the test record to `Probe` or `Scenario`.

- id: MNT-4
  severity: MINOR
  where: /home/user/spola/framework/modules/security-web/test/spola/security/web/CsrfSupport.java:73-130, 190-220 and 262-312; CsrfCookieTests.java:40-44; CsrfCheckTests.java:150-152
  problem: CsrfSupport copies about 100 lines from LoginSupport and Support, and `credentials` sits in an unrelated test class that others reach through a chain of calls.
  evidence: `madeUp`, `text`, `thrown`, `valueOf` (the same as `LoginSupport.idOf`), `raw` and `call` are near duplicates of the LoginSupport versions. `exchange()` repeats `Support.raw` plus `parse`, so that the reply can be recorded with masked values. `CsrfModeTests` calls `CsrfCheckTests.ada()`, which calls `CsrfCookieTests.credentials()`, which calls `Support.recordSent`. Criterion 2 forces some of the copying, because existing test files may not change.
  fix_hint: Move `credentials` and `ada` into CsrfSupport. Let `valueOf` call `LoginSupport.idOf`. Add a comment on `exchange()` saying it exists only to record a masked copy.

- id: MNT-5
  severity: MINOR
  where: /home/user/spola/framework/modules/security-web/test/spola/security/web/CsrfLeakTests.java:28 and 68; CsrfSupport.java:55-56, 138 and 142; CsrfCookieTests.java:238-245
  problem: The masking of the cookie value is written three times with regexes that differ slightly, and the text "<own value>" is hard-coded again instead of using `CsrfSupport.OWN_VALUE`. The "at most one Set-Cookie" check depends on test order.
  evidence: `OWN_LINE` and `OWN_FIELD` in CsrfSupport and `OWN_COOKIE` in CsrfLeakTests are nearly the same pattern. One uses `[^;\r\n]+` and the other `[^;\r\n]*`. `appendReplacement(head, "$1<own value>")` hard-codes the marker. CsrfCookieTests scans the global `Support.REPLIES` for "no reply of these tests", so it only sees replies recorded before it ran. The 200-thread test runs later and is never covered. Whoever adds a test that records an unmasked CSRF reply with `Support.raw` brings back the roughly 0.5% flaky `zPwd` failure.
  fix_hint: Keep one masking function in CsrfSupport and call it from both places. Move the "at most one XSRF-TOKEN per reply" check into CsrfLeakTests, which runs last and sees everything.

- id: MNT-6
  severity: MINOR
  where: /home/user/spola/framework/modules/security-web/src/spola/security/web/Csrf.java:125-127, 194-221 and 264
  problem: Small structural unclarities in the main code.
  evidence: `TOKEN_BYTES`, `TOKEN_LENGTH` and `isWellFormed` are package-private but only used inside Csrf.java (the tests use their own pattern), and 43 must stay in step with 32 by hand. `protection(problems)` returns `null` and also adds to `problems`, so the caller must know that null means a line was added; the early returns and the `before` count are hard to follow. `begin` builds the `Exchange` and does the allowed check in a single constructor-argument expression (`UNCHECKED.contains(...) || matches(...)`).
  fix_hint: Make the constants and `isWellFormed` private. Put the `allowed` boolean in a named local variable in `begin`. Consider `Optional<Protection>` or a thrown exception instead of null plus an out-parameter.
