```
VERDICT: PASS
FINDINGS:
- id: BRK-1
  severity: MINOR
  where: framework/modules/web/src/spola/web/WebException.java:171-178 (private invalid(List,int)) together with Problem.java FieldError.checkCode (MAX_CODE = 100)
  problem: spola.validation accepts a violation code of any length through Errors.reject, and any code at all through a hand-built Violation (Violation has no checks), but WebException.invalid turns each violation into a Problem.FieldError, which throws IllegalArgumentException for a code over 100 characters or one containing a space. A validation failure that should be a 400 becomes a 500.
  evidence: Errors.reject only calls Text.code(code) (modules/validation/src/spola/validation/Text.java:155-167), which checks for an empty code and for printable ASCII without spaces, but not the length. Violation's constructor (Violation.java) checks only for null. FieldError.checkCode throws when `code.length() > MAX_CODE`, or for any char `<= 0x20 || >= 0x7F`. The builder has documented this as `@throws IllegalArgumentException` and in PROGRESS "Kända problem". It only happens with a code over 100 characters or a hand-built Violation, which is unusual.
  fix_hint: In WebException.invalid, leave the code out or cut it instead of throwing. Otherwise keep the documented limit as it is.
FINDINGS SUMMARY / what was tried:
- I ran `java Build.java` on a fresh copy (scratch-breaker/fw, out/ removed). It exited 0, with spola.web at 730/730 and all modules green.
- Server probes with HttpClient (scratch-breaker/src/Probe.java, Probe2.java). All of these matched the spec exactly:
  - an empty INT for optional counts as missing;
  - `all` keeps the original index after an empty element (`$.nums[2]`);
  - an empty body gives `required`;
  - `age=1&age=2` gives `single`;
  - `email&terms` without `=` gives email "" and terms=false;
  - `+5`, `%2B5` and `%205` give `type`;
  - a validator code ends up in `errors`, with the singular detail;
  - bindQuery covers optional empty, type, required and single, and a primitive-int record NPE after a missing required gives a 400 with `required`.
- No echoing: password fields with malformed percent-encoding in both the form body and the query give a generic detail, and the marker is not echoed.
- I compared the FormFields.path copy with spola.validation Path, Text.isIdentifier and appendEscaped. The algorithm is the same for member names and indexes.
- Exception rule, the limit (checked before rendering, count capped), use after close, and the order of null checks before the body is read: all correct.
- The SourceRulesTests change is the minimal one forced by criterion 2. Without it the build cannot be green, so I did not report it as a defect.
- ARCHITECTURE "Validering del 2" covers all 10 required points. PROGRESS marks row 09 as done and lists the limitations. The README has both Spring/Spola examples, the 6 table rows and the deviation rows.
```
