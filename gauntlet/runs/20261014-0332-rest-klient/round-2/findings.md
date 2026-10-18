# Round 2 findings

VERDICT: PASS

The judge step was skipped: all three critics gave PASS with no BLOCKER or MAJOR. The MINOR findings
below are carried over as reported, without a ruling. Round 1's three upheld MAJOR findings (BRK-1
non-ASCII body in transport causes, BRK-2 echoed secrets in the response-exception message, BRK-3
19-digit Content-Length) are fixed; the breaker and auditor confirmed it.

Counts: 0 blocker, 0 major, 18 minor (reported, not judged; BRK-1/AUD-2 and BRK-2/AUD-1 are the same points).

## MINOR
- BRK-1 / AUD-2: with `Authorization: Bearer <key>` only the whole header value is hidden; a server that
  echoes just the token ("Invalid token: <key>") shows the key in getMessage(). The README sentence reads
  as if an echoed key is hidden. Fix: also hide the credentials after the auth scheme, or narrow the text.
- BRK-2 / AUD-1: the docs say the spola.json cause quotes "up to 40 characters"; in fact up to 40 per
  value in up to 100 problems (plus 200 of an exception message). Narrow the wording.
- AUD-3: U+061C escaping and "..." after a cut go beyond the mission lists (judge's optional hints; documented).
- MNT-1 … MNT-13: Text.limit precondition, the hiding rule written in ~10 places (root README over-claims),
  two places deciding "safe text" classes, duplicated 2xx check, missing thread-safety comment, the name
  `secrets`, string-keyed failure kinds, duplicated raw-server scripts, a vacuous timeout test, an unbounded
  wait in a test, positional rows in SetupTests, fully qualified names, unexplained UTF-8 arithmetic.

Critic reports: critic-breaker.md, critic-auditor.md, critic-maintainer.md.
