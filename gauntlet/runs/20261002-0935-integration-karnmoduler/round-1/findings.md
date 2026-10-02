# Round 1 — verdict: FAIL

Verified myself: `java Build.java` in `framework/` exits 0 (spola.demo 36/36, guardrails clean). I reproduced BRK-1 and BRK-2 against `framework/out/classes` in a fresh temp dir. The order file is input from outside the process (REGLER.md §4, parsing), so crafted lines count as realistic input.

## Upheld findings (the builder must address these)
- BRK-1 [MAJOR] framework/modules/demo/src/spola/demo/OrderProcessor.java:47-56 + OrderLine.java:15 (FORMAT) — An order file that starts with a UTF-8 BOM, which is an ordinary export from Excel or Notepad, has its first order rejected as "unknown sku" with exit 0. Reproduced: `\xEF\xBB\xBFapple;3\r\napple;2\r\n` gives "processed 1 orders, rejected 1 / line 1: ?apple x 3: unknown sku", exit 0. The U+FEFF ends up inside the SKU and is also written raw to the summary. A user acting in good faith gets a wrong result from an ordinary file. — fix_hint: Strip a leading U+FEFF from line 1 before parsing, and add an end-to-end test through the entry with a BOM-prefixed order file (the first order is accepted).
- BRK-2 [MAJOR] framework/modules/demo/src/spola/demo/OrderLine.java (parse) → ReportWriter.java:30-33, AuditTrail.java:50-56 — SKUs from the order file can contain control and format characters (ESC, NEL U+0085, LS U+2028), and these are written unescaped to the summary on stdout and to the signed audit trail. Reproduced: `ev\u0085il\u001b[1Aforged;1` puts a raw 033 [ 1 A in both the summary and `o3.txt.audit`. The program escapes the same characters in the broken-line error (`OrderLine.printable`), and spola.util escapes them in the log, so this output path is the one that is inconsistent. The order file is external data (§4), so the crafted input is realistic. — fix_hint: Validate SKUs in `OrderLine.parse` (reject ISO control characters, U+2028/U+2029 and other Cf/Cc code points with an `OrderFileException` that names the line number, shown via `printable`), or escape on output in both ReportWriter and AuditTrail. Add a test through the entry. Make sure the BRK-1 fix strips the BOM before this validation, so that a BOM file still works.

## Rejected
- AUD-1 — The spec's criterion 9 says "kontrollerbart med sökning", so a text search is the agreed check, and the critic itself calls it acceptable as is. There is no defect.

## Duplicates
- (none)

## MINOR (optional for the builder)
- BRK-3 [MINOR] AuditTrail.java:42-45 — If `audit.file` points at the order file, the order file is truncated before it is read and the run reports 0 orders with exit 0. This needs an unusual configuration mistake. fix_hint: reject the configuration when the two resolve to the same file, or open the audit trail after the orders have been read.
- BRK-4 [MINOR] modules/demo/README.md:88 — The claim that values built from a sensitive value through a placeholder are masked does not hold once such a value is bound as a path (it is logged in clear text). This needs a deliberately odd configuration (`inventory.orders=${audit.secret}`). fix_hint: narrow the README claim to the config dump and conversion errors.
- BRK-5 [MINOR] InventoryApp.java:115 — An `audit.file` in a directory that does not exist gives exit 70 ("software error") and a message with internal detail (`lambda$static$3(InventoryWiring.java:154)`). It still fails with a non-zero exit, names the path and prints no stack dump, so it is MINOR. fix_hint: map an IOException cause from component creation to 74 with a short message that names `audit.file`, or record it as friction.
- MNT-1 [MINOR] InventoryWiring.java:97-111 — POSITIVE_INT and STOCK_LEVEL are the same converter twice. fix_hint: add a private `intAtLeast(min)` factory.
- MNT-2 [MINOR] InventoryWiring.java:56-74 — The bootstrap and the full builder chain repeat the same sources, and the profile file name is a literal. fix_hint: extract the shared sources into a helper and make the profile file name a constant. This is partly inherent to the bootstrap friction that is already listed as Kvar.
- MNT-3 [MINOR] InventoryWiring.java:97-118 — The converter constants are declared after the methods that use them. fix_hint: move them up beside the other constants.
- MNT-4 [MINOR] BoundaryTests.java:37 — The bracket-escaped regex is hard to read. The intent is documented in Javadoc. fix_hint: optional; it can stay as is.
