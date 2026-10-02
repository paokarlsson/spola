```
VERDICT: PASS
FINDINGS:
- id: BRK-1
  severity: MINOR
  where: framework/modules/json/README.md:200
  problem: The deviation table says U+2028 and U+2029 are written escaped, but the "Escapas (...)" cell contains the raw U+2028 and U+2029 characters instead of the text ` ` and ` `, so the documented escape shows up as invisible line or paragraph separators.
  evidence: `grep -n -P '\x{2028}|\x{2029}' modules/json/README.md | cat -A` → `200:| Skrivning av U+2028, U+2029, U+007F | Skrivs rM-CM-%tt | Escapas (`M-bM-^@M-(`, `M-bM-^@M-)`, `\u007f`) |`. The bytes E2 80 A8 and E2 80 A9 are the raw characters. The writer itself is correct: `Json.write(JsonString.of("  "))` gives `"  "`.
  fix_hint: Replace the two raw characters in the cell with the literal text ` ` and ` `.
```

I found no BLOCKER or MAJOR defects. The build is green, with 287/287 tests passing in `spola.json`. Everything below was checked with my own runs against `out/spola.json.jar`.

What I tried:
- **Fuzzing:** 300,000 mutated documents (random bytes, structural characters, truncation, insertion), run through all four entry points (`byte[]`, `InputStream`, `String`, `Reader`).
  - Every rejection was a `JsonParseException`; no other exception or `Error`.
  - All entry points gave the same result for every document.
  - No invalid UTF-8 was accepted, judged against the JDK's strict decoder.
  - No round trip through `write` or `writePretty` failed.
- **Limits and inputs:**
  - Exactly at and one over `maxInputSize` for String, Reader, `byte[]` and stream; a 4-byte UTF-8 sequence that crosses the limit.
  - `maxStringLength` with a surrogate pair and with escapes.
  - Depth 512 accepted and 513 rejected for objects.
  - BOM only, two BOMs, BOM-only bytes; empty input and whitespace only.
  - Line and column with CRLF and with characters outside the BMP, in text and byte input.
- **Messages:** a duplicate key containing ANSI control characters comes out escaped, and paths in `JsonAccessException` are escaped too.
- **Numbers:**
  - `1e-3000000000` gives `ArithmeticException` from `bigDecimalValue` and still compares equal numerically.
  - Long.MIN boundary, zero with a huge exponent, and `1E+2` all convert correctly.
  - Hashing and equality for `JsonNumber.of(BigDecimal)` against parsed numbers is consistent.
  - A 4,000-element array of numbers with ~1,000-digit exponents parses, hashes and converts quickly.
- **Writer and tree:** writing, comparing and hashing trees 200,000 levels deep built in code (with `-Xss512k`) caused no stack overflow. Pretty and compact output for nested empty containers is correct, and escaping is correct.
- **Performance:** about 4 MiB of large arrays parses in 40–120 ms, and colliding keys stop at `maxEntries`.
- **Criteria and rules:** criteria 1, 2, 13, 14 and 16 hold (Build.java diff, no reflect/invoke imports, no stray file types, README and ARCHITECTURE/PROGRESS content). The tests for criteria 4–12 cover what the spec asks for.

Scratch files are in `/workspace/gauntlet/runs/20261002-1043-json-parser-och-skrivare/round-1/scratch-breaker/` (`T1.java`, `Fuzz.java`, `Perf.java`).
