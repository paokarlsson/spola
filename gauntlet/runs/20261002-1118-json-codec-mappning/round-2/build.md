# Round 2 — build

## Changes
- `framework/modules/json/src/spola/json/codec/Path.java`: the path now has two kinds of member segment.
  - `member(name)` is for names the codec declares (object fields, the sealed discriminator). These are rendered **in full**, exactly as `spola.json` `Paths` renders them: `.name` for identifiers of any length, and `["..."]` for everything else, using the same escaping (lowercase `\uXXXX` outside printable ASCII, `\"`, `\\`).
  - `key(name)` is for names that come from data (unknown members, `map` keys). Only these are still cut after 40 characters.
  - The length cap that `isIdentifier` applied is removed.
- `ObjectCodec.java`: unknown members are now reported with `path.key(name)`. Known fields still use `path.member`.
- `MapCodec.java`: decode and encode use `path.key(...)` for map keys.
- `ProblemTests.java`: two new tests.
  - Three declared fields over 40 characters: two identifiers that differ only in the last character, plus one non-identifier containing a space, `å` and quotes. The test covers wrong type, missing member and encode-null. Each path is asserted equal to the `JsonAccessException.path()` that `JsonObject.string(name)` gives for the same name.
  - A 41-character map key is cut, and a 40-character key is not.
- `JsonCodec.java` (Depth Javadoc): states that `JsonCodecs.JSON` passes a tree through without walking it, so its depth is not counted (BRK-3).
- `JsonCodecs.java` (`BIG_INTEGER`/`BIG_DECIMAL` Javadoc): documents the asymmetry with `read` (`maxNumberLength` 1 000) and the workaround, which is to parse with larger limits and then call `decode` (BRK-4).
- `Constructors.java`: maintenance note saying `Of1..Of16` and the 16 `object` overloads must be changed together (MNT-1).
- `LazyCodec.java`: comment explaining why `enterLazy`/`exitLazy` are not in try/finally (MNT-2).
- `JsonTests.java`: the comment now says why the codec suite is referenced by its full name (MNT-3).
- `ARCHITECTURE.md` ("Säkra meddelanden"), `modules/json/README.md` and `PROGRESS.md`: describe the declared-name and data-name rule, and state explicitly that the 40-character limit does not apply to exception text (BRK-2). New known gaps are listed.

## Verification
`cd framework && java Build.java` → exit 0:
```
spola.guardrails: OK - 17 tests, 17 ok, 0 failed
spola.test: OK - 77 tests, 77 ok, 0 failed
spola.di: OK - 48 tests, 48 ok, 0 failed
spola.config: OK - 68 tests, 68 ok, 0 failed
spola.util: OK - 80 tests, 80 ok, 0 failed
spola.codegen: OK - 41 tests, 41 ok, 0 failed
      ok      a long member name is cut in the path (0 ms)
      ok      declared field names over 40 characters are never cut, and render as in spola.json (2 ms)
      ok      a long map key is cut in the path, as data from the document (0 ms)
spola.json: OK - 417 tests, 417 ok, 0 failed, 0 skipped, 0 suite fixtures failed
spola.demo: OK - 43 tests, 43 ok, 0 failed
BUILD OK in 47213 ms
```

## Addressed
- BRK-1 [MAJOR]: Declared names are now never cut and are rendered the same way as `Paths`. The breaker's case (`...Line1` wrong type, `...Line2` missing) now gives `$.shippingAddressAdditionalDeliveryInstructionsLine1` and `...Line2`, and the new test asserts both against `JsonAccessException`. Names that come from data are still cut. Otherwise up to 100 problems under one 1 MiB key would amplify memory, which the finding's fix_hint explicitly allows.
- BRK-2 [MINOR]: Documented in ARCHITECTURE.md and the README that the 40-character limit applies to quoted document values and not to exception text written by the app (limit 200).
- BRK-3 [MINOR]: Narrowed the Javadoc. `JSON` does not walk the tree, and the tree model and writer handle any depth without recursion (the breaker's own reproduction showed no `StackOverflowError`).
- BRK-4 [MINOR]: Documented the asymmetry in both Javadocs and in PROGRESS.md.
- MNT-1, MNT-2, MNT-3 [MINOR]: Added the comments.

## Rebuttals
- (none)

## Known gaps
- Unknown members and map keys longer than 40 characters that start the same way still get the same path. This is a deliberate trade-off against memory amplification, and it is documented.
- No test checks that the 16 `OfN` overloads are consistent with each other. A comment covers it, but no test or generator does (MNT-1 handled with the cheapest option).
- `BIG_INTEGER`/`BIG_DECIMAL`: the asymmetry with `read` is documented, not removed.
