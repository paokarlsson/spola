# Round 1 build: JSON part 2, `spola.json.codec`

## Changes

New package `spola.json.codec` (main code, `framework/modules/json/src/spola/json/codec/`):
- `JsonCodec<T>`: public abstract class with a package-private constructor. Instance methods: `encode`, `decode`, `read(String)`, `read(byte[])`, `write`, `writePretty`, `map(to, from)`. Static: `object` (0–16 fields plus the general form `object(List<Field>, FieldValues -> R)`), `field`, `optionalField`, `sealed`.
- `JsonCodecs`: `STRING, BOOLEAN, INT, LONG, DOUBLE, BIG_DECIMAL, BIG_INTEGER, UUID, INSTANT, LOCAL_DATE, LOCAL_TIME, LOCAL_DATE_TIME, OFFSET_DATE_TIME, DURATION, JSON`, plus `list, set, map, optional, nullable, lazy, enumOf(values), enumOf(values, name)`.
- `ObjectCodec<R>` (`ignoringUnknown()`), `Field<R,T>` (`orElse`), `FieldValues<R>`, `Constructors.Of1..Of16` (generated shapes), `Variant<T,S>` with `Variant.Tagged<T>`, `JsonMappingException`, `JsonProblem`.
- Internal: `ScalarCodec, MappedCodec, ListCodec, SetCodec, MapCodec, OptionalCodec, NullableCodec, LazyCodec, EnumCodec, SealedCodec, Decoding, Encoding, Path, Messages`.

Other changes:
- `modules/json/src/module-info.java`: `exports spola.json.codec;` and Javadoc. Still no `requires`.
- `modules/json/test/spola/json/JsonTests.java`: adds `spola.json.codec.CodecTests.tests()` to the module's suite. The name is fully qualified, with no import.
- New tests in `modules/json/test/spola/json/codec/`: `CodecTests` (aggregator), `Model`, `Problems`, `RoundTripTests`, `StrictnessTests`, `ProblemTests`, `ObjectCodecTests`, `SealedTests`, `MappingTests`, `DepthTests`, `CodecConcurrencyTests`, `SourceRulesTests`. That is 128 tests.
- `modules/json/README.md`: a mapping section with Jackson side by side (`readValue`, `@JsonProperty`, `@JsonIgnoreProperties`, `@JsonInclude`, `@JsonTypeInfo`/`@JsonSubTypes`, `JavaTimeModule`), a record example (nested record, list, Optional, enum, Instant, Email value type), a sealed hierarchy, recursion, the real error report (captured by running the example), and a table of deviations.
- `ARCHITECTURE.md`: new section "JSON del 2" covering all the decisions criterion 18 requires. `PROGRESS.md`: section 06b, the remaining list, and known problems.

Key design choices (also in ARCHITECTURE.md):
- **Failure detection.** A codec reports the problem and returns `null`. The caller sees the failure because the problem count went up, so `null` stays a legal value for `nullable`. There are no exceptions in the decode path.
- **Problem cap.** 100 problems are kept, the rest are counted, and one summary problem on `$` is added. `getMessage()` is a single line, at most 2,000 characters.
- **Message length.** A value is quoted with at most 40 characters. An exception message from app code keeps at most 200 characters, which is a documented choice. A member name inside a path is cut at 40 characters, so that long keys cannot blow up memory across 100 paths.
- **Missing members** are reported after the object's other members, in field order.
- **Encoding is fail-fast**: one problem, thrown as `JsonMappingException` with its path.
- **Depth.** Objects and arrays are counted in both directions and capped at `JsonLimits.DEFAULT.maxDepth()` (512). A guard on `lazy` chains (64 lazy codecs in a row with no container) turns a self-resolving `lazy` into `IllegalStateException` instead of a stack overflow.
- **Number bounds.** `BIG_DECIMAL` requires the scale to be within ±10,000 and `BIG_INTEGER` allows at most 10,000 digits, both ways. `DOUBLE` rejects overflow, non-zero underflow, and NaN/Inf on encode.
- **UUID** must be in the strict 8-4-4-4-12 form.
- **Sealed hierarchies.** `Variant.tag(S)` captures the encoder in a lambda, so no cast is needed. A variant that is not in the list, a `null` selector result, or a selector that throws all give `JsonMappingException` on encode.

## Verification

`java Build.java` in `framework/`:
```
guardrails: source       222 .java files
guardrails: source       9 other files: 0 not allowed, 9 documents (0 parse as Java)
guardrails: no violations
...
spola.json: OK - 415 tests, 415 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 3064 ms
...
BUILD OK in 39575 ms        (exit 0)
```
The codec part of the suite (`spola.json.codec`) has 128 tests in these groups: round trip, strict scalars (41 rejected and 18 accepted, parameterized), problems, object codecs, sealed hierarchies, mapping and null, depth, concurrency, source rules.

Criterion 3 grep, which reviewers can run from `framework/`:
```
$ grep -rnE 'java\.lang\.reflect|java\.lang\.invoke|isInstance|\.cast\(|getRecordComponents|getEnumConstants|getPermittedSubclasses' modules/json/src/spola/json/codec
(no output, exit 1)
$ grep -rn "spola.json.codec" modules/json/src/spola/json/*.java
(no output, exit 1)
```
`SourceRulesTests` also checks both of these, plus that module-info has `exports spola.json.codec;` and no `requires`.

