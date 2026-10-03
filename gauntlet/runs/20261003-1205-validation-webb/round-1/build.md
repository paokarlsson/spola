# Round 1 build: Validering del 2 (09b)

## Changes

Main code (`framework/modules/web/src/`):
- `module-info.java`: added `requires transitive spola.validation` and updated the Javadoc (the direction of the dependency, the new bullet). No other new `requires`.
- `spola/web/Problem.java`: `FieldError(String path, String message, Optional<String> code)`. The old 2-arg constructor is kept and gives an empty code. `toJson()` writes `path`, `message`, then `code` (only when present). A code must be 1 to 100 printable ASCII characters with no spaces, otherwise `IllegalArgumentException`. Added `MAX_CODE = 100`.
- `spola/web/WebException.java`: `invalid(Validated.Invalid<?>)` and `invalid(ValidationException)`, both built by one private method. Status 400, standard title, detail `The request has N validation error(s).` plus ` The first 100 are listed.` when count > listed. `errors` carry the code.
- `spola/web/WebRequest.java`: added `readJson(codec, validator)`, `readJson(codec, limits, validator)`, `readForm(binder)`, `readForm(binder, validator)`, `bindQuery(binder)`, `bindQuery(binder, validator)` and a private `validated(...)`. Javadoc covers the order, codes, the limit, exceptions and the no-echo rule.
- `spola/web/FormBinder.java` (new): `@FunctionalInterface` with `bind(FormFields)` and the default `bind(FormData)`.
- `spola/web/FormFields.java` (new): the collector. `required`, `optional`, `all` and `flag`, the codes `required`/`type`/`single`, the empty-value rule (`param == Param.STRING`), at most 100 kept and all counted (the limit is checked before the path is rendered), the exception rule, `IllegalStateException` after the binding, and the private copy of the path rendering.
- `spola/web/Param.java`: package-private `convertOrNull(text)`, so that binding never sees `Param`'s value-quoting message.

Tests (`framework/modules/web/test/`):
- New suites: `FieldErrorTests` (crit 3 and 10 unit), `ValidatedJsonTests` (crit 4, 9 JSON, 10 server), `FormBinderTests` (crit 5 table with 52 rows, crit 6, crit 8), `ValidatedFormTests` (crit 7, 9 form, 11), `ValidationReadmeTests` (crit 12).
- New README sources: `OrderApi.java` and `SignupApp.java`.
- `WebTests.java`: the 5 new suites are registered.
- `SourceRulesTests.java`: **changed** (see Known gaps / spec conflict). The expected `requires` list and the expected `spola.*` set now include `spola.validation`, and the test name was updated.

Docs:
- `modules/web/README.md`: intro sentence, 6 comparison-table rows, a section "Validering av JSON och formulär" with both examples next to Spring Boot, the `ValidationException` mapper line, 6 deviation rows, and the "Felformat för kroppsfel" row updated to mention `code`.
- `ARCHITECTURE.md`: new section "Validering del 2" with every decision required by criterion 14.
- `PROGRESS.md`: new 09b section (row 09 done, 09a + 09b), "Återstår" updated, known limitations added.

## Verification

`java Build.java` in `framework/` (final run):
```
spola.guardrails: OK - 17 tests, 17 ok, 0 failed ...
spola.json: OK - 417 tests ...
spola.http: OK - 283 tests, 283 ok ...
spola.validation: OK - 251 tests, 251 ok ...
spola.web: OK - 730 tests, 730 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 2360 ms
spola.demo: OK - 43 tests ...
BUILD OK in 82080 ms
exit=0
```
`spola.web` had 591 tests before this round, so 139 are new.

Other checks:
- `git diff --quiet -- framework/modules/validation/src`: no changes.
- `grep -rn "java.lang.reflect\|java.lang.invoke" framework/modules/web/src`: no hits. `SourceRulesTests` also checks this and is green.
- The new tests caught a real bug during the round (`required` did not record a missing field). It is fixed, and table case [2] covers it.
- Baseline note: my first baseline run before any change failed once in `spola.http` (flaky under load). The second baseline and every later run were green.

