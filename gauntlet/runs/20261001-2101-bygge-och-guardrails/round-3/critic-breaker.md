```
VERDICT: FAIL
FINDINGS:
- id: BRK-1
  severity: MAJOR
  where: framework/modules/guardrails/src/spola/guardrails/Guardrails.java:88-91 (the source check only selects files ending in ".java")
  problem: A Java program saved without a .java extension (a shebang script for the JDK source launcher) is runnable code inside framework/, but the source check never looks at it, so it can declare an @interface, use a forbidden annotation and read annotations reflectively while the build stays green.
  evidence: Copied framework to round-3/scratch-breaker/fw-sh and added an executable file tools/wire:
      #!/usr/bin/env -S java --source 25
      import java.lang.annotation.*;
      @Retention(RetentionPolicy.RUNTIME) @interface Inject {}
      class Service { @Inject Object dep; }
      void main() throws Exception { ... f.isAnnotationPresent(Inject.class) ... }
    `java Build.java` -> exit=0, "BUILD OK in 1684 ms" (no violations reported).
    `./tools/wire` -> "inject present: true", exit 0.
    This breaks all three parts of REGLER §2 (custom @interface, a non-allowlisted annotation, a reflective annotation read) in a program that is in framework/ and runs, and Build.java does not catch it.
  fix_hint: Pick source files by content as well as by name, the same way class files are now picked by CAFEBABE. Treat any file whose first line is a `#!...java` shebang (and `*.jsh`) as Java source and run it through SourceCheck. Or flag such files as a violation.

- id: BRK-2
  severity: MAJOR
  where: framework/modules/guardrails/src/spola/guardrails/FileCheck.java:78 and :113 (holdsJavaCode only understands zip files; it treats ZipException as "not a zip, so allowed")
  problem: A vendored third-party jar passes the FOREIGN_JAR check as soon as it is compressed with gzip, or put in a .tar.gz, or put in any container that java.util.zip.ZipFile cannot open. This is the same evasion pattern as last round's BRK-2 (renamed jar), still open.
  evidence: Built a "third-party" jar acme.jar holding com/acme/Inject.class (a RUNTIME @interface) plus a manifest. For each case below I made a separate copy of framework/ in round-3/scratch-breaker/:
      fw-gz:  lib/acme.jar.gz  (gzip -c acme.jar)          -> java Build.java exit=0, BUILD OK
      fw-tgz: lib/deps.tgz     (tar czf deps.tgz acme.jar) -> java Build.java exit=0, BUILD OK
    The jar comes back unchanged: `gunzip -c lib/acme.jar.gz | jar tf` lists META-INF/MANIFEST.MF and com/acme/Inject.class.
    Also, a zip whose first entry uses an unsupported method (BZip2) makes ZipFile throw "invalid CEN header (bad compression method: 12)". Line 113 then counts the whole file as harmless: fw-bz with lib/deps.zip -> exit=0. `unzip -lv` still lists Hello.class and Hello$Inject.class inside it.
    REGLER §1 forbids vendored jar files, and AC2 requires no .jar outside out/.
  fix_hint: Do not treat an unreadable or unknown archive as clean. Unwrap gzip (GZIPInputStream, magic 1F 8B) and tar (ustar magic at offset 257) and scan what is inside. Report as FOREIGN_JAR any file with a zip signature (PK\3\4 / PK\5\6) that ZipFile rejects, instead of skipping it.

- id: BRK-3
  severity: MINOR
  where: framework/modules/guardrails/src/spola/guardrails/FileCheck.java BUILD_FILES / BUILD_DIRECTORIES
  problem: The build-file check still misses common files from the tools it claims to cover (sbt, Gradle, Bazel) and from other JVM build tools (Leiningen, Clojure deps).
  evidence: Copy fw-bf with project/plugins.sbt, project/build.properties, gradle/deps.gradle (a Gradle script plugin, applied with `apply from:`), bazel/deps.bzl, project.clj and deps.edn -> java Build.java exit=0, "BUILD OK in 1746 ms".
  fix_hint: Match by extension (*.sbt, *.gradle, *.gradle.kts, *.bzl) and add project.clj and deps.edn. Optionally flag sbt's project/build.properties.
```

What I also tried, all of which held up:
- **AC3:** `@Deprecated` (including `forRemoval=true`) on `Violation.format()`, on `GuardrailsTool.name()` (an override), on `main`, and on `Allowlist.isAllowedModule` (used across classes). The build stays green every time.
- **AC2:** The repo-wide scan finds jar, Makefile and BUILD files only in round-1 and round-2 `scratch-*` directories, which are gitignored.
- **Plain tar:** An uncompressed tar of a jar happened to be flagged. `ZipFile` found the inner jar's end-of-archive record near the end of the tar.

Experiment files are in `/workspace/gauntlet/runs/20261001-2101-bygge-och-guardrails/round-3/scratch-breaker/` (fw-sh, fw-gz, fw-tgz, fw-bz, fw-bf, plus logs *.log and tools/Craft.java).
