```
VERDICT: FAIL
FINDINGS:
- id: BRK-1
  severity: MAJOR
  where: framework/modules/guardrails/src/spola/guardrails/SourceCheck.java:303-307 (shebang line blanked up to the first '\n' only)
  problem: The check drops everything up to the first '\n', but the source launcher ends the `#!` line at the first '\r' (BufferedReader.readLine). A launcher script that uses CR line endings therefore reaches javac as an empty file, gets zero violations and counts as clean, while `java --source 25 FILE` still runs all of it.
  evidence: round-4/scratch-breaker/t/wire was written with
      printf '#!/usr/bin/env -S java --source 25\rimport java.lang.annotation.*;\r@Retention(RetentionPolicy.RUNTIME) @interface Inject {}\rclass Svc { @Inject Object dep; }\rvoid main() throws Exception { System.out.println("inject present: " + Svc.class.getDeclaredField("dep").isAnnotationPresent(Inject.class)); }\r'
    It was copied to fw-cr/tools/wire in a copy of framework/.
    `java Build.java` -> exit=0, "guardrails: source 1 of 4 other files are Java source", "BUILD OK in 2149 ms" (no violations).
    `cd fw-cr/tools && java --source 25 wire` -> "inject present: true".
    That is a custom @interface, a non-allowlisted annotation and isAnnotationPresent in a file the JDK runs directly. This is the same rule break as round-3 BRK-1, still open through a different line terminator. The same applies inside fixtures/launcher-source/, where the LAUNCHER self-test would also see an empty file with no violations.
  fix_hint: Strip the first line the way the launcher does: end it at '\r', '\n' or "\r\n". Add a fixture file with CR-only line endings.

- id: BRK-2
  severity: MAJOR
  where: SourceCheck.java:293-302 (strict UTF-8 decode; a failure on a non-shebang file means Optional.empty(), i.e. skipped)
  problem: The launcher decodes source with Charset.defaultCharset() and replaces bad bytes, so a non-shebang file with a single invalid UTF-8 byte (e.g. Latin-1 `é` in a comment) still runs. Guardrails skips that same file as "not Java", so the check fails open.
  evidence: Two files were put in fw-enc/tools/:
      gen       = "// caf\xe9\n" + compact source with @Retention, @interface Inject, @Inject field, isAnnotationPresent
      gen2.txt  = public class Gen + "// caf\xe9" + @interface Inject + @Inject field
    `java Build.java` -> exit=0, "0 of 5 other files are Java source", "BUILD OK in 2071 ms".
    `java --source 25 gen` -> "inject present: true". `java --source 25 gen2.txt` -> "inject present: true".
  fix_hint: Do not skip on a decoding error. Decode the way the launcher does (UTF-8 with REPLACE, or also try ISO-8859-1). If the result parses, analyse it. At minimum, report a file that is not UTF-8 but parses after lenient decoding as UNCHECKABLE_SOURCE.

- id: BRK-3
  severity: MAJOR
  where: SourceCheck.java:309-316 (parse is tried only with --release 25; a parse failure on a non-shebang file means it is skipped)
  problem: The launcher accepts `--source N` for every N the JDK supports. A file that parses at an older release but not at 25 (e.g. `_` as a method name) runs, yet guardrails skips it. build.md lists this as a "known gap", but it falls inside the judge's binding round-3 scope: anything the source launcher runs directly is in scope, and a bypass is MAJOR.
  evidence: fw-old/tools/old.txt =
      public class Old { static void _() {} public static void main(String[] a) throws Exception { ...isAnnotationPresent(Inject.class)... } }
      @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) @interface Inject {}
      class Svc { @Inject Object dep; }
    `java Build.java` -> exit=0, "0 of 4 other files are Java source", "BUILD OK in 2035 ms".
    `java --source 8 old.txt` -> "inject present: true" (only a warning about '_').
  fix_hint: When the parse fails at 25, retry at each lower supported release (javax.lang.model.SourceVersion / --release 8..24). If any release parses, analyse at that release, or fail closed with UNCHECKABLE_SOURCE.

- id: BRK-4
  severity: MINOR
  where: SourceCheck.java:280 (jshell scripts recognised by the `.jsh` extension only); Guardrails.java:22-23 Javadoc says "jshell scripts ... are reported"
  problem: jshell runs a script with any file name, so a jshell script named `.txt` (or with no extension) declares and reads annotations, and nothing reports it.
  evidence: fw-jsh/tools/setup.txt holds an import, `@Retention(RUNTIME) @interface Inject {}`, `class Svc { @Inject Object dep; }`, a top-level println(...isAnnotationPresent(Inject.class)) and `/exit`.
    `java Build.java` -> exit=0, "0 of 4 other files are Java source", BUILD OK.
    `jshell t/setup.txt` -> "inject present: true".
    Graded MINOR because detecting jshell scripts by content is open-ended. Still, the Javadoc claim is false, and the case belongs in the known gaps.
  fix_hint: Either detect jshell input by content (jdk.jshell SourceCodeAnalysis on text files that fail the compilation-unit parse, flagging any snippet that is a complete declaration or statement), or document it in PROGRESS.md as a gap enforced by review and correct the Javadoc.
```

I left four things alone because they held up. `out/` is wiped at the start of every build (Build.java:79), so a file planted there does not survive. A `.java` file with bad UTF-8 throws and turns the build red. The zip fail-closed handling is as described in build.md. Files in the fixtures directory are covered by the layout check, apart from the CR-only case in BRK-1.

All experiments are in `/workspace/gauntlet/runs/20261001-2101-bygge-och-guardrails/round-4/scratch-breaker/`: the framework copies `fw-cr`, `fw-enc`, `fw-old` and `fw-jsh`, their logs `cr.log`, `enc.log`, `old.log` and `jsh.log`, and the inputs in `t/`.
