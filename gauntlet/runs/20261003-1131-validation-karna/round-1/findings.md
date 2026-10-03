# Round 1 — verdict: PASS

## Upheld findings (the builder must address these)
None at BLOCKER or MAJOR.

## Rejected
- BRK-1 (as MAJOR) — I reproduced the behaviour. `oneOf(Collection<? extends T>)` lets T be inferred as `Object`, so a String collection on an int field compiles. But that exact signature is fixed in mission.md (constraint table: `oneOf(Collection<? extends T>)`), and spec.md says the API signatures are part of the contract and a deviation counts as an unmet criterion. The suggested fix (`Collection<T>`) would break the contract, so this is not a defect in the build. What is left is an overstated doc claim: the class Javadoc of `Constraint` (Constraint.java:16-18) says the type parameter makes a wrong-type constraint a compile error, and that does not hold for `oneOf`. That part is upheld below as MINOR (point 6: narrowing an overstated doc claim is an acceptable fix).

## Duplicates
None.

## MINOR (optional for the builder)
- BRK-1 [MINOR, downgraded from MAJOR] Constraint.java:16-18 and Constraints.oneOf Javadoc — the "wrong type is a compile error" claim does not hold for `oneOf`, because `? extends T` (mandated by mission) infers `Object` for a mismatched collection, and you only find out at runtime when every value is rejected. — fix_hint: narrow the claim in the `Constraint` class doc, and note in the `oneOf` Javadoc (and maybe the README deviation table) that the element type is not checked against the field type. Optionally add a type witness example such as `Constraints.<Integer>oneOf(...)`. Do not change the signature.
- BRK-2 [MINOR] ValidationException.java:38-40 / Violation.java — `ValidationException` cannot be serialized (`NotSerializableException: spola.validation.Violation`). The field comment ("serializable, as List.copyOf lists are") is wrong, and `@SuppressWarnings("serial")` hides the lint warning. Exception serialization is rare in practice, so MINOR. — fix_hint: make `Violation implements java.io.Serializable` (all components are String), or make the field transient and correct the comment.
- AUD-1 [MINOR] PathTests / spec C5 — the spec is ambiguous between `\u001B` and "identical to the codec". The builder's codec-identical `\u001b` is the correct reading, since the exact-equality requirement is the binding one. No change needed.
- MNT-1 [MINOR] Validator.java — the varargs copy and null-check block is repeated four times. — fix_hint: one helper.
- MNT-2 [MINOR] Constraints.java:518 — the private `notNull(String, Object)` parameter check shares its name with the public `notNull()` constraint. — fix_hint: rename it to `requireParam` or similar.
- MNT-3 [MINOR] Path.java / Text.java — this is a deliberate copy of the codec's path rendering, and the copies can drift apart. — fix_hint: say in ARCHITECTURE.md which files have to change together.
- MNT-4 [MINOR] Validator/Errors Javadoc — the null semantics are spread over several overlapping paragraphs. — fix_hint: one consolidated statement or table, with links to it.
