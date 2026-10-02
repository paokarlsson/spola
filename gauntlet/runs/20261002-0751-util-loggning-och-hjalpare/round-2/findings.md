# Round 2 — verdict: FAIL

## Upheld findings (the builder must address these)
- BRK-1 [MAJOR] framework/modules/util/src/spola/util/LineFormatter.java:76-81 (`stackTrace` catches only `RuntimeException`), together with LineHandler.java:34-38 (`publish` catches only `RuntimeException` from `format`) — If the trailing exception's `toString()`/`getMessage()` throws an `Error`, that `Error` escapes from the logging call. The judge reproduced this independently against `framework/out/spola.util.jar`. `log.warn("t", e)` with `toString(){ throw new AssertionError("boom"); }` gives `ESCAPED java.lang.AssertionError: boom`. `log.warn("d", e)` with the common recursive mistake `getMessage(){ return "failed: " + this; }` gives `ESCAPED class java.lang.StackOverflowError`. This breaks the spec ("Ett argument vars toString kastar ger inte undantag ut ur loggningen"). It also contradicts Log's own Javadoc (Log.java: "Only VirtualMachineErrors other than StackOverflowError ... propagate"). The same object, when a placeholder consumes it, is caught and replaced with a marker. A developer acting in good faith can trigger this with a recursive `getMessage`, and it happens in an error path (inside a catch block), where losing the original exception hurts most. So MAJOR. — fix_hint: In `LineFormatter.stackTrace`, catch `Throwable` and render it through `MessageFormatter.marker`, which already rethrows fatal VMErrors other than SOE. A recursive `getMessage` can also throw SOE from inside `printStackTrace` after part of the trace has been written, so discard the partial buffer as you do now. Consider the same widening in `LineHandler.publish` (`reportError` for non-fatal Throwables). Add tests: a trailing Throwable whose `toString` throws `AssertionError`, and one whose `getMessage` recurses. Both must yield a logged record with a marker and no exception from the logging call.

## Rejected
- (none)

## Duplicates
- (none)

## MINOR (optional for the builder)
- MNT-1 [MINOR] LineFormatter.java:103 — The literal U+2028/U+2029 characters in source are invisible and fragile. Upheld as MINOR. Note: the critic's warning about `\u` escapes is wrong. U+2028/U+2029 are not Java line terminators, so `' '` and `' '` compile fine as char literals and are the simplest readable fix. `Character.getType(c) == Character.LINE_SEPARATOR || ... == Character.PARAGRAPH_SEPARATOR` also works.
- MNT-2 [MINOR] LineFormatter.java:96-108 and docs — The escape rule is described in several places. Optional: make the Logging Javadoc refer to `escape` as the single definition.
- AUD-1 [MINOR] LazyTests supplier-release test relies on `System.gc()`. This is documented, and the builder's reason for leaving it is acceptable. Carried over as MINOR.
- AUD-2 [MINOR] The tab prefix on stack-trace lines differs from the JDK format. This is a deliberate fix for round-1 BRK-1 and is documented in Javadoc, ARCHITECTURE and PROGRESS. The spec only says "stackspår på följande rader", so this is acceptable. No action needed.
