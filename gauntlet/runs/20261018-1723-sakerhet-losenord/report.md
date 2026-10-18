# Report: 20261018-1723-sakerhet-losenord

- Status: PASS
- Title: Security review part 3: conditional hash upgrade and password hardening
- Mission: uppdrag/15c-sakerhet-losenord.md (security review 2026-10-18, L1–L4)
- Rounds: 1 of 4

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | PASS (0 blocker, 0 major, 11 minor reported) | green | 0bc8e7b |

## Built
`PasswordUpgrade.upgrade(username, verifiedHash, newHash)` with a compare-and-set in `InMemoryUserStore.upgrader()`;
`Authenticator` turns an unreadable stored hash into BAD_CREDENTIALS after one dummy check and a WARNING with
only the class name; PBKDF2 hashes capped at 32 bytes for new hashes (64 still verify and are rehashed); the
timing caveat across algorithms is documented.

## Remaining findings (MINOR)
See round-1/findings.md. Most notable: a {pbkdf2} user with a password bcrypt refuses is never upgraded, which
the new caveat does not mention.

## Notes
- The judge was skipped because all three critics passed with no BLOCKER/MAJOR.
- Stash: –
