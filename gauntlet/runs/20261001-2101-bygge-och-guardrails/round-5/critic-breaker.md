```
VERDICT: FAIL
FINDINGS:
- id: BRK-1
  severity: MAJOR
  where: framework/modules/guardrails/src/spola/guardrails/LauncherCheck.java:110-112 (analyze at parsedAt) and :258-262 (sourceOptions)
  problem: A `.md` document is analysed once, at the newest release that parses it, with `--source N` against the JDK 25 API. The launcher compiles `java --source N` with the release-N API (`--release` semantics), so the same text can bind to different methods at an older release, and a reflective annotation read passes with a green build.
  evidence: |
    Scratch: /workspace/gauntlet/runs/20261001-2101-bygge-och-guardrails/round-5/scratch-breaker/fw3/docs/release.md
      @Deprecated
      class Main {
          static Class<?> getFirst() { return Main.class; }
          static final class U { boolean isAnnotationPresent(Class<?> c) { return false; } }
          static class Inner extends java.util.ArrayList<U> {
              boolean test() { return getFirst().isAnnotationPresent(Deprecated.class); }
          }
          public static void main(String[] a) { Inner i = new Inner(); i.add(new U()); System.out.println("reflective read of @Deprecated: " + i.test()); }
      }
    At 25, getFirst() binds to ArrayList.getFirst() (added in 21), which returns U, so isAnnotationPresent is the user's own method. At release 8, ArrayList has no getFirst, so the call binds to Main.getFirst() and then to Class.isAnnotationPresent.
    $ java --source 25 docs/release.md   -> reflective read of @Deprecated: false
    $ java --source 8  docs/release.md   -> reflective read of @Deprecated: true
    $ java Build.java -> exit=0
      guardrails: source       4 other files: 0 not allowed, 4 documents (1 parse as Java)
      guardrails: no violations ... BUILD OK
    Separately: `java --source 8` on a file using `Module` / `getModule()` fails with "cannot find symbol". This shows the launcher really compiles against the release-8 API, while SourceCheck at `--source 8` sees the JDK 25 API.
  fix_hint: Analyse every UTF-8 reading at every release that parses it, with `--release N` (not `--source N`) so it matches the launcher. Or reject any `.md` that parses as Java at all.

- id: BRK-2
  severity: MAJOR
  where: framework/modules/guardrails/src/spola/guardrails/Fixture.java:28 (FILES -> only TreeSpec reads tree.txt) together with GuardrailsTool.java:96-100 (listed fixture dirs excluded from the main check)
  problem: A FILES fixture's `tree.txt` can also be a runnable source-launcher program that declares a custom `@interface`, uses `@Retention` and reads annotations reflectively. The main check never sees it (fixture dir excluded), and the self-test only feeds it to TreeSpec, so the build stays green. That gets past REGLER §2.
  evidence: |
    In a copy (scratch-breaker/fw), I rewrote modules/guardrails/fixtures/foreign-jar/tree.txt so that:
      line 1: #!/usr/bin/env -S java --source 25                      (TreeSpec: comment)
      line 2: @Evil class Main { public static void main(String[] a) { System.out.println("ran with Evil: " + Main.class.isAnnotationPresent(Evil.class)); } } /*
      ...all original tree.txt lines unchanged (inside the Java block comment)...
      last:   */ @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) @interface Evil {}
    TreeSpec turns lines 2 and last into harmless empty files with odd names. FileCheck has nothing to report for them.
    $ java --source 25 tree.txt   -> ran with Evil: true
    $ java Build.java             -> exit=0, "guardrails: no violations", "fixture ok   foreign-jar (14 expected violations)", BUILD OK
  fix_hint: Run LauncherCheck on every fixture file that is not a fixture input of kind SOURCE, BYTECODE or DEPENDENCIES (e.g. tree.txt), with the result expected to be empty. Or require that tree.txt does not parse as Java in any reading or release.

- id: BRK-3
  severity: MINOR
  where: framework/modules/guardrails/src/spola/guardrails/LauncherCheck.java:96-103 (non-documents reported by name only), applied to fixtures/launcher-source/tools/*
  problem: In the launcher-source fixture, every non-`.md` file is expected to produce exactly one name-based UNCHECKABLE_SOURCE violation whatever it contains. Its content can therefore be replaced by any executable Java with unlimited new forbidden annotations or reflection, and neither the self-test nor the main check notices.
  evidence: |
    In a copy (scratch-breaker/fw2), I replaced fixtures/launcher-source/tools/wire with a #! program that declares a RUNTIME @interface Evil and calls Main.class.isAnnotationPresent(Evil.class).
    $ ./wire -> evil true
    $ java Build.java -> exit=0, "fixture ok   launcher-source (17 expected violations)", BUILD OK
  fix_hint: Also pin the content of non-document fixture inputs, e.g. expect their source-analysis violations, or compare them against a checksum listed in Fixtures.java.
```

Scratch copies used: /workspace/gauntlet/runs/20261001-2101-bygge-och-guardrails/round-5/scratch-breaker/{fw,fw2,fw3}. The build logs are b1.log, b2.log and b3.log in the same directory.
