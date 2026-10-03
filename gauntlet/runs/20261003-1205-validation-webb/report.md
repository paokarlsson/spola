# Report: 20261003-1205-validation-webb

- **Status:** PASS
- **Title:** Validation part 2: validation and form binding in spola.web (mission 09b, `uppdrag/09b-validation-webb.md`)
- **Rounds:** 1 of 5

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | PASS (0 blocker, 0 major, 5 minor) | green | c190458 |

## What was built
- `spola.web` now has `requires transitive spola.validation`. `spola.validation` itself is unchanged.
- `Problem.FieldError` gained an optional `code`.
- New `WebException.invalid(Validated.Invalid)` and `WebException.invalid(ValidationException)`.
- New `readJson(codec, validator)` and `readJson(codec, limits, validator)`.
- New `FormBinder`/`FormFields` that collect every binding error instead of stopping at the first.
- New `readForm(binder[, validator])` and `bindQuery(binder[, validator])`.
- 139 new tests in `spola.web`, plus README examples next to Spring Boot.
- ARCHITECTURE has a new section "Validering del 2", and PROGRESS marks row 09 as done.

The judge was skipped because every critic gave PASS with no BLOCKER or MAJOR.

## Remaining findings (MINOR)
- **BRK-1:** a `Violation` code over 100 characters or with spaces makes `WebException.invalid` throw, so the client gets 500. This is documented.
- **AUD-1:** `SourceRulesTests` had to change its expected `requires` list, which conflicts with criterion 3. The change was forced by criterion 2 and is accepted.
- **MNT-1:** the path rendering now exists in four places.
- **MNT-2:** `FormFields.bind` has the same throw check twice.
- **MNT-3:** the empty-value rule uses an identity check against `Param.STRING`, and `Param` does not document this.

## Assumptions
These are the builder's interpretations, documented in Javadoc and tested:
- An empty checkbox value gives `false`.
- `all` skips empty values except for `STRING`, and errors keep their original index.
- Any exception from a `Param` parser counts as a `type` error.

## Stash
–
