```
VERDICT: FAIL
FINDINGS:
- id: BRK-1
  severity: MAJOR
  where: framework/modules/guardrails/src/spola/guardrails/FileTree.java:36 (visitFile: `attrs.isRegularFile()`; walkFileTree does not follow links) and Guardrails.java:85 (file check)
  problem: A jar that is a symbolic link, or a jar inside a symlinked directory, is never seen by the file check, so a vendored jar under framework/ still gives a green build.
  evidence: In a copy of framework/, `mkdir lib; ln -s /tmp/real.jar lib/x.jar; java Build.java` gives "guardrails: no violations ... BUILD OK", exit 0. `ln -s /tmp/vendor lib` with /tmp/vendor/guava.jar inside gives "BUILD OK", exit 0. An empty regular file lib/x.jar gives RED, so only the symlink form gets past the check. Logs are in scratch-breaker/symjar.log and symdir.log.
  fix_hint: Report symlinks too. For example, treat `attrs.isSymbolicLink()` as a violation, or check files/dirs found with `Files.walk` with FOLLOW_LINKS by name, so that `.jar` symlinks and linked dirs are flagged.

- id: BRK-2
  severity: MAJOR
  where: FileTree.java:36 (source check file list) vs Build.java compile() (`Files.walk(...)` returns symlinked files and javac compiles them)
  problem: A symlinked .java file inside a module is compiled into the jar but skipped by the source check, so SOURCE-retention annotations in it (which never reach bytecode) get past every guardrail.
  evidence: The file /tmp/evil/spola/guardrails/Evil.java contains `@javax.annotation.processing.Generated("x") final class Evil {}`. I linked it with `ln -s` to modules/guardrails/src/spola/guardrails/Evil.java and added `requires static java.compiler;`. Then `java Build.java` gives "guardrails: source 16 .java files" (Evil is not counted), "bytecode 23 .class files" (Evil.class was compiled), "no violations, BUILD OK", exit 0. Log: scratch-breaker/symsrc.log.
  fix_hint: Build the source-check file list exactly like the compile list (same walk, same symlink handling), or refuse symlinks in module source trees.

- id: BRK-3
  severity: MAJOR
  where: framework/modules/guardrails/src/spola/guardrails/SourceCheck.java:47-50 (ANNOTATION_READERS)
  problem: The check misses reflective annotation reads through `Executable.getParameterAnnotations()` (Method and Constructor), which breaks REGLER.md §2 "att läsa annoteringar via reflektion".
  evidence: I added modules/guardrails/src/spola/guardrails/Peek.java with `static int n(java.lang.reflect.Method m) { return m.getParameterAnnotations()[0].length; }`. `java Build.java` gives "guardrails: no violations ... BUILD OK", exit 0. Log: scratch-breaker/paramrefl.log.
  fix_hint: Add getParameterAnnotations, and consider Method.getDefaultValue, to the reader set. Add a fixture line for it.

- id: BRK-4
  severity: MINOR
  where: framework/Build.java JAVAC_OPTIONS (`-Xlint:all,-deprecation` + `-Werror`)
  problem: `@Deprecated(forRemoval = true)`, an allowed annotation, on a method that is used in the same module makes the build red, because the `removal` lint is still on and -Werror turns its warning into an error.
  evidence: Added `@Deprecated(forRemoval = true)` above `static boolean isAllowedModule` in Allowlist.java. Output: "warning: [removal] isAllowedModule ... 1 error ... BUILD FAILED: compilation of spola.guardrails failed", exit 1. Plain `@Deprecated` stays green. Log: scratch-breaker/deprrem.log.
  fix_hint: Also disable `removal` (`-Xlint:all,-deprecation,-removal`), or document that forRemoval is meant to break the build.

- id: BRK-5
  severity: MINOR
  where: framework/modules/guardrails/src/spola/guardrails/FileCheck.java:16-21
  problem: FileCheck says it covers Bazel, but it misses Bazel's main file name `BUILD`. Other third-party build/vendor artifacts also get through: `Makefile`, `.zip`, `.jmod`.
  evidence: `touch BUILD Makefile; mkdir lib; touch lib/thing.zip lib/x.jmod; java Build.java` gives "BUILD OK", exit 0 (scratch-breaker/bazel2.log).
  fix_hint: Add "build" (Bazel) and other common names to BUILD_FILES, and consider flagging all archive extensions outside out/.

- id: BRK-6
  severity: MINOR
  where: framework/modules/guardrails/src/spola/guardrails/BytecodeCheck.java:56 (`ClassFile.of().parse(bytes)` with no file context) and GuardrailsTool.java:76 (IllegalArgumentException is handled as a usage error)
  problem: A malformed .class file under framework/ gives an error with no file name, plus a misleading usage message. This breaks the requirement that every failure names its file.
  evidence: `mkdir lib; touch lib/Foo.class; java Build.java` prints "guardrails: Bad magic number", then "usage: check --root DIR ...", "BUILD FAILED", with no path (scratch-breaker/bazel.log).
  fix_hint: Catch the parse exception per file and report it, or rethrow it with the relative path as an analysis error, not as a usage error.

- id: BRK-7
  severity: MINOR
  where: framework/modules/guardrails/src/spola/guardrails/Guardrails.java:62-69 (all loose .java files analysed in one javac task)
  problem: Two independent, valid, loose source files with the same class name (for example two compact-source scripts called Run.java in different directories) make the build red.
  evidence: `mkdir -p tools/a tools/b`, then put `void main(){IO.println(1);}` in tools/a/Run.java and the same in tools/b/Run.java. Output: "guardrails: source check cannot analyse the sources: tools/b/Run.java:1: duplicate class: Run", "BUILD FAILED" (scratch-breaker/dupe.log).
  fix_hint: Analyse each loose file, or each launcher-style script, in its own task.
```

What I checked and found working: baseline green; the six acceptance-criteria experiments came out as specified. Plain `@Deprecated` on a method used from other classes stays green, including on a public method. A custom `@interface` gives red with file:line. `requires java.desktop` is green; `requires foo.bar` is red with file:line. An empty lib/x.jar is red. Changing a fixture's expected violation (source-annotation, foreign-jar) gives red.

Logs and experiment copies are in /workspace/gauntlet/runs/20261001-2101-bygge-och-guardrails/round-1/scratch-breaker/.
