# Round 1 — verdict: FAIL

Judge's verification: the baseline `java Build.java` is green (exit 0). I reproduced BRK-1, BRK-2 and BRK-3 in throwaway copies of `framework/` under /tmp, which I then deleted. Each one gave `BUILD OK`, exit 0. The builder's rebuttal section is empty, and its "Known gaps" only excuse *indirect* reflection, which does not cover BRK-3. All acceptance criteria 1–10 hold as specified. The upheld issues are bypasses that let REGLER violations reach `framework/` without the guardrails noticing.

## Upheld findings (the builder must address these)
- BRK-1 [MAJOR] framework/modules/guardrails/src/spola/guardrails/FileTree.java:35 (`attrs.isRegularFile()` in visitFile; walkFileTree does not follow links) — The file check never sees a jar that is a symlink, or a jar inside a symlinked directory. Verified: `ln -s /tmp/real.jar lib/x.jar` gives BUILD OK, exit 0. This lets a vendored jar into the repo without detection (REGLER §1, AC2). — fix_hint: Report symlinks too, either by flagging every symlink under the checked root or by classifying symlinked files and dirs by name or target. Add a fixture line for it (tree.txt can describe a link, or the self-test can create one).
- BRK-2 [MAJOR] FileTree.java (source-check file list) vs Build.java compile() (`Files.walk` includes symlinked .java files) — A symlinked .java file inside a module gets compiled into the jar but skipped by the source check. Its SOURCE-retention annotations therefore get past every guardrail. Verified: Evil.java with `@javax.annotation.processing.Generated` was symlinked in. The output showed "source 16 .java files", "bytecode 23 .class files", `Evil.class` in `out/spola.guardrails.jar`, and BUILD OK, exit 0. — fix_hint: Derive the compile list and the source-check list from one shared walk, so they cannot diverge. Alternatively, refuse symlinks in module source trees with a reported violation. One fix in FileTree can cover both BRK-1 and BRK-2.
- BRK-3 [MAJOR] framework/modules/guardrails/src/spola/guardrails/SourceCheck.java:47-50 (ANNOTATION_READERS) — The check misses direct reflective annotation reads through `Executable.getParameterAnnotations()`. Verified: `m.getParameterAnnotations()[0].length` in a new class gives BUILD OK, exit 0. The builder chose to enforce REGLER §2 "läsa annoteringar via reflektion" and documents only indirect calls as a gap. A *direct* annotation-reading API on `java.lang.reflect` is a hole in that claim, and it is exactly the API a DI container would use. — fix_hint: Add `getParameterAnnotations` and the `getAnnotated*` family (`getAnnotatedReturnType`, `getAnnotatedParameterTypes`, `getAnnotatedType`, …). Consider `Method.getDefaultValue` too, or match all public methods of `java.lang.reflect`/`java.lang.Class` that return `Annotation`/`Annotation[]`/`Annotation[][]`/`AnnotatedType`. Add the case to the `reflective-annotation-read` fixture.

## Rejected
- (none)

## Duplicates
- AUD-1 → (related to BRK-3, but not a full duplicate). AUD-1 concerns the known *indirect*-call gap and stays MINOR; the BRK-3 fix does not need to address it.

## MINOR (optional for the builder)
- BRK-4 [MINOR] Build.java:42 `-Xlint:all,-deprecation` + `-Werror` — `@Deprecated(forRemoval = true)` on a method used in the same module makes the build red (evidence: deprrem.log). AC3 only covers plain `@Deprecated`, so this stays MINOR. Either add `-removal` or document that this behaviour is intended.
- BRK-5 [MINOR] FileCheck.java:16-21 — Bazel's `BUILD`, `Makefile`, `.zip` and `.jmod` are not flagged (bazel2.log). FileCheck claims Bazel coverage, so at least add `BUILD`/`BUILD.bazel`/`WORKSPACE`/`MODULE.bazel`.
- BRK-6 [MINOR] BytecodeCheck.java:56 / GuardrailsTool.java:76 — A malformed `.class` prints "Bad magic number" with no path, followed by a misleading usage message (bazel.log). The build is still red, but the error message is weak. Report the file path as an analysis error.
- BRK-7 [MINOR] Guardrails.java:62-69 — Two loose compact-source files with the same class name in different directories cause "duplicate class" and a red build (dupe.log). This hits future compact-source scripts.
- AUD-1 [MINOR] SourceCheck — Indirect reflection (MethodHandles, string-based lookup) is not detected. This is already documented as a known gap; keep it in PROGRESS.md.
- MNT-1 [MINOR] SourceCheck.java:244-273 — The hand-rolled lexer for the `@interface` line is hard to maintain.
- MNT-2 [MINOR] Fixtures.java:15-95 — Hard-coded file:line expectations must be renumbered whenever a fixture changes. Use in-fixture markers or add a header comment.
- MNT-3 [MINOR] Build.java:119 / DependencyCheck — The requires rule is implemented twice. Cross-reference the two copies with comments.
- MNT-4 [MINOR] SelfTest.java:28-30,141-158 — The regex coupling to the REGLER.md table format should be mentioned in the failure message and in ARCHITECTURE.md.
- MNT-5 [MINOR] GuardrailsTool.java:136-156 — The WriterOutputStream bridge is fragile. Use PrintWriter internally, or document why it only decodes on flush.
