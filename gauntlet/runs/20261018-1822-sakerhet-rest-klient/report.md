# Report: 20261018-1822-sakerhet-rest-klient

- Status: PASS
- Title: Security review part 4: hide secrets and reject dot paths in client
- Mission: uppdrag/15d-sakerhet-rest-klient.md (security review 2026-10-18, K1–K5)
- Rounds: 1 of 4

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | PASS (judge: 0 blocker, 0 major, 11 minor) | green | 53f304c |

## Built
Authorization/Proxy-Authorization credentials after the scheme and decoded Basic user:password/password are
hidden; `RestClientResponseException.safeExcerpt()` for log lines; docs warn about jdk.httpclient.HttpClient.log
and jdk.internal.httpclient.debug; "." and ".." segments (also %2e, and before ";") are refused; corrected text
on the spola.json cause.

## Remaining findings (MINOR)
See round-1/findings.md. Most notable: when the API key is the Basic user-id (Stripe-style `key:`) and the
server echoes only the user-id, it is not hidden (judge lowered from MAJOR: outside K1's rule); javax.net.debug
also prints Authorization.

## Notes
- The breaker's BRK-1 was lowered to MINOR by the judge.
- Stash: –