Criterion 11 compile error. This was done outside `framework/`, in `/tmp/sealeddemo/Demo.java`, against the built module. It adds `record Triangle(double side) implements Shape` to `permits` and leaves the switch with only the Circle and Square cases:
```
$ javac --module-path /tmp/jt --add-modules spola.json -d /tmp/sealeddemo/out Demo.java
Demo.java:20: error: the switch expression does not cover all possible input values
            shape -> switch (shape) {
                     ^
1 error
exit 1
```
The comment that explains this is in `SealedTests` (class Javadoc), in `Model.SHAPE`, and in README ("Sealed hierarkier").

The README example was run as a program against the built module. Its output, pasted into the README, is real:
```
$.email: invalid value: not an email address
$.address.city: missing member
$.tags[1]: expected string but found number
$.status: unknown value "active"; expected one of "ACTIVE", "CLOSED"
$.created_at: expected an ISO-8601 instant such as 2026-10-02T09:18:00Z but found "2026-10-02 09:18"
$.admin: unknown member; expected one of "name", "email", "address", "tags", "note", "status", "created_at"
```

Stack margin: the 100,000-level tests pass on a default platform thread, on a virtual thread, and on a platform thread with a 512 KiB stack (the default `ThreadStackSize` is 1024 KiB). With 256 KiB the encode overflowed, so that case is not claimed; see the known gaps.

## Criteria map
1. The build is green, exit 0.
2. The package is exported in module-info and there are no `requires`. `SourceRulesTests` checks this, and the greps above show it.
3. Covered by the grep above and by `SourceRulesTests`.
4. `RoundTripTests`: `Model.EVERYTHING` uses every built-in codec. It also uses `list`, `set`, `map`, `optional`, `nullable`, `lazy`, `c.map`, both forms of `enumOf`, `optionalField`, `orElse`, `sealed`, the general `object` form (`TIMES`) and `ignoringUnknown`. The recursion goes through `lazy` (Node, Group). Each value is checked with decode(encode), read(write), read(writePretty) and read(bytes).
5. `StrictnessTests`: 41 rejected cases at both `$.value` and `$`, including every case the spec names, plus 18 accepted cases (among them `1.0` and `1e2` as INT). `1e1000000000` is timed and must finish in under 1 s.
6. `ProblemTests` "a document with problems on several levels": 6 exact problems in document order, and the exact `getMessage()`.
7. `ProblemTests`: 10,000 bad elements give 101 problems, and the message is at most 2,000 characters.
8. `ProblemTests`: an unknown member whose name has `\n \r \u0000  `, and an exception message of 10,000 characters with CR/LF. `Problems.exception` checks every problem path, message and `getMessage()` for printable ASCII.
9. `ObjectCodecTests`, the first 8 tests.
10. `ObjectCodecTests`: 16 fields (`Sixteen`) and 22 fields in the general form (`Big`), each with a round trip and problem collection.
11. `SealedTests` and the javac demonstration above.
12. `MappingTests`: the first two tests (the `Email::new` problem path, and encoding through `Email::value`, counted).
13. `MappingTests`: "null in a required field…" (`$.address.city`) and "with nullable, null is written".
14. `DepthTests`.
15. `CodecConcurrencyTests`: 1,000 virtual threads read, write and decode a bad document, and also race to resolve a fresh `lazy`.
16. `modules/json/README.md`, the mapping section and the deviation table.
17. Guardrails reported no violations. The only annotations are `@Override`, `@SuppressWarnings`, `@Serial`, `@FunctionalInterface` and `@SafeVarargs`. The only new file types are `.java`.
18. ARCHITECTURE.md "JSON del 2" and PROGRESS.md 06b.

## Addressed
Round 1, so there are no findings yet.

## Rebuttals
None.

## Known gaps
- **The aggregator touches `spola.json.codec`.** The module's single test entry, `spola.json.JsonTests` (a test class in package `spola.json`), names `spola.json.codec.CodecTests` fully qualified, because Build.java allows only one test entry per module. No main-code class in `spola.json` refers to the codec package, which is what the mission's reason demands ("Trädmodellen i spola.json får inte bero på spola.json.codec"). If reviewers read criterion 2 as covering test code too, the fix would be to change the entry in Build.java.
- **Exception messages keep up to 200 characters, not 40.** The "40 characters of a value" rule is applied to document values, to member names in paths, and to names in lists. Exception messages are developer-written explanations; they are escaped and cut at 200. This is documented in ARCHITECTURE.md.
- **Small stacks can still overflow.** Recursion is bounded at 512 containers, but each level costs a few frames per codec in the chain. A thread with a very small stack (256 KiB in my test) can still hit `StackOverflowError` near 512 levels. Default and 512 KiB stacks are fine. This is documented in PROGRESS.md.
- **The depth limit is fixed** at 512 and cannot be changed per call.
- **An `Error` from app code is not caught.** If a constructor, `map` function or getter throws an `Error` (as opposed to a `RuntimeException`), it propagates. A misuse of `FieldValues.get` with a foreign field is deliberately rethrown as `IllegalArgumentException` and is not turned into a problem.
