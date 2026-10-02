```
VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: framework/modules/json/src/spola/json/Parser.java:211-273 (string())
  problem: string() mixes escape decoding, surrogate-pair state (high, highEscaped, highAt) and the length limit in one 60-line loop, so a change to one of them is risky.
  evidence: Three local variables carry the surrogate state across iterations, and the pairing rule `escaped != highEscaped` has no explanatory comment.
  fix_hint: Extract a small SurrogateTracker, or at least a comment explaining why an escaped half cannot pair with a raw half.

- id: MNT-2
  severity: MINOR
  where: framework/modules/json/src/spola/json/JsonObject.java:331-343 (and the matching JsonArray code)
  problem: The path-carrying copy mechanism (at, withPath, the path field and the hash copy) is duplicated between JsonObject and JsonArray, and path is stored as a field on a value type.
  evidence: `JsonObject at(String newPath) { return newPath.equals(path) ? this : new JsonObject(members, hash, newPath); }`. The public constructor-like overloads must stay in sync with JsonArray's.
  fix_hint: Keep as is if it is documented, but note in ARCHITECTURE.md that any new container field must also be carried through at().

- id: MNT-3
  severity: MINOR
  where: framework/modules/json/src/spola/json/JsonNumber.java:203-206
  problem: equals() recomputes canonical() for both operands on every call, although the hash is cached in the constructor. The cost is hidden, and the work is repeated in each lookup that collides.
  evidence: `canonical(text).equals(canonical(number.text))`
  fix_hint: Cache the canonical form lazily, or document that equals is O(n) with allocation.

- id: MNT-4
  severity: MINOR
  where: framework/modules/json/src/spola/json/JsonObject.java:104-165
  problem: The five of(...) overloads with near-identical Javadoc are boilerplate that is hard to change.
  evidence: Overloads for 2 to 5 members repeat the same @param blocks.
  fix_hint: Keep only of() and of(name, value) plus the builder, or accept the repetition as an API choice.
```
