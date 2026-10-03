# Round 1 – build

## Changes
- `framework/Build.java`: `spola.validation` added to `MODULES` with test entry `spola.validation.ValidationTests`.
- New module `framework/modules/validation/`:
  - `src/module-info.java`: Javadoc on what it replaces; only `requires static java.compiler` (tests only); `exports spola.validation`.
  - `src/spola/validation/Validator.java`: functional interface `validate(T, Errors)`, `check`, `require`, `of(Constraint...)`, `lazy(Supplier<Validator<T>>)`, `builder()`; nested `final class Builder<T>` with `field`, `optional`, `nested`, `each`, `eachNested`, `rule(test, code, message)`, `rule(name, test, code, message)`, `include`, `when`, `build`. Varargs methods are `@SafeVarargs public final` and copy the array in a loop (`-Xlint:varargs`). Arguments are checked when the step is added (NPE for null, IAE for an empty name).
  - `Errors.java`: `at`, `atIndex`, `atKey`, `reject`, `path`. Shared private report. The limit of 100 is checked in `reject` before anything is created, so paths are only rendered for kept violations. `count` saturates. Using it after `check` returns throws `IllegalStateException`.
  - `Violation.java` (record path/code/message, no value), `Validated.java` (sealed, `Valid`, `Invalid` with consistency checks, `MAX_VIOLATIONS = 100`), `ValidationException.java` (count + first 3 `path: message`, ASCII-escaped, each entry ≤ 600 chars, total ≤ 2 000).
  - `Constraint.java`: final immutable class, `of`, `withMessage`, `withCode`, `code`, `message`, `accepts`. The predicate is never called with `null`.
  - `Constraints.java`: 27 built-ins. All codes and messages are in the Javadoc table. Exact number comparison. Parameters are checked at creation (IAE).
  - Internal: `Path.java` (same algorithm as `spola.json.codec.Path`), `Text.java` (escaping and cutting), `Email.java` (loop-based definition), `LazyValidator.java`.
  - `README.md`: CreateOrder example next to Spring Boot (Jakarta annotations, `@Valid`, `@AssertTrue`), the builder steps, the Jakarta→Spola table and the deviation table.
  - Tests (`test/spola/validation/`): `ValidationTests` (entry), `OrderValidation` (the README source), `ConstraintTableTests`, `EmailTests`, `PathTests`, `OrderExampleTests` (+ README checks), `LimitTests`, `CustomValidatorTests`, `CompileTests`, `UserCodeTests`, `ConcurrencyTests`, `SafetyTests`, `SourceRulesTests`.
- `framework/PROGRESS.md`: 09a entry, 09 split into 09a/09b, 09b listed as remaining, known limitations.
- `framework/ARCHITECTURE.md`: directory listing line plus the new section "Validering del 1", covering every point in criterion 15.

## Verification
`java Build.java` in `framework/`:
```
modules in build order: [..., spola.web, spola.validation, spola.demo]
guardrails: no violations
...
tests of spola.validation:
spola.validation: OK - 251 tests, 251 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 1191 ms
...
BUILD OK in 92425 ms   (exit 0)
```
All other modules are unchanged and green: guardrails 17, test 77, di 48, config 68, util 80, codegen 41, json 417, http 283, web 591, demo 43.

How the tests cover the criteria:
- C3: 147 table cases. A meta-test asserts that each of the 27 constraints has a valid case, an invalid case and a null case, with null valid except for the presence constraints. Each invalid case asserts `Invalid([Violation("$", code, message)], 1)` exactly.
- C4: 16 valid and 27 invalid addresses, including every required one.
- C9: javac reports `inference variable V has incompatible bounds` on line 14 for notBlank-on-int and min-on-String. I checked this by hand against `out/classes/spola.validation`. The positive cases compile with `-Xlint:all -Werror`.

## Addressed
Round 1: no findings yet.

## Rebuttals
None.

## Known gaps / judgement calls
- **C5 hex case:** the criterion text says "escapat som `\u001B`", but it also requires "exakt densamma som `spola.json.codec`". The codec writes lower-case hex (`Messages.appendEscaped` uses `Integer.toHexString`, and codec test `ProblemTests` expects `\u000a`). I followed the codec, so ESC is `\u001b`. `PathTests` documents this, and each path names the codec test that pins it.
- **Overloads:** `notEmpty` for collection and map is `notEmptyCollection()` / `notEmptyMap()`, which the mission allows ("metoder med olika namn"). The same pattern gives `size` for `Collection` and `mapSize` for `Map`, since a no-type-argument overload is impossible.
- **Messages I chose** (mission says "dokumenterade"):
  - `decimalMin`: `must be at least X` / `must be greater than X`
  - `decimalMax`: `must be at most X` / `must be less than X`
  - `positive` / `negative` and their `OrZero` variants: `must be greater than 0`, `must be greater than or equal to 0`, `must be less than 0`, `must be less than or equal to 0`
  - `digits`: `must have at most I integer digits and F fraction digits`
  - `oneOf`: `must be one of the allowed values`
  - `pastOrPresent` / `futureOrPresent`: `must be in the past or in the present` / `must be in the future or in the present`
- **Additions beyond the letter of the mission** (defensive and documented):
  - `Errors.reject` escapes control, separator, bidi and lone-surrogate characters in messages and cuts them at 500 characters. Letters in any script are kept.
  - Codes must be printable ASCII without spaces.
  - `Errors` after use throws `IllegalStateException`.
  - `oneOf` refuses an empty collection and null elements.
- **Null handling:**
  - A built validator treats a `null` root value as valid and runs no steps.
  - `eachNested` skips `null` elements, like `@Valid`. Use `each(..., notNull())` to report them.
- **`Validator.lazy`:** under a first-use race the supplier may be called by more than one thread. This is harmless for a `static final` field.
- **Recursion:** it runs on the Java stack and has no cycle detection, as the spec says.
