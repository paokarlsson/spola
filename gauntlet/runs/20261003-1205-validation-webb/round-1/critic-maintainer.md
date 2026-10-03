```
VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /workspace/framework/modules/web/src/spola/web/FormFields.java:279-318
  problem: The path rendering (the member and element rules and `isIdentifier`) is now copied into a fourth place. The others are `spola.json.Paths`, `spola.json.codec.Path` and `spola.validation.Path`/`Text`. A change to the notation has to be made in all four.
  evidence: The Javadoc says "a private copy of the member and element rendering of spola.validation.Path ... since neither module exports it. A test compares the two for the same names." The comparison test limits the drift risk, which is why this is only MINOR.
  fix_hint: Export one small path-rendering helper from `spola.validation` (or `spola.json`) and delete the copy. If that is not done, list all four copies in ARCHITECTURE.md so that a future change finds them.
- id: MNT-2
  severity: MINOR
  where: /workspace/framework/modules/web/src/spola/web/FormFields.java:87-105
  problem: The same `count > 0` then `throw failure(subject)` check appears in both the catch block and the normal path of `bind`.
  evidence: `if (collector.count > 0) { throw collector.failure(subject); }` appears twice.
  fix_hint: Catch the exception into a local variable, then decide once after the `finally`. Alternatively, keep the current form and add a comment saying that the duplication is intended.
- id: MNT-3
  severity: MINOR
  where: /workspace/framework/modules/web/src/spola/web/FormFields.java:245-247
  problem: Whether a value counts as empty depends on the identity check `param != Param.STRING`. A user-defined `Param.of(...)` string parser is treated differently from `Param.STRING`, and nothing in the `Param` API says so.
  evidence: `return text.isEmpty() && param != Param.STRING;`
  fix_hint: Add a note on `Param.STRING` that points to this rule, or give `Param` a package-private `emptyIsValue` flag.
```
