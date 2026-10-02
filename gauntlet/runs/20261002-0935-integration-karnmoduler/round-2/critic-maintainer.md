```
VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /workspace/framework/modules/demo/src/spola/demo/InventoryApp.java:121-127
  problem: The same UncheckedIOException-to-exit-74 mapping is written twice, once directly and once unwrapped from a ResolutionException.
  evidence: "return fail(log, IO_ERROR, e.getMessage() + ": " + e.getCause());" and "return fail(log, IO_ERROR, io.getMessage() + ": " + io.getCause());"
  fix_hint: Add a small private helper such as ioFailure(log, UncheckedIOException), and call it from both branches.
- id: MNT-2
  severity: MINOR
  where: /workspace/framework/modules/demo/src/spola/demo/AuditTrail.java:47-51 and InventoryApp.java:100
  problem: The audit.file == orders check runs in two places, so it is evaluated twice. The constructor throws IllegalArgumentException, which the app maps to exit 70, but the app's own pre-check normally prevents that.
  evidence: "if (overwritesOrders(inventory, settings)) { throw new IllegalArgumentException(...)" in the constructor, and "AuditTrail.overwritesOrders(settings.inventory(), settings.audit())" in run().
  fix_hint: Add a comment in the constructor saying it is a safety net behind the app's pre-check, or move the check into AppSettings validation.
- id: MNT-3
  severity: MINOR
  where: /workspace/framework/modules/demo/src/spola/demo/OrderLine.java:36-45
  problem: parse() calls Integer.parseInt(group(2)) twice and does two kinds of validation (format and SKU), so the quantity is parsed redundantly.
  evidence: "Integer.parseInt(matcher.group(2)) < 1" and "new OrderLine(line, sku, Integer.parseInt(matcher.group(2)))"
  fix_hint: Parse the quantity once into a local variable, and only after matcher.matches() has succeeded.
- id: MNT-4
  severity: MINOR
  where: /workspace/framework/modules/demo/src/spola/demo/OrderProcessor.java:46-77
  problem: process() does reading, BOM handling, parsing, deciding and auditing in one loop body. It is still readable but is the natural place for further growth.
  evidence: The BOM strip, the comment skip and the decision logic all sit inline in the for loop.
  fix_hint: If it grows further, extract the BOM and comment handling into a helper such as a nextOrderText method.
```
