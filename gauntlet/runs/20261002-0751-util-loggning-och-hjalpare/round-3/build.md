# Round 3 — build

## Changes
- `framework/modules/util/src/spola/util/LineFormatter.java`: `stackTrace` now catches `Throwable` instead of `RuntimeException`. It drops the partial trace and writes one continuation line, `\t<exception class> [printStackTrace() threw <X>]`. It goes through `MessageFormatter.marker`, which rethrows only fatal VirtualMachineErrors other than StackOverflowError. That is the same rule placeholders use, and the one Log's Javadoc states. `getClass().getName()` is final, so it cannot throw. The Javadoc describes the fallback. The U+2028/U+2029 literals are now `' '`/`' '` escapes (MNT-1). `escape`'s Javadoc names U+2028/U+2029 and states that it is the single definition of the rule (MNT-2, partly).
- `framework/modules/util/src/spola/util/LineHandler.java`: `publish` now catches `Throwable` from `format`. Fatal VMErrors (not SOE) are rethrown. Anything else goes to `reportError(FORMAT_FAILURE)`, and non-Exceptions are wrapped in a RuntimeException because `reportError` takes an `Exception`.
- `framework/modules/util/test/spola/util/LoggingTests.java`: new test "a trailing exception whose toString or getMessage throws an Error is still logged". It logs `warn("t", e)` where `toString` throws `AssertionError`, then `warn("d", e)` where `getMessage` returns `"failed: " + this` (recursion, so SOE), then `info("after")`. It checks for exactly 5 lines: each record is logged, each marker line names the right exception class and Error, and the next record still comes out. Two helper exception classes are included.
- `framework/ARCHITECTURE.md` (Util part 1, console format): documents the fallback. `framework/PROGRESS.md`: test count 70 and the new test listed.

## Verification
`java Build.java` in `framework/`:
```
spola.guardrails: OK - 17 tests, 17 ok, 0 failed
spola.test: OK - 77 tests, 77 ok, 0 failed
spola.di: OK - 48 tests, 48 ok, 0 failed
spola.config: OK - 63 tests, 63 ok, 0 failed
    ok      a trailing exception whose toString or getMessage throws an Error is still logged (5 ms)
spola.util: OK - 70 tests, 70 ok, 0 failed
BUILD OK in 15872 ms
```
(The first attempt went red on javac `-Werror [serial]` for the helper classes. I added `serialVersionUID` and it went green.)
`grep -c $' ' LineFormatter.java` gives 0: there are no invisible literals left in the source.

## Addressed
- BRK-1 [MAJOR]: fixed in both `LineFormatter.stackTrace` and `LineHandler.publish`, as the fix hint suggested. A regression test covers both reproductions (AssertionError from toString, SOE from a recursive getMessage).
- MNT-1 [MINOR]: replaced the literals with ` `/` ` escapes.
- MNT-2 [MINOR]: partly. `escape`'s Javadoc is now marked as the single definition. The other doc spots are unchanged.
- AUD-1, AUD-2 [MINOR]: no action. The judge accepted both as they are.

## Rebuttals
- (none)

## Known gaps
- If the trace fails, all of it is lost, including frames that printed fine before the failure. This is deliberate: a partial buffer can end in the middle of a line.
- The `RuntimeException` wrapper in `LineHandler` exists only to satisfy the `reportError(String, Exception, int)` signature. In practice that branch can hardly be reached now, because `LineFormatter` catches the failures itself.
