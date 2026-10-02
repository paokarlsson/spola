```
VERDICT: FAIL
FINDINGS:
- id: BRK-1
  severity: MAJOR
  where: framework/modules/config/src/spola/config (Source.file, used by InventoryWiring.config at InventoryWiring.java:84); the issue shows through spola.demo
  problem: Round 2 fixed the BOM for the order file but not for the program's configuration file. If inventory.properties starts with a UTF-8 BOM (the same Notepad/Excel export that BRK-1 in round 1 was about), the first key becomes "﻿<key>", and that setting is silently dropped or reported as missing even though it is in the file.
  evidence: In /workspace/gauntlet/runs/20261002-0935-integration-karnmoduler/round-2/scratch-breaker/t3, I ran: printf '\xef\xbb\xbfrules.max.quantity=5\r\ninventory.orders=orders.txt\r\ninventory.stock.apple=100\r\naudit.secret=abcdefghijklmnopqrst\r\n' > inventory.properties; printf 'apple;50\n' > orders.txt; java -p framework/out/classes -m spola.demo/spola.demo.InventoryApp
    → "processed 1 orders, rejected 0 / line 1: apple x 50 / apple: 50", exit=0. The file sets max quantity 5, but the default of 100 is used, so an order that should be rejected is accepted.
    With the BOM in front of inventory.orders= instead, the run fails with "error: invalid configuration: ... missing key 'inventory.orders' (expected Path)", exit 78, even though the key is in the file.
    With a BOM before a "# comment" first line, the DEBUG config dump shows a junk key "?# = shop config".
  fix_hint: Strip a leading U+FEFF in spola.config's file source, the same way OrderProcessor now does. Add a test in spola.config, plus an end-to-end test in spola.demo with a BOM-prefixed inventory.properties. If this is not fixed now, list it under Friktion/Kvar and in PROGRESS "Återstår".
- id: BRK-2
  severity: MINOR
  where: framework/modules/demo/src/spola/demo/AuditTrail.java:68-83 (overwritesOrders/sameFile) and InventoryApp.java:105
  problem: The new guard only stops audit.file from pointing at the order file. Pointing it at the configuration file truncates inventory.properties, replaces it with audit lines, and the run still exits 0.
  evidence: In scratch-breaker/t4 I ran InventoryApp --audit.file=inventory.properties. The result was exit=0, and inventory.properties now contains only "accepted line=1 sku=apple quantity=1 / closed entries=1 hmac-sha256=...".
  fix_hint: Also reject audit.file when it is the same file as config.file or one of the profile files, or open the audit trail with CREATE_NEW / write to a temp file and move it into place.
- id: BRK-3
  severity: MINOR
  where: framework/modules/demo/src/spola/demo/OrderProcessor.java:73-74, InventoryApp.java:125
  problem: An order file that is not UTF-8 (for example "äpple;2" in Windows-1252) fails with exit 74 ("I/O error") and an internal exception text. There is no line number and no hint about the encoding, and orders before the bad buffer may already be decided in the audit trail.
  evidence: printf 'apple;3\n\xe4pple;2\npear;1\n' > orders.txt → "error: cannot read the order file orders.txt: java.nio.charset.MalformedInputException: Input length = 1", exit=74.
  fix_hint: Catch CharacterCodingException separately and report "orders.txt is not UTF-8 (near line N)" as DATA_ERROR 65, or document this in the README exit-code table.
```

I tried the round-2 changes (BOM handling, SKU validation, audit.file == order file, the exit-74 mapping) along with secret masking in placeholders, short secrets and malformed properties escapes. I also tried dotted and unicode SKUs, CRLF and tab whitespace, the blocked list with spaces, and empty log levels. None of these showed a defect beyond the three findings. `java Build.java` passes in a copy of `framework/` (BUILD OK). The Kvar items 3 to 7 are listed in PROGRESS "Återstår".

Scratch files are in `/workspace/gauntlet/runs/20261002-0935-integration-karnmoduler/round-2/scratch-breaker/`.
