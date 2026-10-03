```
VERDICT: FAIL
FINDINGS:
- id: BRK-1
  severity: MAJOR
  where: framework/modules/validation/src/spola/validation/Constraints.java (oneOf, signature `<T> Constraint<T> oneOf(Collection<? extends T> allowed)`); documented guarantee in Constraint.java class Javadoc ("the type parameter makes a constraint for the wrong type a compile error")
  problem: Calling `oneOf` with values of the wrong element type still compiles, so a type error that the docs and the mission say is caught at compile time instead becomes a constraint that rejects every value at runtime.
  evidence: |
    scratch-breaker/src/t/T1.java:
      record P(int age, ...) {}
      Validator.<P>builder().field("age", P::age, oneOf(List.of("1","2"))).build()
    javac: compiles with no error. Running it with age=1 prints:
      Invalid[violations=[Violation[path=$.age, code=oneOf, message=must be one of the allowed values]], count=1]
    It also compiles with a pre-typed `static final Set<String> CODES` on an Integer field, because T is inferred as Object. Realistic case: `oneOf(Set.of("RED","GREEN"))` on an enum field, or currency codes as Strings on a Currency field.
    I checked the fix in scratch-breaker/src/t/T4.java, a minimal copy of the same inference shape. With `b(Collection<T>)` the call `field(String::length, b(CODES))` with Set<String> CODES gives "method field ... cannot be applied". `b(NUMS)` and `b(List.of(1,2))` still compile. With the current `? extends T` form, `a(CODES)` compiles.
  fix_hint: Change the signature to `oneOf(Collection<T>)` so a pre-typed collection of the wrong type is a compile error, add a CompileTests case for it, and narrow the Javadoc claim (an inline `List.of("1")` can still be inferred as Object).
- id: BRK-2
  severity: MINOR
  where: framework/modules/validation/src/spola/validation/ValidationException.java (field `violations`, `@SuppressWarnings("serial")`, comment "serializable, as List.copyOf lists are"); Violation.java (record does not implement Serializable)
  problem: ValidationException is a Throwable and so Serializable, but serializing it fails because Violation is not Serializable, and `@SuppressWarnings("serial")` hides the lint warning that would have flagged this.
  evidence: |
    scratch-breaker/src/t/T5.java: catch the ValidationException from `Validator.of(notBlank()).require(" ")` and pass it to `ObjectOutputStream.writeObject`.
    Output: java.io.NotSerializableException: spola.validation.Violation
  fix_hint: Make `Violation` implement `java.io.Serializable` (its components are all Strings), or mark the field transient and document that.
```

The build is green: I ran `java Build.java` in a copy (`/workspace/gauntlet/runs/20261003-1131-validation-karna/round-1/scratch-breaker/fw`) and got EXIT 0, with spola.validation at 251/251 tests passing.

Things I tried that held up:
- **Paths:** the path rendering matches `spola.json.codec.Path` and `Messages` character for character. The lower-case `\u001b` follows the codec, and `build.md` names this as a judgement call.
- **Wrong-type constraints:** these are compile errors, as expected, for `size` on a String, `notEmpty` on a List, `past(LocalDate)` on a `LocalDateTime`, and `min` on a String.
- **Null and empty values:** null elements in `each` with `notNull` behave correctly, and so do `optional` and `nested` skipping, and validator ordering.
- **The 100 limit:** I validated 10,000 elements and got count 10000 with 100 violations kept. The message stays short and ASCII-only.
- **Escaping:** messages with U+2028, U+202E and `\n` are escaped. Using an `Errors` after `check` returns throws `IllegalStateException`.
- **Email boundaries:** a 253-character domain is accepted, 254 is rejected, and so are a trailing dot and a trailing hyphen.
- **Numbers:** `digits` and `decimal` give correct results for `AtomicInteger`, `Float` and `1E+5`.

The experiment files are in `/workspace/gauntlet/runs/20261003-1131-validation-karna/round-1/scratch-breaker/src/` (`Exp.java`, `t/T1.java` to `t/T6.java`).
