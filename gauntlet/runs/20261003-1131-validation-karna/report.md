# Report: 20261003-1131-validation-karna

- **Status:** PASS
- **Title:** Validation part 1: composable validators and violation reports
- **Mission:** uppdrag/09a-validation-karna.md (moved to uppdrag/klara/)
- **Rounds:** 1 of 5

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | PASS (0 blocker, 0 major, 7 minor) | green | 61de226 |

## Built
A new standalone module, `spola.validation`. Validators are built as plain Java from getters and `Constraint` values, with no annotations or reflection. The module covers fields, optional fields, nested objects, element-wise checks, class-level and cross-field rules, include/when, lazy recursion and 27 built-in constraints. A violation has a path, a code and a message, and never carries the rejected value. Paths use the JSON codec's notation. At most 100 violations are kept, but all are counted. Also delivered: the README with the Spring Boot comparison, PROGRESS.md and ARCHITECTURE.md updates, and 251 tests.

## Fixed
Nothing needed fixing: there were no BLOCKER or MAJOR findings in round 1. The judge downgraded BRK-1 (`oneOf` with a collection of the wrong type compiles) to MINOR, because the mission fixes the signature as `Collection<? extends T>`.

## Remaining MINOR findings (see round-1/findings.md)
- BRK-1: the Javadoc claims that a wrong type is a compile error, but this doesn't hold for `oneOf`. The doc claim should be narrowed.
- BRK-2: `ValidationException` cannot be serialized, because `Violation` is not `Serializable`.
- AUD-1: the spec asks for `\u001B` but also for text identical to the codec. The codec-identical `\u001b` was chosen.
- MNT-1–4: a repeated varargs copy block; a private `notNull` helper whose name is easy to confuse with the constraint; the path rendering copied from the codec (risk of drift); and null-semantics Javadoc spread over several places.

## Assumptions
- Messages the mission left as "documented" were chosen by the builder, written in Javadoc and tested word for word.
- The empty and size checks for collections and maps have separate names: `notEmptyCollection`, `notEmptyMap` and `mapSize`.

## Stash
–
