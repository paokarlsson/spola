# Round 1 – build

## Changes
- `framework/Build.java`: `spola.json` with test entry `spola.json.JsonTests::suite` added to `MODULES`.
- New module `framework/modules/json/` (no `requires`, exports `spola.json`):
  - `JsonValue` (sealed: `JsonObject`, `JsonArray`, `JsonString` record, `JsonNumber`, `JsonBoolean` record, `JsonNull` enum).
  - `JsonObject`/`JsonArray`: immutable, insertion order kept, `of`/`builder`/`copyOf`, readers with path
    (`string`, `number`, `bool`, `object`, `array`, `get`, `optional`) throwing `JsonAccessException`
    (`$.items[2].sku: expected string but found number`). Path is a non-value field on views returned by readers.
  - `JsonNumber`: keeps text; numeric equality via a canonical form (sign, significant digits, BigInteger exponent),
    linear in text length; `intValueExact`/`longValueExact`/`bigIntegerValueExact` throw `ArithmeticException`
    (cap `MAX_EXACT_DIGITS` = 10 000), `bigDecimalValue`, `doubleValue`; `of(long|BigInteger|BigDecimal|double)`, NaN/Inf rejected.
  - `JsonLimits` record (maxDepth 512, maxStringLength 1 Mi, maxNumberLength 1000, maxEntries 100 000, maxInputSize 4 MiB) with `withX`.
  - `JsonParseException` (line, column in code points, offset in bytes or chars, `isByteOffset`, `description`), messages printable ASCII only.
  - `Json`: `parse(String|byte[]|InputStream|Reader[, JsonLimits])` (stream/reader variants declare `IOException`),
    `write`, `writePretty`, `write(v, Appendable)`, `writePretty(v, Appendable)`, `writeUtf8`, `writePrettyUtf8`.
  - Internal: `Parser` (iterative, explicit frame list), `Input` (text input and own strict RFC 3629 UTF-8 decoder;
    streams/readers never read more than one unit past `maxInputSize`), `TextWriter` (iterative), `Equality`
    (iterative deep equality; container hashes cached at construction so nothing recurses), `Paths`, `Text`.
  - Tests (`test/spola/json/`, 287 tests): `RfcExampleTests`, `ConformanceTests` (57 accept / 97 reject, each with
    its RFC rule, all four entry points, round trip), `ErrorMessageTests`, `LimitsTests`, `NumberTests`,
    `ObjectTests`, `WriterTests`, `AccessTests`, `ConcurrencyTests`, `Utf8Tests` (differential vs JDK decoder), helper `Inputs`.
  - `README.md`: parse / switch / build / write next to Jackson, numbers, limits, errors, deviation table.
- `framework/ARCHITECTURE.md`: new section "JSON del 1" (all decisions: 06a/06b split, duplicates, number storage
  and equality, BOM, writer escaping incl. motivation, default limits with measured memory, errors, paths); directory list.
- `framework/PROGRESS.md`: 06a under Klart, 06b remaining, known JSON limitations.

## Verification
`java Build.java` in `framework/` (last run):
```
guardrails: source       187 .java files
guardrails: source       9 other files: 0 not allowed, 9 documents (0 parse as Java)
guardrails: no violations
spola.guardrails: OK - 17 tests ...
spola.test: OK - 77 tests ...
spola.di: OK - 48 tests ...
spola.config: OK - 68 tests ...
spola.util: OK - 80 tests ...
spola.codegen: OK - 41 tests ...
spola.json: OK - 287 tests, 287 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 2754 ms
spola.demo: OK - 43 tests ...
BUILD OK in 36225 ms   (exit 0)
```
Other checks:
- `grep -rn "java.lang.reflect\|java.lang.invoke" modules/json/src` → nothing; no annotations besides `@Override`, `@Serial`, `@FunctionalInterface`.
- `find framework -name '*.json'` (outside `out/`) → nothing; `Allowlist` untouched.
- Memory probe (4 MiB documents, `-Xmx2g`): worst measured tree ≈ 25x input (`[10,10,...]`, ≈ 106 MB); numbers in ARCHITECTURE.md.
- Smoke probes: `[1,]`, dup keys, `01`, `"\ud800"`, `C0 AF`, `ED A0 80`, `F4 90 80 80`, truncated UTF-8, 100 000 `[` all give
  `JsonParseException` with position; `1e1000000000` → `bigDecimalValue` scale -1e9, `bigIntegerValueExact` throws immediately.

## Criteria map
1 Build.java/MODULES; 2 module-info, grep; 3 `RfcExampleTests`; 4 `ConformanceTests` (tables + size test);
5 `ErrorMessageTests`; 6 `LimitsTests`; 7 `NumberTests`; 8 `ObjectTests`; 9 `WriterTests`; 10 `ConformanceTests`
"round trip"; 11 `AccessTests`; 12 `ConcurrencyTests`; 13 `modules/json/README.md`; 14/15 guardrails green;
16 ARCHITECTURE/PROGRESS.

## Addressed
Round 1, no findings.

## Rebuttals
None.

## Known gaps
- `writePretty` output grows with the square of the nesting depth (indentation); documented, tested only to depth 1 000.
- C1 controls (U+0080–U+009F) are not escaped by the writer (spec lists the escaped set exactly); documented.
- Column counts code points, not grapheme clusters.
- `optional(name)` treats JSON `null` as absent (documented decision); `contains`/`members()` distinguish.
- The "comment referencing the rule" in criterion 4 is a `rule` field on every case (shown in the test name), plus section comments in the table.
