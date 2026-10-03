VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /workspace/framework/modules/validation/src/spola/validation/Validator.java:105-120, 186-206, 218-242, 279-306
  problem: The same copy-and-null-check block (new ArrayList, copy loop, `checked`) is pasted four times, in `of`, `field`, `optional` and `each`.
  evidence: `var copy = new ArrayList<Constraint<? super V>>(constraints.length); for (var constraint : constraints) { copy.add(constraint); } var list = checked(copy);`
  fix_hint: Add one helper that takes the array and does the null check, the copy and `List.copyOf`. A plain array parameter that is not varargs should not trigger `-Xlint:varargs`. Then each method body is a single call.

- id: MNT-2
  severity: MINOR
  where: /workspace/framework/modules/validation/src/spola/validation/Constraints.java:62 and 518
  problem: The public `notNull()` and the private `notNull(String, Object)` share a name but do different things. The private one is a parameter check that throws IAE, so a reader can take it for the constraint.
  evidence: `notNull("decimalMin", min);` next to `public static Constraint<Object> notNull()`
  fix_hint: Rename the helper to `requireParam`.

- id: MNT-3
  severity: MINOR
  where: /workspace/framework/modules/validation/src/spola/validation/Path.java and Text.java
  problem: The path rendering and escaping are a deliberate copy of `spola.json.codec.Path`, so the two can drift apart. The only protection is test comments that name the codec tests.
  evidence: Path.java Javadoc: "The notation and the rendering are those of spola.json.codec's paths". Build report: "same algorithm as spola.json.codec.Path".
  fix_hint: Record in ARCHITECTURE.md that this is a copy kept for module independence, and which two files to change together.

- id: MNT-4
  severity: MINOR
  where: /workspace/framework/modules/validation/src/spola/validation/Validator.java:11-54 and Errors.java:21-27
  problem: The Javadoc is dense. The `null` rules are spread over several overlapping paragraphs and appear in Validator, Builder, Constraints and `eachNested`.
  evidence: The "null:" paragraph in Validator, plus the separate null notes on `nested`, `each` and `eachNested`.
  fix_hint: Put one null-handling table in the README or in the Validator class doc, and have the method docs link to it.
