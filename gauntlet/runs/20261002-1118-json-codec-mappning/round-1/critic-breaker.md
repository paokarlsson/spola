```
VERDICT: FAIL
FINDINGS:
- id: BRK-1
  severity: MAJOR
  where: framework/modules/json/src/spola/json/codec/Path.java:54 and :61 (isIdentifier rejects names longer than 40, then uses Messages.excerpt)
  problem: Problem paths cut every member name longer than 40 characters, including ordinary declared field names. This breaks the binding decision in mission.md that paths use "samma format och samma escaping" as spola.json (Paths/JsonAccessException), and two different fields can end up with the same path.
  evidence: |
    Scratch program /workspace/gauntlet/runs/20261002-1118-json-codec-mappning/round-1/scratch-breaker/p/Coll.java defines
    record Order(String shippingAddressAdditionalDeliveryInstructionLine1, String shippingAddressAdditionalDeliveryInstructionLine2)
    and gives each field its own name. C.read("{\"shippingAddressAdditionalDeliveryInstructionLine1\": 1}") prints:
      $["shippingAddressAdditionalDeliveryInstruc"... (49 characters)]: expected string but found number
      $["shippingAddressAdditionalDeliveryInstruc"... (49 characters)]: missing member
    The two problems belong to different fields but have the same path, and the path is not valid path syntax.
    For the same 45-character name, spola.json's reader gives "$.customerPreferredCommunicationChannelOverride: ..."
    while the codec gives "$[\"customerPreferredCommunicationChannelOve\"... (45 characters)]" (Probe.java in the same dir).
    Uppdrag 08/09 are supposed to pass these paths to clients, who could not map them back to a field.
  fix_hint: Write declared field names (and short or identifier names) in full, exactly as spola.json Paths does. Only shorten names that come from the document (unknown members, map keys), or cap the whole path string instead of each segment.

- id: BRK-2
  severity: MINOR
  where: framework/modules/json/src/spola/json/codec/Messages.java:24,138
  problem: Exception messages from app code are quoted up to 200 characters. A common pattern like `throw new IllegalArgumentException("not an email: " + value)` therefore copies about 185 characters of client-controlled input into the problem, but mission.md says messages "citerar högst 40 tecken av ett värde".
  evidence: Msg.java (scratch dir). EMAIL = STRING.map(Email::new, ...) with a 317-character hostile value gives "$.email: invalid value: not an email: x \u0000\u000d\u000aINFO forged‮yyyy...(about 180 y)... (331 characters)". It is escaped and has no raw control characters, but it is far more than 40 characters of the value. The builder documents this as a deliberate choice; the judge should rule on it against the binding decision.
  fix_hint: Cut exception messages at MAX_EXCERPT, or narrow the rule in mission.md/ARCHITECTURE.md explicitly.

- id: BRK-3
  severity: MINOR
  where: framework/modules/json/src/spola/json/codec/JsonCodecs.java:232 (JSON identity codec); JsonCodec.java:61-66 Javadoc "Depth"
  problem: The JSON codec never counts depth. A tree built in code that is deeper than 512 levels decodes and encodes without error, which contradicts the Javadoc ("a deep tree built in code ... gives JsonMappingException"). write() then produces text that read() rejects.
  evidence: Deep2.java: JSON.decode(100_000-deep JsonArray) -&gt; "decode ok"; JSON.write(same) -&gt; 200004 characters, no error. RT.java: JSON.read(JSON.write(600-deep array)) -&gt; JsonParseException "nesting deeper than maxDepth (512)".
  fix_hint: Check depth in JSON's encode/decode (iteratively), or narrow the Javadoc to exclude JSON.

- id: BRK-4
  severity: MINOR
  where: framework/modules/json/src/spola/json/codec/JsonCodecs.java:181 (BIG_INTEGER encode), BIG_DECIMAL encode (scale check only)
  problem: Encoding accepts numbers up to 10,000 digits (BIG_DECIMAL has no precision bound at all), but read() parses with JsonLimits.DEFAULT, where maxNumberLength is 1,000. So read(write(v)) fails for valid values.
  evidence: RT.java: BIG_INTEGER.read(BIG_INTEGER.write(BigInteger.TEN.pow(1000))) -&gt; JsonParseException "number longer than maxNumberLength (1000)". The same happens for new BigDecimal(BigInteger.TEN.pow(1000)).
  fix_hint: Make the encode bounds match JsonLimits.DEFAULT.maxNumberLength(), or document the asymmetry.
```

I tried the following without finding defects:
- Number strictness and exponent DoS on all number codecs: `1e±1000000000`, `1e±9999999999`, `0e±1000000000`, `-0`, `1E2`, `100e-2`. Every case gave the right result or a `JsonMappingException` quickly.
- Round trips of extreme `java.time` values (MIN/MAX, negative durations, offsets with seconds).
- Sealed, lazy, map, nullable and optional recursion up to 255 levels (the 512-container limit) on a platform thread and a virtual thread. No `StackOverflowError`.
- Hostile member names, enum values and exception messages containing U+2028, U+0000, CR/LF and U+202E. Messages stayed safe: `getMessage()` was 1,976 characters with 101 problems.
- A full `java Build.java` in a copy of `framework/`: green, `spola.json` 415/415 tests.

The scratch programs are in `/workspace/gauntlet/runs/20261002-1118-json-codec-mappning/round-1/scratch-breaker/p/`.
