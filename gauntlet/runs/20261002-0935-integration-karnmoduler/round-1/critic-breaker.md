```
VERDICT: FAIL
FINDINGS:
- id: BRK-1
  severity: MAJOR
  where: framework/modules/demo/src/spola/demo/OrderProcessor.java:47-56 with OrderLine.java:18 (FORMAT `[^;\s]+`)
  problem: An order file saved as UTF-8 with a byte-order mark (a normal export from Excel "CSV UTF-8" or older Notepad) has its first order silently rejected as "unknown sku", and the run still exits 0.
  evidence: `printf '\xef\xbb\xbfapple;3\r\napple;2\r\n' > o2.txt; java -p framework/out/classes -m spola.demo/spola.demo.InventoryApp --config.file=inventory.properties --inventory.orders=o2.txt` (stock apple=10) prints "processed 1 orders, rejected 1 / rejected: line 1: ?apple x 3: unknown sku / final stock: apple: 8", exit 0. The BOM U+FEFF is not matched by `\s`, so it ends up inside the SKU. The summary is wrong and nothing signals an error.
  fix_hint: Strip a leading U+FEFF from line 1 before parsing, or reject SKUs that contain format or control characters, and add a test.

- id: BRK-2
  severity: MAJOR
  where: framework/modules/demo/src/spola/demo/ReportWriter.java:30-33, AuditTrail.java:50-56 (SKU from OrderLine.parse is written unescaped)
  problem: The order file is data from outside the process, and its SKU is written raw to the summary on stdout and to the signed audit trail, so control characters (ESC sequences, NEL U+0085, LS U+2028) get through. The program escapes them only in the broken-line error message (`OrderLine.printable`), and the log escapes them through spola.util.
  evidence: The order line `ev<U+0085>il<ESC>[1A<ESC>[2Kprocessed<U+2028>forged;1` passes the `[^;\s]+` pattern. The summary (`od -c`) contains `l i n e   2 :   e v ? i l 033 [ 1 A 033 [ 2 K p r o c e s s e d ? f o r g e d   x   1 :   u n k n o w n   s k u`: a raw ESC "cursor up and erase line", which hides an earlier summary line in a terminal. The audit file contains `s k u = e v 302 205 i l 033 [ 1 A 033 [ 2 K ... 342 200 250 f o r g e d`, so the same sequence erases or forges lines when someone `cat`s the "tamper-evident" trail. The DEBUG log line for the same order is escaped (`ev\u0085il\u001b[1A...`), which shows the inconsistency.
  fix_hint: Validate SKUs in OrderLine.parse (for example, printable non-control code points only; otherwise throw OrderFileException with the line number), or escape them the way `printable` does before writing to the summary and the audit trail.

- id: BRK-3
  severity: MINOR
  where: framework/modules/demo/src/spola/demo/AuditTrail.java:42-45 (opens and truncates in the constructor, before the orders are read)
  problem: If `audit.file` points at the order file (for example after a copy-paste mistake in the properties file), the order file is truncated before it is read, and the program reports "processed 0 orders" with exit 0. The input is lost without any error.
  evidence: `... --inventory.orders=o2.txt --audit.file=o2.txt` prints "processed 0 orders, rejected 0", exit 0, and afterwards `cat o2.txt` gives `closed entries=0 hmac-sha256=472eea...`.
  fix_hint: Reject the configuration when audit.file and inventory.orders resolve to the same file, or open the audit trail only after the orders have been read.

- id: BRK-4
  severity: MINOR
  where: framework/modules/demo/README.md ("liksom värden som byggs av ett känsligt värde med en platshållare") vs AuditTrail.java:46, OrderProcessor.java:43, InventoryApp.java:105
  problem: The README says values built from the secret through a placeholder are masked, but once such a value is bound as a path it is logged in clear text.
  evidence: `INVENTORY_AUDIT_SECRET=Pr1vate-Audit-Key-7781 ... '--inventory.orders=${audit.secret}'` makes the INFO log contain "audit trail Pr1vate-Audit-Key-7781.audit opened" and "processing orders from Pr1vate-Audit-Key-7781" (4 occurrences, exit 74). With DEBUG it also prints "settings: AppSettings[inventory=InventorySettings[orders=Pr1vate-Audit-Key-7781 ...".
  fix_hint: Narrow the README claim to what is true (config dump and conversion errors), or treat values derived from a sensitive key as sensitive after binding.

- id: BRK-5
  severity: MINOR
  where: framework/modules/demo/src/spola/demo/InventoryApp.java:115 (ResolutionException maps to SOFTWARE_ERROR)
  problem: A user configuration mistake (an `audit.file` in a directory that does not exist) is reported as exit 70, "software error", with internal developer detail, instead of a user-facing I/O or configuration error.
  evidence: `--audit.file=$S/nope/a.audit` gives "error: cannot resolve spola.demo.OrderProcessor -> spola.demo.AuditTrail: the factory of spola.demo.AuditTrail (bound at spola.demo.InventoryWiring.lambda$static$3(InventoryWiring.java:154)) threw java.nio.file.NoSuchFileException: .../nope/a.audit", exit=70.
  fix_hint: Map an IOException cause from component creation to IO_ERROR (74) with a short message that names audit.file.
```

Checked and found OK: the build passes in a clean copy (/workspace/gauntlet/runs/20261002-0935-integration-karnmoduler/round-1/scratch-breaker/fw, 36 demo tests). Criterion 9 (searching for Container, Binder and Config) and module-info `requires` are clean. The secret stays masked at TRACE/DEBUG, in a too-short-secret error and in a mistyped `--audit.secret` argument. Bad log levels, bad logger prefixes, non-numeric or overflowing stock, and empty values all fail with exit 78 and name the key. The priority order and profile tests match the config README. A missing order file closes the audit trail and exits 74. An empty order file works.

The experiment files are in /workspace/gauntlet/runs/20261002-0935-integration-karnmoduler/round-1/scratch-breaker/shop/.
