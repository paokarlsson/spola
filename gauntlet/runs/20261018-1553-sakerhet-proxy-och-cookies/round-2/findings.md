# Round 2 findings

VERDICT: PASS

The judge step was skipped: all three critics gave PASS with no BLOCKER or MAJOR. Round 1's upheld MAJOR
(BRK-1, the P4 text still promised the session id is not kept in memory after the response) is fixed in all
places and guarded by a README test. The MINOR findings below are carried over without a ruling.

Counts: 0 blocker, 0 major, 8 minor (reported, not judged).

## MINOR
- BRK-1: Sessions Javadoc/README still say the id never appears in any toString; Response/Headers.toString
  and WebRequest.cookies() show it. Scope the sentence to the session layer as ARCHITECTURE does.
- BRK-2: README "Sessioner" says `idleTimeout` (60 s) without naming HttpTimeouts; confusable with
  SessionOptions.idleTimeout (30 min).
- AUD-1: one CaddyExampleTests needle still depends on a Markdown line wrap.
- AUD-2: "the server keeps no tokens, not in memory" (Csrf, WebSecurity, README) has the P4 shape.
- MNT-1: the P4 caveat is written in about seven places; spola.http has no pointer to it.
- MNT-2: SchemeFlowTests depends on the README example CaddyExample.security.
- MNT-3: ForwardedReadmeTests still has a line-wrap needle, a shadowing local and fully qualified names.
- MNT-4: the sample warning in the ForwardedHeaders Javadoc is unchecked; logger name literal in two classes.

Critic reports: critic-breaker.md, critic-auditor.md, critic-maintainer.md.
