```
VERDICT: FAIL
FINDINGS:
- id: BRK-1
  severity: MAJOR
  where: framework/modules/util/src/spola/util/Log.java:586-593 (emit) and 605-609 (supplier variant)
  problem: The round-2 BRK-1 fix only works after `Logging.configure`. With a plain `Log.forClass(...)` and the JDK's default logging, which the spec says is left alone ("Ingenting konfigureras som bieffekt"), the same input still throws an Error out of `log.warn`. That breaks the spec ("Ett argument vars toString kastar ger inte undantag ut ur loggningen") and Log's own Javadoc ("the message is still logged ... Only VirtualMachineErrors other than StackOverflowError propagate").
  evidence: The scratch program is /workspace/gauntlet/runs/20261002-0751-util-loggning-och-hjalpare/round-3/scratch-breaker/T.java. It runs against framework/out/spola.util.jar and never calls Logging.configure. The input is `Log.forClass(T.class).warn("x", e)`, first with `e` whose `toString(){ throw new AssertionError("boom"); }`, then with `e` whose `getMessage(){ return "failed: " + this; }`. Run as `java -p out/spola.util.jar --add-modules spola.util -cp . T`, it prints:
      ESCAPED default class java.lang.AssertionError
      ESCAPED default class java.lang.StackOverflowError
    The cause: `emit` hands the Throwable straight to `logger.log(level, msg, thrown)`. The JDK's StreamHandler.publish catches only `Exception` from `format`, so `printStackTrace` errors pass through. With `Logging.configure(...)` (run as `T 1`) all five cases (toString throws AssertionError, recursive getMessage SOE, getCause throws, getLocalizedMessage throws, multi-line message) log a record with a marker and nothing escapes. So the fix covers only one configuration. Calling a Log before configuring, or without configuring at all (libraries, tests, after `Logging.reset()`), is something a good-faith developer will do.
  fix_hint: In `Log.emit` and in `log(Level, Supplier, Throwable)`, wrap `logger.log(level, text, thrown)` in `catch (Throwable)`. Rethrow fatal VMErrors other than SOE (as `MessageFormatter.marker` does). Otherwise log again without the throwable, with `thrown.getClass().getName()` plus a marker added to the text. Add a test that uses a System.Logger whose `log(level, msg, thrown)` calls `thrown.toString()`, or that uses the default JUL setup.
```

Everything else I tried passes. With `Logging.configure`, all five bad-exception cases are logged with a single marker line, nothing escapes, and the next record still comes out. A multi-line exception message stays on tab-prefixed continuation lines, so it cannot fake a new record. I ran `java Build.java` myself and it passed (BUILD OK).
