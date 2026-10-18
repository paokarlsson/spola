# Round 1 findings

VERDICT: PASS

The judge step was skipped: all three critics (breaker, auditor, maintainer) gave PASS with no
BLOCKER or MAJOR. The MINOR findings below are carried over as reported, without a ruling.

Counts: 0 blocker, 0 major, 11 minor (reported, not judged).

## MINOR
- BRK-1: with csrf on, a late `WebSecurity.logout` (after the response) removes the session and then
  throws; the Javadoc does not say the session is already gone.
- AUD-1: SourceRulesTests changed 11 → 12 source files (needed for the new public `Csrf` class; spec ambiguity).
- AUD-2: the cookie function runs once in `build()` on a placeholder, as in 11a (spec ambiguity, documented).
- AUD-3: a handler that sets its own XSRF-TOKEN could give two Set-Cookie for it (documented misuse).
- AUD-4: a ~1/1000 chance that 10b's LeakTests trips on a random 4-character marker in 11b's unmasked
  session ids; existing tests could not be changed in this mission.
- MNT-1: the cookie-template copy logic is duplicated between Csrf and Sessions.
- MNT-2: the CSRF contract is written out in about eight places.
- MNT-3: names do not separate `Csrf` (config) from `Csrf.Protection`; "Exchange" means two things.
- MNT-4: CsrfSupport copies about 100 lines from LoginSupport/Support; `credentials` lives in a test class.
- MNT-5: cookie-value masking is written three times; the "at most one Set-Cookie" check depends on test order.
- MNT-6: package-private constants only used in Csrf.java; null-plus-out-parameter in `protection(...)`.

Critic reports: critic-breaker.md, critic-auditor.md, critic-maintainer.md.
