```
VERDICT: FAIL
FINDINGS:
- id: BRK-1
  severity: MAJOR
  where: framework/Build.java:229-230 (`--exclude modules/guardrails/fixtures`) together with framework/modules/guardrails/src/spola/guardrails/Guardrails.java:619 (loose sources skip `notChecked`) and SelfTest.checkFixtureDirectoriesAreListed (only checks directories)
  problem: The whole fixtures tree is left out of the source and bytecode checks, not just the fixture directories listed in Fixtures.ALL. Any `.java` file placed directly in `fixtures/` is never checked, yet the JDK 25 multi-file source launcher that runs `java Build.java` can still load and run it, because `framework/` is its source root and `modules/guardrails/fixtures` is a valid package name. A custom `@interface`, a disallowed annotation and a reflective annotation read can therefore all run during a green build.
  evidence: |
    I copied framework/ to round-2/scratch-breaker/fw7 and added modules/guardrails/fixtures/Evil.java. It has `package modules.guardrails.fixtures;`, a nested `@Retention(RUNTIME) @interface Inject {}`, an `@Inject Object dep;` field, and run() calls `getDeclaredField("dep").isAnnotationPresent(Inject.class)`. I added one statement at the end of Build.build(): `((Runnable) Class.forName("modules.guardrails.fixtures.Evil").getConstructor().newInstance()).run();`
    $ java Build.java  →
      guardrails: source       16 .java files
      guardrails: no violations
      ...
      Evil: field annotated with @Inject? true
      BUILD OK in 1579 ms   EXIT=0
  fix_hint: Exclude only the directories named in Fixtures.ALL (resolved below fixtures/), not the fixtures root. Report any other file under fixtures/, or at least any `.java`/`.class` file outside a listed fixture directory. Alternatively, reject fixture paths that form a valid Java package path.

- id: BRK-2
  severity: MAJOR
  where: framework/modules/guardrails/src/spola/guardrails/FileCheck.java:812,829 (ARCHIVES is matched by name only) and Guardrails.java:649 (bytecode check reads `.class` files only, never archive contents)
  problem: Third-party compiled code can be vendored and used without being detected. A jar renamed to `.zip` or given no extension, or an exploded class directory, passes the file check. Archive contents are never bytecode-checked, so annotated third-party classes (including an `@interface`) inside such an archive also pass. This is the same REGLER §1 bypass as round-1 BRK-1, done by renaming instead of linking.
  evidence: |
    In scratch-breaker/fw1 I copied a real jar (org.thirdparty.Lib) to lib/thirdparty.zip and lib/thirdparty-1.0, and copied its classes to lib/org/thirdparty/Lib.class. Build.java gained `new URLClassLoader(new URL[]{Path.of("lib/thirdparty.zip").toUri().toURL()}).loadClass("org.thirdparty.Lib").getMethod("hi").invoke(null)`.
    $ java Build.java → "guardrails: no violations", "vendored says: third party", BUILD OK, EXIT=0
    In scratch-breaker/fw5 I added lib/deps.zip, a jar containing a compiled RUNTIME `@interface Inject` and a class annotated with `@Inject`. I also put the same .class files into fixtures/bytecode-annotation/ (excluded from the bytecode check and not used by the self-test).
    $ java Build.java → "guardrails: no violations", BUILD OK, EXIT=0
  fix_hint: Detect archives by content: a file that starts with the zip magic `PK\3\4` outside out/ counts as FOREIGN_JAR, whatever its name. Report every `.class` file outside out/. Optionally, bytecode-check the entries of any archive you find.

- id: BRK-3
  severity: MINOR
  where: framework/modules/guardrails/src/spola/guardrails/FileCheck.java:801-809
  problem: The build-file denylist still misses files from common JVM build tools: Mill (`build.mill`, `build.sc`), Buck (`BUCK`), Pants (`pants.toml`), Maven's `.mvn/extensions.xml` and Gradle's `gradle/libs.versions.toml`. AC2 says "andra byggverktyg" (other build tools).
  evidence: In scratch-breaker/fw3, `touch build.mill build.sc BUCK pants.toml .mvn/extensions.xml gradle/libs.versions.toml` followed by `java Build.java` gives "guardrails: no violations", EXIT=0.
  fix_hint: Add these names. Also consider flagging the `.mvn/` and `.gradle/` directories and `*.bzl` files.
```

The breaker scratch directory is /workspace/gauntlet/runs/20261001-2101-bygge-och-guardrails/round-2/scratch-breaker/. The experiments are in fw1 to fw7, each with a matching log file (fw1.log, fw1b.log, fw3.log, fw5.log, fw7.log).

These checks held up, and I found no defect in them:
- **AC3:** `@Deprecated` on the public `Guardrails.check` still gives a green build.
- **Package annotations:** a `package-info.java` with `@Generated` is reported with its line, so the build goes red.
- **Malformed class files:** truncated class files give a red build with the file path in the message.
