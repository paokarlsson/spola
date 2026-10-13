# Round 1 findings

VERDICT: PASS

The judge step was skipped: all three critics (breaker, auditor, maintainer) gave PASS with no
BLOCKER or MAJOR. The MINOR findings below are carried over as reported, without a ruling.

Counts: 0 blocker, 0 major, 12 minor (reported, not judged).

## MINOR
- BRK-1 / AUD-1: an in-flight request with the old id loses its session view when another request
  renews it (fail-closed choice, documented). Tomcat would let it keep the view. Revisit in 11b/11c
  if login races with polling matter.
- BRK-2: Javadoc and ARCHITECTURE say "a memory dump holds no valid ids"; it should say the store
  holds only SHA-256 of ids (PROGRESS.md already says the id lives as a String in headers).
- AUD-2: more than 200 cookies gives 400 on every route behind the session filter; not pinned by a test.
- AUD-3 / MNT-4: SessionLeakTests depends on suite order and static state filled by earlier tests.
- AUD-4 / MNT-6: an over-long Javadoc line in Sessions.java and name clashes (`template`, `attributes`).
- MNT-1: Sessions.java holds five jobs; consider a package-private SessionStore before 11b/11c grow it.
- MNT-2: `alive`/`visible`/`toString` read as queries but can end an expired session; `expireIfDue`
  hides its return value.
- MNT-3: `Session.generation` is a package-private mutable field guarded only by a comment.
- MNT-5: `invalidate` after the response is built, and a `maxAge` in the cookie function, are documented
  only in ARCHITECTURE/PROGRESS, not in the Javadoc.

Critic reports: critic-breaker.md, critic-auditor.md, critic-maintainer.md.
