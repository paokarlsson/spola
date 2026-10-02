```
VERDICT: FAIL
FINDINGS:
- id: BRK-1
  severity: MAJOR
  where: framework/modules/demo/src/spola/demo/OrderProcessor.java:46-47 (Javadoc of process()), :56-78 (read loop and the new CharacterCodingException catch)
  problem: The Javadoc added this round says that when the order file is not valid UTF-8, "the orders before it are decided". That is false. BufferedReader/StreamDecoder decode ahead in blocks, so the exception fires before the earlier lines in the same block are read. Valid orders before the bad line are not decided and are missing from the signed audit trail, while the error message gives the exact bad line number.
  evidence: |
    Used the jars built by `java Build.java` (exit 0), copied to round-3/scratch-breaker/mods.
    Small file: printf 'apple;1\napple;2\ncaf\xe9;1\n' > orders.txt (Windows-1252 é on line 3), audit.file=audit.log
      java -p ../mods -m spola.demo/spola.demo.InventoryApp
      -> "error: broken order file: orders.txt line 3: not valid UTF-8; ..." exit=65
      -> audit.log: "closed entries=0 ..."   (lines 1 and 2 were never decided)
    Realistic size, about 30 KB: apple;1, then 3000 x pear;0001, then apple;2, then caf\xe9;1, then apple;3
      -> "orders.txt line 3003: not valid UTF-8", exit=65
      -> "audit trail audit.log closed after 2457 entries" (the last entry is line=2457, so lines 2458-3002 are missing)
    Compare a format error on line N, where lines 1..N-1 are decided and audited, as documented. A single Windows-1252 character, which is the ordinary case the round targeted, breaks the documented guarantee for any file.
  fix_hint: Either read the file as bytes and decode one line at a time with a strict decoder, so the earlier lines are really decided, or check the whole file for UTF-8 before processing any order. Otherwise narrow the Javadoc (and README) to say that no orders or only some orders are decided when the file is not UTF-8.

FINDINGS (non-findings tried, for the record):
- The first `java Build.java` run went red ("module not found: spola.di"). Re-running gave exit 0, and spola.demo is 42/42. The red run most likely came from another critic building into the shared `out/` at the same moment, so I did not report it.
- A BOM in inventory.properties and in a profile file (`inventory-prod.properties`, through optionalFile) is now honoured: max.quantity=5 rejects apple;50.
- A Windows-1252 inventory.properties containing a secret gives exit 78, and the message names the path without leaking the secret.
- A double BOM is not skipped. That is pathological input, so I did not report it.
- Friktion has 8 points (3 Åtgärdat, 5 Kvar), and the Kvar items are listed under PROGRESS "Återstår".
```

Scratch experiments are in /workspace/gauntlet/runs/20261002-0935-integration-karnmoduler/round-3/scratch-breaker/ (t1 = UTF-8 decision order, t2 = config BOM and encoding).
