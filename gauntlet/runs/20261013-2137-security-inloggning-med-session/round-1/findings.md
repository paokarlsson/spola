# Round 1 findings

VERDICT: PASS

The judge step was skipped: all three critics (breaker, auditor, maintainer) gave PASS with no
BLOCKER or MAJOR. The MINOR findings below are carried over as reported, without a ruling.

Counts: 0 blocker, 0 major, 11 minor (reported, not judged).

## MINOR
- BRK-1: PROGRESS.md says "se nedan" for the 11b bullet, which is above.
- AUD-1: the README's Spola example is split into helpers and takes a RouterBuilder; the extra deviation
  is explained in text but not in the deviation table.
- AUD-2: "handler not called" (criterion 3) is shown indirectly (mapper counter and sibling route).
- AUD-3: process note — the auditor ran `pkill -f "java Build.java"`, which may have hit another critic's
  build; the breaker's report is complete and its build was green.
- MNT-1: `config.booleanValue("cookie.secure")` in LoginExample's Javadoc and the README snippet does not
  exist on `Config` (it is on `Section`).
- MNT-2: "session filter already ran" is detected by catching any IllegalStateException before the chain.
- MNT-3: shared test helpers live in LoginFlowTests; the example's rule list is copied three times.
- MNT-4: leak-test thresholds and an exact log-line count depend on other tests' sizes.
- MNT-5: README wording on registering `sessions.filter()` as well is less precise than the Javadoc;
  the formLogin deviation appears twice.
- MNT-6: LoginReadmeTests comparison-row check is looser than it reads.
- MNT-7: close names (`sessions` list/method/field, LoginSupport vs Support members); a long ARCHITECTURE bullet.

Critic reports: critic-breaker.md, critic-auditor.md, critic-maintainer.md.