## Criteria mapping
1. Green build. New suites run in `spola.web` (730 tests).
2. `module-info` has `requires transitive spola.validation`. The validation src is unchanged and there is no reflect/invoke.
3. `FieldErrorTests` covers: an empty code, the toJson order and omission, 10 invalid codes (space, control characters, 101 characters, empty, non-ASCII), and the exact mapping-error JSON. That last check uses both a literal and the pre-09b format rebuilt from `JsonMappingException.problems()`. It also checks that the validated route gives the same mapping error.
4. `ValidatedJsonTests` covers: a valid value; 5 violations (fields plus nested list) as exact JSON; 8 "same as without validator" cases (415 ×3, empty body, 413, invalid JSON, mapping ×2) with the counting validator at 0; 250 → 100 errors and the exact detail; a validator exception reaching a mapper (`assertSame`) or giving 500 without its message; and `readJson(codec, limits, validator)` (413 at 50 bytes, maxDepth 3, and a larger limit than the router's).
5. `FormBinderTests` "the binding table": 52 rows, each with an exact value or exact `errors`, covering every listed item.
6. 1 000 invalid values → exactly the first 100 plus the exact detail (also 101/100). A record NPE gives a 400 with `required`. A thrown instance with no collected errors passes as the same instance. A saved `FormFields` throws ISE in all 4 methods. Also tested: a WebException after errors gives the 400, and an `Error` always passes.
7. `ValidatedFormTests` covers: 201 valid; binding errors (validator count 0); validation errors; 415 ×4, 413 and 400 as for `readForm()`; read-once 500 ×3; and `bindQuery` on GET (`Query has N problems.`, validation errors, and the body still readable afterwards).
8. `FormBinderTests` "paths": 13 names × (member, index) compared with `Errors.at`/`atIndex`. The names include an identifier, a space, `"` and `\`, control characters, non-ASCII, a 56-character name, an empty name, `1st`, `a.b` and `items[0].qty`.
9. A password marker through a `Param` that fails (and whose exception message holds the value), a flag, and an int field. The JSON marker is rejected by a validator. Each check covers the body, the headers and the `spola.web` log records.
10. 500 without a mapper; with the README mapper the response equals `WebException.invalid(e).problem(...)` as JSON (1 and 2 violations). Unit tests show that the Invalid and the exception give the same problem, singular and plural.
11. 200 virtual-thread clients over `HttpClient`, mixing form and JSON. Each case is one of: valid, binding error, validation error, JSON valid/invalid. Static final binder, codec and validator. Each client's exact body is checked (75 valid).
12. `ValidationReadmeTests`: README blocks equal to `OrderApi.java` and `SignupApp.java`, Spring Boot before Spola, all 12 new rows, and both examples run through a server.
13. Guardrails are green. Only `.java` and `.md` files were added or changed. The only annotation used is `@FunctionalInterface`.
14. See ARCHITECTURE "Validering del 2" and PROGRESS 09b.

## Addressed
Round 1: no findings yet.

## Rebuttals
None.

## Known gaps
- **Spec conflict: criterion 3 vs criteria 1 and 2.** The existing `SourceRulesTests` asserts the exact `requires` list (`transitive spola.http`, `transitive spola.json`, `static java.net.http`). It also asserts that the module descriptor's `spola.*` requires are exactly `{spola.http, spola.json}`. The mission requires adding `requires transitive spola.validation`, so the file cannot stay unchanged while the build is green. I made the minimum change: I added the new module to both expected values and to the test name. This keeps the "no other new requires" check of criterion 2 enforced. Diff: 8 lines in `SourceRulesTests.java`. It is documented in ARCHITECTURE and PROGRESS.
- **Interpretations, documented in Javadoc and tested:**
  - `flag` with an empty value (`terms=`) gives `false`. This applies the empty-value rule and is not a `type` error.
  - `all` leaves out empty elements for non-STRING params. Later errors keep their position in the form as the index.
  - In `FormFields`, any `RuntimeException` from a `Param` parser counts as a conversion failure (`type`), as `Param.of` documents, including a `WebException`.
  - A parser that returns `null` gives `IllegalStateException`. This follows the binder exception rule.
- A `Violation` code longer than 100 characters is valid in `spola.validation` (it has no length limit) but gives an IAE (500) in `WebException.invalid`. It is documented with `@throws`.
- The flake I saw in `spola.http` on the first baseline run belongs to that module's existing tests, not to this change.
