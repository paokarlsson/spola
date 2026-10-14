# Round 1 findings

VERDICT: PASS

The judge step was skipped: all three critics (breaker, auditor, maintainer) gave PASS with no
BLOCKER or MAJOR. The MINOR findings below are carried over as reported, without a ruling.

Counts: 0 blocker, 0 major, 7 minor (reported, not judged; BRK-1 and AUD-1 are the same point).

## MINOR
- BRK-1 / AUD-1: the README "Bakom Caddy" snippet binds loopback (`HttpServerOptions.DEFAULT`), so Caddy in
  another container gets 502; same pattern as 13a's BRK-1.
- MNT-1: `Sessions.attributes(...)` copies Secure and the call sites override it; call order decides.
- MNT-2: the scheme is read in both Sessions.apply and Csrf.begin; the invariant that they agree has no code comment.
- MNT-3: shared 13b test helpers live in SchemeFlowTests instead of a *Support class.
- MNT-4: SchemeCookieTests copies CsrfCookieTests' route table.
- MNT-5: SchemeConcurrencyTests uses a mutable `https` with a post-incremented counter.
- MNT-6: the Secure rule is restated in about 15 places.

Critic reports: critic-breaker.md, critic-auditor.md, critic-maintainer.md.
