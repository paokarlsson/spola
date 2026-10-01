```
VERDICT: FAIL
FINDINGS:
- id: BRK-1
  severity: MAJOR
  where: framework/modules/guardrails/src/spola/guardrails/SourceCheck.java:256-258 (readsAnnotations returns false unless the owner is in java.base)
  problem: Reading annotations through the standard javax.lang.model API is not detected. `AnnotatedConstruct.getAnnotation`, `getAnnotationsByType` and `getAnnotationMirrors` live in java.compiler, which spola.guardrails already requires, so annotation-driven logic in a code generator or analysis tool passes with a green build.
  evidence: |
    Probe in a copy of framework/ (scratch-breaker/p-lm, script probes/lm.sh) adds modules/guardrails/src/spola/guardrails/Meta.java:
      static boolean skip(Element element) {
          return element.getAnnotation(Deprecated.class) != null
                  || !element.getAnnotationMirrors().isEmpty();
      }
    `java Build.java` gives "compiled spola.guardrails (18 files) ... guardrails: source 19 .java files ... guardrails: no violations ... BUILD OK", EXIT 0.
    Control probe (p-ctl) with `type.getAnnotation(Deprecated.class)` on a Class gives "Meta.java:9: ANNOTATION_REFLECTION ... java.lang.Class.getAnnotation", BUILD RED. So the rule is live, and only the java.base restriction lets the javax.lang.model read through.
  fix_hint: Also apply the return-type criterion (Annotation, AnnotationMirror, or arrays and lists of them) to javax.lang.model.AnnotatedConstruct and its subtypes, and add the case to the reflective-annotation-read fixture.

- id: BRK-2
  severity: MAJOR
  where: framework/modules/guardrails/src/spola/guardrails/SourceCheck.java:256-273
  problem: Reading a class's annotations at runtime through java.lang.classfile is not detected. This is how Spring's MetadataReader works (it reads class bytes with ASM), so a developer writing a Spring replacement could plausibly do it to drive logic. java.lang.classfile.Annotation is not a subtype of java.lang.annotation.Annotation, so the return-type check misses it.
  evidence: |
    Probe (scratch-breaker/p-cf, script probes/cf.sh) adds Meta.java:
      try (var in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
          var model = ClassFile.of().parse(in.readAllBytes());
          return model.findAttribute(Attributes.runtimeVisibleAnnotations())
                  .map(a -> a.annotations().stream()
                          .anyMatch(x -> x.className().stringValue().equals("Ljava/lang/Deprecated;")))
                  .orElse(false);
      }
    `java Build.java` gives "guardrails: no violations ... BUILD OK", EXIT 0.
  fix_hint: Treat calls that return java.lang.classfile annotation types (Annotation, TypeAnnotation, Runtime*AnnotationsAttribute) as annotation reads. Exempt only guardrails' own BytecodeCheck by an explicit, named exception, and add a fixture case.

- id: BRK-3
  severity: MINOR
  where: framework/modules/guardrails/src/spola/guardrails/LauncherCheck.java:135-179
  problem: Every .md file containing `;` or `{` is decoded in each JDK charset (about 170) and parsed at every release from 25 down to 8. Build time therefore grows by about 0.65 s per ordinary 16 KB document, which hurts once later missions add documentation.
  evidence: |
    Probe p-docs: 40 copies of ARCHITECTURE.md (16 KB, one line `rev N; {x}` appended) in framework/docs/.
    `java Build.java` gives "43 documents (0 parse as Java) ... BUILD OK in 30229 ms" (user time 1m29s). The baseline with 3 documents is "BUILD OK in 3778 ms".
  fix_hint: Drop readings that cannot parse before running javac, for example when the text has no top-level `class`/`interface`/`record`/`enum`/`void`/`module`/`import`/`package` token in any reading. Alternatively, parse a document's readings once per release group, or stop early on the first lexer error for that charset.
```

These probes passed and are not findings:
- A custom `@interface` in a second listed module (`spola.demo`) is reported with file and line in both the source check and the bytecode check.
- Multi-module dependency ordering and packaging work.
- A 4 MB `.md` file does not exhaust memory (13 s build).
- The direct `Class.getAnnotation` read is caught (control probe).

All probes are in `/workspace/gauntlet/runs/20261001-2308-bygge-och-guardrails/round-1/scratch-breaker/`: the scripts in `probes/*.sh`, the build logs in `p-*.log` and the mutated copies in `p-*/`. I did not change any of the builder's files.
