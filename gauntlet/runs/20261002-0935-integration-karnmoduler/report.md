# Report: 20261002-0935-integration-karnmoduler

- **Status:** PASS
- **Title:** Integration: kärnmodulerna i ett riktigt program
- **Mission:** uppdrag/05c-integration-karnmoduler.md (moved to uppdrag/klara/)
- **Rounds:** 3 of 4

| Round | Verdict | Upheld | Build | Commit |
|---|---|---|---|---|
| 1 | FAIL | 0 blocker, 2 major, 7 minor | green | cf8b464 |
| 2 | FAIL | 0 blocker, 1 major, 6 minor | green | 86183f6 |
| 3 | PASS | 0 blocker, 0 major, 6 minor | green | 71c53f5 |

## Built
New module `spola.demo`: an inventory program for a small web shop. The entry is `InventoryApp` (`main`, plus `run(args, env, out, log)` that returns an exit code) and the wiring module is `InventoryWiring`. It uses spola.di (components, a qualifier, an AutoCloseable audit trail), spola.config (defaults, file, profile, prefixed env and args, a sensitive key, records) and spola.util (Log, Logging.configure). There are 42 tests. The friction report in `modules/demo/README.md` has 8 points: 3 **Åtgärdat** (`Section.keys()`, `LoggingSettings.parseLevel`, BOM skipping in `Source.file`) and 5 **Kvar**, which are also listed in PROGRESS "Återstår".

## Fixed along the way
- R1 BRK-1: an order file starting with a UTF-8 BOM lost its first order. The BOM is now skipped.
- R1 BRK-2: control characters in a SKU reached the summary and the audit trail. Such SKUs now make the line broken (exit 65).
- R1 minors: audit.file pointing at the order file is now rejected, audit I/O errors give exit 74, the masking claim in the README is narrowed, and the duplicated converters are merged.
- R2 BRK-1: a config file starting with a BOM lost its first setting. It is fixed in spola.config's `Source`.
- R2 minors: a non-UTF-8 order file now gives exit 65 with a line number, the I/O error mapping lives in one helper, and the quantity is parsed once.

## Remaining (MINOR)
- The Javadoc of `OrderProcessor.process()` says earlier orders are decided when the file is not UTF-8. Because of read-ahead buffering, that is false.
- `firstLineNotUtf8` counts only `\n` line breaks.
- The BOM-skipping rule is implemented twice (demo and config).
- `Source.java` uses fully qualified names inline.
- `OrderProcessor.process()` does several jobs at once.
- `--audit.file=<config file>` overwrites the configuration file.

## Assumptions
- Config files are created by the tests in temporary directories.
- Order lines have the form `sku;antal`.
- The wiring-error test uses a package-private overload of the entry, because end-user configuration cannot cause a wiring error.

## Stash
–
