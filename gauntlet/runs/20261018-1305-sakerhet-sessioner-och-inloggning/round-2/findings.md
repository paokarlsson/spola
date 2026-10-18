# Round 2 findings

VERDICT: PASS

The judge step was skipped: all three critics gave PASS with no BLOCKER or MAJOR. Round 1's upheld MAJOR
(BRK-1, a login racing `Sessions.renew` on one cookie left a logged-in session outside the per-user
index) is fixed; the breaker's round-1 probe now gives exactly the limit and 0 inherited notes, and a new
HTTP stress probe found no inconsistency. The MINOR findings below are carried over without a ruling.

Counts: 0 blocker, 0 major, 11 minor (reported, not judged; AUD-1 = MNT-2).

## MINOR
- BRK-1 / AUD-2: ARCHITECTURE says a new session "never, not even briefly" exceeds the limit; with
  concurrent logins of the same user it briefly does (documented elsewhere in the same section).
- BRK-2: `login` Javadoc "never this one" needs the qualification for concurrent logins of the same user.
- BRK-3: Sessions Javadoc says the index holds only live sessions; expired ones stay until lookup/cleanup.
- MNT-1: three different `release` methods in one package.
- MNT-2 / AUD-1: an unreachable defensive branch in `SessionEntry.hold`.
- MNT-3: the request's hold is three parallel nullable fields.
- MNT-4: the default 10 is written as a literal in several Javadoc places.
- MNT-5: the CacheControl drift guard gives an unreadable failure message.
- MNT-6: race-test helpers shared via static imports and duplicated across modules.

Critic reports: critic-breaker.md, critic-auditor.md, critic-maintainer.md.
