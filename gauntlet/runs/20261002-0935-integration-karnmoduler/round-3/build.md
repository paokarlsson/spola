# Round 3 — build

## Changes
- `framework/modules/config/src/spola/config/Source.java`: `Source.file`/`optionalFile` skip a UTF-8 byte order mark (U+FEFF) at the very start of the file (`skipByteOrderMark`, mark/reset on the BufferedReader). Only a leading BOM is removed. No API change. Javadoc (class and `file`) updated.
- `framework/modules/config/test/spola/config/SourceTests.java`: 2 tests. One checks that a leading BOM is skipped (file and optionalFile, keys are exactly `first`, `second`). One checks that an empty file, a file without a BOM and a U+FEFF that is not at the start are all unchanged.
- `framework/modules/config/README.md`: new section "### Filer" (UTF-8, leading BOM skipped, invalid UTF-8 is an error).
- `framework/modules/demo/test/spola/demo/ProgramTests.java`: end-to-end test for the exact reproduction (BOM + `rules.max.quantity=5` first, `apple;50`). It now gives "rejected 1 ... exceeds max quantity 5".
- `framework/modules/demo/README.md`: Friktion point 8 (**Åtgärdat**) with a code snippet, the fix, the tests and where it is documented. The run section now says `inventory.properties` is UTF-8 with the BOM skipped, and that a non-UTF-8 order file gives exit 65 with the line.
- `framework/ARCHITECTURE.md`: under 05c core changes, the BOM decision and its reason. The "Filer" rule in the config section now mentions the BOM. The intro "Båda är tillägg" is corrected to describe a behavior change only for BOM files.
- `framework/PROGRESS.md`: 05c now says 3 Åtgärdat and 5 Kvar. Added a round-3 note and updated the test counts (demo 42, config 68).
- MINORs: `OrderProcessor.java` (BRK-3), `InventoryApp.java` (MNT-1), `AuditTrail.java` (MNT-2), `OrderLine.java` (MNT-3), and a test in `FailureTests.java` (BRK-3).

## Verification
- `java Build.java` in `framework/` gave exit=0:
  ```
  guardrails: no violations
  spola.guardrails: OK - 17 tests ... spola.test: OK - 77 tests
  spola.di: OK - 48 tests, spola.config: OK - 68 tests, spola.util: OK - 80 tests
  spola.demo: OK - 42 tests, 42 ok, 0 failed
  ```
- Checked that the new tests are real: I temporarily commented out the `skipByteOrderMark(reader)` call and rebuilt. Two tests failed: `spola.config > sources > a byte order mark at the start of a file is skipped, not part of the first key` and `spola.demo ... a byte order mark at the start of inventory.properties does not hide the first setting`. I then restored the call, and the build is green.
- Manual reproduction from the finding with the built `out/`: `\xEF\xBB\xBFrules.max.quantity=5` + `apple;50` now prints `processed 0 orders, rejected 1` / `line 1: apple x 50: exceeds max quantity 5`. Exit 0, which is a normal successful run with a rejection.

## Addressed
- BRK-1 [MAJOR]: fixed with option (a), the preferred one. Changed `spola.config` with a test, added an end-to-end test in `spola.demo`, updated the README for config and demo plus Javadoc and ARCHITECTURE, and recorded it as Friktion point 8 (**Åtgärdat**).
- BRK-3 [MINOR]: `CharacterCodingException` while reading the order file becomes an `OrderFileException` → exit 65, `orders.txt line N: not valid UTF-8; save the order file as UTF-8`. `BufferedReader` decodes ahead, so the line is found by decoding the bytes again with a strict decoder (`firstLineNotUtf8`). Test: Windows-1252 byte on line 2 → "line 2", and no `MalformedInputException` or stack trace.
- MNT-1 [MINOR]: added an `ioFailure(log, UncheckedIOException)` helper that is used in both places.
- MNT-2 [MINOR]: added a comment in the `AuditTrail` constructor. It says the check is a safety net and that `InventoryApp` pre-checks so it can report a configuration error.
- MNT-3 [MINOR]: the quantity is parsed once. `FORMAT` limits it to at most 9 digits, so `parseInt` cannot overflow.

## Rebuttals
- (none)

## Known gaps
- BRK-2 [MINOR]: still not fixed. `--audit.file=<the config file>` still overwrites the configuration file. The configuration file's path is only known in the bootstrap round of `InventoryWiring` and is not part of `AppSettings`. Fixing it means threading that path through, and the judge rated this an unusual configuration mistake. The order-file case is guarded.
- MNT-4 [MINOR]: the loop body is unchanged. The fix hint says to extract it only if it grows, and it has not grown.
