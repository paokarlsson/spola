# Round 1 — verdict: PASS

Judge's verification: I ran the baseline `java Build.java` in a fresh copy of `framework/` with `out/` removed. It printed `BUILD OK`, exit 0, and all 10 fixtures were ok. I reran the breaker's BRK-1 (`probes/lm.sh`) and BRK-2 (`probes/cf.sh`) in fresh /tmp copies, and both gave `guardrails: no violations`, exit 0, so both reproduce. I deleted the copies afterwards. The auditor checked criteria 1–10 and found them all met. I found nothing that contradicts that, and no code in `framework/` breaks REGLER.md.

## Upheld findings (the builder must address these)
- (none)

## Rejected
- MNT-2: The comma at the end of a `<li>` matches the other items in the same list, which also end with commas. "Every `.java` file is parsed and attributed" is still accurate. The same item says separately that documents are "reported, never analysed". Nothing is wrong here.

## Duplicates
- MNT-5 → AUD-1 (hard-coded expected line numbers, a known gap in build.md)

## MINOR (optional for the builder)
- BRK-1 [MINOR, downgraded from MAJOR] SourceCheck.java:256-258. Annotation reads through `javax.lang.model.AnnotatedConstruct` (`getAnnotation`, `getAnnotationMirrors`) are not flagged. I reproduced it: exit 0. Why I downgraded it:
  - The spec and mission require three checks: source annotations and declarations, bytecode, and dependencies. Detecting reflective reads is an extra that the builder added. PROGRESS.md and ARCHITECTURE.md honestly limit it to `java.base` methods. I may not widen the contract (judge rule 7).
  - `javax.lang.model` is the compile-time language model. It is not runtime reflection in the sense of REGLER §2, and guardrails itself has to use it to enforce §2.
  - Only the six allowlisted JDK annotations can exist. Annotation-driven logic in a code generator would contradict the whole design of Spola, so a good-faith developer is unlikely to trigger this.
  - No code in `framework/` does this today. If code ever did, it would be a §2 BLOCKER regardless of the check.
  - fix_hint: List the API as a known gap in PROGRESS.md, or extend the return-type criterion to `AnnotatedConstruct`/`AnnotationMirror` and add a fixture case.
- BRK-2 [MINOR, downgraded from MAJOR] SourceCheck.java:256-273. Reading `RuntimeVisibleAnnotations` with `java.lang.classfile` is not flagged. I reproduced it: exit 0. The same reasons apply as for BRK-1. Parsing class bytes is not reflection under §2, it sits outside the scope the builder documents, and guardrails' own `BytecodeCheck` legitimately reads annotations the same way. Forbidding it would take a named exception, which is a design decision and not a hole in a required check.
  - fix_hint: List it as a known gap, or flag classfile annotation types outside `BytecodeCheck` and add a fixture case.
- BRK-3 [MINOR] LauncherCheck.java:135-179. Every `.md` file that contains `;` or `{` is parsed in about 170 charsets × releases 25..8. The breaker's log shows 40 documents of 16 KB each take 30 s, against a 3.8 s baseline. This does not break anything today, but it will hurt as documentation grows. Incremental builds and caching are out of scope, but a cheap pre-filter is not.
  - fix_hint: Pre-filter readings that contain no top-level declaration token, or deduplicate identical decoded texts before running javac.
- AUD-1 [MINOR] Fixtures.java. The expected line numbers are hard-coded, which build.md already documents as a known gap.
  - fix_hint: Anchor them to marker comments.
- MNT-1 [MINOR] Guardrails.java:86-124. `analyzeSources` does four jobs.
  - fix_hint: Split out the guard for an unlisted module-info, and call `checkOtherFiles` from `check()`.
- MNT-3 [MINOR] `LauncherCheck.CURRENT_RELEASE` and `Fixtures.LATEST` duplicate `Runtime.version().feature()`.
  - fix_hint: Share one constant.
- MNT-4 [MINOR] LauncherCheck.java:187-205. The `return "\n"` that keeps line numbers aligned has no comment.
  - fix_hint: Add a one-line comment.
