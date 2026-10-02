# Round 2 – build

## Changes
- `modules/demo/src/spola/demo/OrderProcessor.java`: skip a leading U+FEFF (BOM) on line 1 before the blank/comment check and parsing (BRK-1).
- `modules/demo/src/spola/demo/OrderLine.java`: `parse` rejects an SKU containing any non-visible code point (`OrderLine.visible`: not Cc, Cf, Zl, Zp, Zs, Cs or unassigned) with an `OrderFileException` naming the file and line. `printable` now escapes the same set (except ASCII space), so RLO, NBSP and similar characters are escaped in the error message too (BRK-2).
- `modules/demo/src/spola/demo/AuditTrail.java`: the constructor no longer throws a checked `IOException`. An open failure becomes `UncheckedIOException("cannot open the audit trail <file> (audit.file)")`. New `file(...)` and `overwritesOrders(...)` methods; the constructor refuses an audit trail that would be the order file (BRK-3, BRK-5).
- `modules/demo/src/spola/demo/InventoryApp.java`: if `AuditTrail.overwritesOrders`, the run fails with exit 78 before the container is created (BRK-3). A `ResolutionException` whose cause is an `UncheckedIOException` maps to 74 with the short message (BRK-5). Javadoc of `IO_ERROR` updated.
- `modules/demo/src/spola/demo/InventoryWiring.java`: `intAtLeast(min)` factory replaces the duplicated converters, and the converter constants moved above the methods (MNT-1, MNT-3).
- Tests: `ProgramTests` gained two tests: a BOM+CRLF file written as raw bytes is accepted, and a BOM before a comment line. `FailureTests` gained an invisible-SKU test (ESC, NEL, LS, PS, RLO, mid-file BOM, NBSP, DEL: exit 65, line named, the raw character appears in neither out, log nor audit) and an audit.file==order file test (also via `sub/../`, exit 78, order file unchanged). The "component cannot be created" test became the exit-74 test (names `audit.file`, no `lambda$`).
- `modules/demo/README.md`: notes on the order file's encoding, BOM and SKU characters; exit-code table (74, 78); the masking claim narrowed to the config dump and conversion errors, in both places (BRK-4).
- `PROGRESS.md` (round-2 entry, 40 tests) and `ARCHITECTURE.md` (decisions: validate on read rather than escape on output; audit.file != orders).

## Verification
`java Build.java` in `framework/` → exit 0:
```
guardrails: no violations
spola.guardrails: OK - 17 tests
spola.test: OK - 77 tests
spola.di: OK - 48 tests
spola.config: OK - 66 tests
spola.util: OK - 80 tests
spola.demo: OK - 40 tests, 40 ok, 0 failed
BUILD OK in 19586 ms
exit 0
```
Manual reproduction of the breaker's cases against `out/classes` in a temp dir:
- `\xEF\xBB\xBFapple;3\r\napple;2\r\n` → `processed 2 orders, rejected 0`, `line 1: apple x 3`, `apple: 5`, exit 0.
- `ev\u0085il\u001b[1Aforged;1` → `error: broken order file: o3.txt line 1: the sku may only hold visible characters (...), got 'ev\u0085il\u001b[1Aforged;1'`. The audit file contains only `closed entries=0 hmac-sha256=...` (checked with `cat -v`: no raw ESC).

## Addressed
- BRK-1 (MAJOR): BOM stripped on line 1. The end-to-end test uses raw BOM bytes and CRLF, and the first order is accepted.
- BRK-2 (MAJOR): SKUs are validated in `OrderLine.parse` (after the BOM strip), and the rejection names the line. There is an end-to-end test for 8 characters, which also checks that the raw character is in no output or audit trail.
- BRK-3 (MINOR): rejected as configuration (78) before anything is opened; there is also a guard in the constructor; tested.
- BRK-4 (MINOR): the README claim is narrowed.
- BRK-5 (MINOR): mapped to 74 with a short message that names `audit.file`; tested.
- MNT-1, MNT-3 (MINOR): done.
- MNT-2 (MINOR): not done. The duplicated sources are the bootstrap friction already listed as **Kvar**, and extracting a helper here would only hide it.
- MNT-4 (MINOR): left as is (the judge says optional; the intent is documented).

## Rebuttals
None.

## Known gaps
- The SKU rule is strict: Zs is rejected, so NBSP inside an SKU is a broken line. That is intended, since SKUs are identifiers, but it is a policy choice.
- `sameFile` compares normalized absolute paths and, when both exist, `Files.isSameFile`. A hard-link or symlink alias of an order file is caught only because the order file exists at check time, which it always does for a run that gets that far.
