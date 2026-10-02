```
VERDICT: FAIL
FINDINGS:
- id: BRK-1
  severity: MAJOR
  where: framework/modules/util/src/spola/util/LineFormatter.java:80-82 (stackTrace catches only RuntimeException) together with framework/modules/util/src/spola/util/LineHandler.java:36-39 (publish catches only RuntimeException from format)
  problem: If the trailing Throwable's toString()/getMessage() throws an Error, that Error escapes from the logging call. The most likely case is a StackOverflowError from a recursive getMessage such as `getMessage(){ return "failed: " + this; }`. This breaks the spec rule "Ett argument vars toString kastar ger inte undantag ut ur loggningen". It also contradicts Log's documented policy (Log.java:55-59) that only VirtualMachineErrors other than StackOverflowError propagate. The same exception object, when a placeholder consumes it, is caught and rendered as a marker.
  evidence: I wrote a scratch program at /workspace/gauntlet/runs/20261002-0751-util-loggning-och-hjalpare/round-2/scratch-breaker/src/demo/Main.java and built it against a fresh copy whose `java Build.java` exited 0 (fw/out/spola.util.jar). It calls Logging.configure(defaults().withOutput(baos)) and then:
      class Bad extends RuntimeException { public String toString(){ throw new AssertionError("boom"); } }
      class Deep extends RuntimeException { public String getMessage(){ return getMessage(); } }
      log.warn("error toString", new Bad());     -> ESCAPED from log.warn: java.lang.AssertionError: boom
      log.warn("placeholder {}", new Bad());     -> no exception; line "... placeholder [toString() threw java.lang.AssertionError]"
      log.warn("deep", new Deep());              -> ESCAPED deep: class java.lang.StackOverflowError
      log.warn("x {}", "x", new BadRt())         -> (RuntimeException in toString) is caught: "\t[printStackTrace() threw java.lang.IllegalStateException]"
    So a trailing Throwable is protected only against RuntimeException. A placeholder argument is protected against every Throwable except non-SOE VirtualMachineErrors.
  fix_hint: In LineFormatter.stackTrace, catch Throwable and pass it through MessageFormatter.marker, which already rethrows fatal VMErrors other than SOE. Optionally do the same in LineHandler.publish. Add a test with a trailing Throwable whose getMessage recurses or whose toString throws an AssertionError.
```

The round-1 log-forging fix (BRK-1) holds. Checks I ran:
- **Build:** a fresh copy builds green (exit 0, 69 util tests).
- **Forging:** I logged an exception whose message contains `\r`, `\n`, U+0085, U+000B and U+2028, plus a full fake record. Every continuation line starts with a tab, U+0085, U+000B and U+2028 are escaped, and nothing reaches column 0.
- **Source encoding:** the literal U+2028/U+2029 characters in `escape` are stored as correct UTF-8 bytes.
