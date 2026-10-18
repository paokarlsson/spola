# Report: 20261018-1553-sakerhet-proxy-och-cookies

- Status: PASS
- Title: Security review part 2: proxy warning, cookie hardening and doc fixes
- Mission: uppdrag/15b-sakerhet-proxy-och-cookies-dokumentation.md (security review 2026-10-18, P1–P5)
- Rounds: 2 of 4

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | FAIL (0 blocker, 1 major, 10 minor) | green | 236b800 |
| 2 | PASS (0 blocker, 0 major, 8 minor reported) | green | 1f82568 |

## Built
One WARNING per ForwardedHeaders filter (sender address only) when an untrusted sender sends X-Forwarded-*;
a production setting with __Host-SESSION and secure(true) on both cookies (compiled CaddyExample); Docker
examples bind 0.0.0.0; corrected claims about ids in memory and private client addresses.

## Fixed in round 2
- BRK-1 (MAJOR): the P4 text still promised the id is gone from memory after the response; now says it stays
  in headers and connection buffers while a keep-alive connection is open.
- All round-1 MINOR findings (MNT-5 partly, with a rebuttal).

## Remaining findings (MINOR)
See round-2/findings.md.

## Notes
- Round 2's judge was skipped because all critics passed with no BLOCKER/MAJOR.
- Stash: –
