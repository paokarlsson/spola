# Report: 20261013-2333-security-bcrypt

- Status: PASS
- Title: BCrypt hashes and a delegating {id} password hasher (spola.security)
- Mission: uppdrag/12-security-bcrypt-och-delegerande-hasher.md (need: psykologen, losenord-bcrypt)
- Rounds: 2 of 5

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | FAIL (0 blocker, 1 major, 10 minor) | green | a83d0bc |
| 2 | PASS (0 blocker, 0 major, 5 minor reported) | green | fff56cd |

## Built
`BCryptPasswordHasher` (`defaults()` cost 10, `of(cost)`), verifying $2a$/$2b$/$2y$ and writing
$2a$ like Spring; Blowfish written out with tables derived from π; `PasswordHasher.delegating(defaultId, Map)`
with Spring's `{id}` prefix. Passwords of 1–72 UTF-8 bytes without U+0000 only (CVE-2025-22228). Canonical
parsing, constant-time comparison, `needsRehash` only for lower cost. Tested against jBCrypt, Openwall and
libxcrypt vectors and the JDK's Blowfish cipher.

## Fixed
- Round 1 BRK-1 (MAJOR): a hasher with lower cost than the stored hashes gives a permanent username timing
  oracle. Documented in Javadoc, README, PROGRESS and ARCHITECTURE: use at least the highest stored cost.
- Round 1 MINOR: cost limits in one place, Blowfish key schedule without a null switch, named fixtures,
  clearer method names, sturdier source check, 5 s guard on cost-31 tests.

## Remaining findings (MINOR)
See round-2/findings.md (test-helper placement, a boolean parameter, a copied message prefix, the hash
layout split over two classes, a source check tied to variable names).

## Notes
- Round 2's judge was skipped because all critics passed with no BLOCKER/MAJOR.
- Stash: –
