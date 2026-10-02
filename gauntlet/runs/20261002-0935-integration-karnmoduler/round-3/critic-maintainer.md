```
VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /workspace/framework/modules/demo/src/spola/demo/OrderProcessor.java:24-61 and /workspace/framework/modules/config/src/spola/config/Source.java:151
  problem: The same "skip a leading BOM" rule is implemented twice, once with mark/reset on the reader in config and once with a per-line `charAt` check in the demo. A later change to the BOM rule has to be made in both places.
  evidence: `BYTE_ORDER_MARK` and `text.charAt(0) == BYTE_ORDER_MARK` in OrderProcessor, and `reader.read() != '﻿'` in Source.skipByteOrderMark
  fix_hint: Add a comment in OrderProcessor saying the duplication is deliberate because the demo cannot reach the private config helper. Or expose a small shared util that opens a UTF-8 reader without a BOM.

- id: MNT-2
  severity: MINOR
  where: /workspace/framework/modules/demo/src/spola/demo/OrderProcessor.java:91-108
  problem: `firstLineNotUtf8` counts `\n` bytes, but `BufferedReader.readLine` also ends a line on a lone `\r`. For files with old Mac line endings the reported line number differs from the `number` counter used for valid lines. The helper also re-reads the whole file and has a hard-to-follow decode call.
  evidence: `if (bytes.get(i) == '\n') line++` and `decode(bytes, CharBuffer.allocate(bytes.remaining()), true)`
  fix_hint: Mention the `\n`-only assumption in the Javadoc, or count `\r` as well. Add a short comment explaining that `bytes.position()` stops at the malformed input.

- id: MNT-3
  severity: MINOR
  where: /workspace/framework/modules/config/src/spola/config/Source.java:86 and 151
  problem: Fully qualified names (`java.util.Collections`, `java.io.BufferedReader`) are used inline where an import would match the rest of the file.
  evidence: `java.util.Collections.unmodifiableMap(copy)` and `skipByteOrderMark(java.io.BufferedReader reader)`
  fix_hint: Import both types.

- id: MNT-4
  severity: MINOR
  where: /workspace/framework/modules/demo/src/spola/demo/OrderProcessor.java:50-84
  problem: `process()` still opens the file, parses lines, decides each order, records the results and translates exceptions. This was already noted as MNT-4 and is carried over.
  evidence: The loop body is about 20 lines inside a try with two catch clauses.
  fix_hint: Extract `handle(order, processed, rejected)` if the method grows further.
```
