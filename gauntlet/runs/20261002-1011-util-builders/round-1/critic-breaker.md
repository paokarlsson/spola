```
VERDICT: PASS
FINDINGS:
- id: BRK-1
  severity: MINOR
  where: framework/modules/codegen/src/spola/codegen/RecordReader.java:203-212 (access only checks the record's enclosing chain) and TypePrinter.first (lines ~355-372)
  problem: If a component type is a private member type of an enclosing type, the generator qualifies it and writes a file that does not compile. It should reject this with a clear error that names the file and the record.
  evidence: scratch-breaker/t/src/p/Outer.java = `public class Outer { private enum Kind { A, B } record Item(Kind kind, int n) implements ItemWith {} }`. Generating `Outer.Item` prints "wrote ItemWith.java". Then `javac -Xlint:all -Werror` fails with "src/p/ItemWith.java:39: error: Kind has private access in Outer" (three errors, at lines 39, 58 and 75 of the generated file).
  fix_hint: When a component type or bound resolves to a member type in the scopes, look up its modifiers. If it is private, throw CodegenException ("component type Kind is private in Outer; the generated interface cannot name it").

- id: BRK-2
  severity: MINOR
  where: framework/modules/codegen/src/spola/codegen/RecordReader.java:265-277 (imports)
  problem: On-demand imports and `import module` imports are always copied, even when nothing in them is used. A record file with `import spola.util.*;` or `import module spola.config;` therefore gets a generated file that imports spola.* from another module. That breaks AC2 and "generated code depends only on the JDK and the record". The pruning the builder added for this exact reason is incomplete.
  evidence: From the code: the branch `simple.equals("*") || usedNames.contains(simple)` and `importTree.isModule()` keep the import with no usage check. No current demo file uses on-demand spola imports (grep finds none), so it is latent.
  fix_hint: Drop on-demand imports and module imports from spola.* (or from anything outside java.*/javax.*/jdk.*) when no component type needs them. At minimum, document the limitation.

- id: BRK-3
  severity: MINOR
  where: framework/Build.java checkGeneratedFiles() together with GeneratedFiles.inspect
  problem: A plain syntax error in a record file that has a generated sibling is reported as "generated files do not match their records ... regenerate them with `java Build.java codegen`". Running that command cannot fix it, and only the first parse error is shown, instead of javac's normal diagnostics.
  evidence: In a copy of framework/, removing the `;` after `var sku = matcher.group(1)` in OrderLine.java and running `java Build.java` prints "OrderLineWith.java: cannot be regenerated: ... the file does not parse: line 41: ';' expected", then "BUILD FAILED: generated files do not match their records (see above); regenerate them with `java Build.java codegen`".
  fix_hint: When the record source does not parse, skip the check for that file (let javac report the error) or give a separate message that does not point to codegen.
```

What I tried that held up:
- **AC7, rename:** renaming `sku` to `article` turns the build red with exit 1 before javac runs. The message names the file path and `java Build.java codegen`.
- **AC8:** `java Build.java codegen` on a clean copy reports "2 generated file(s), 0 rewritten".
- **Baseline:** `java Build.java` in a copy of framework/ is green (exit 0).
- **Generated code that compiled with no warnings in the generated files** (`-Xlint:all -Xdoclint:all/protected -Werror`):
  - a generic record with a bounded type parameter and a wildcard component
  - a record with an intersection bound and generic varargs
  - a record nested in a generic class
  - a record nested in an interface
- **Things I checked and found nothing:**
  - with-method names: they use locale-independent upper-casing, so a Turkish locale does not change them
  - name clashes with methods of Object
  - a `Builder` member type of the record
  - how qualified names are written
  - how the header is recognized
  - how a damaged header and a wrongly named file are handled
  - how one-record generation and full regeneration report errors
  - the order of the check versus compilation
- **Deprecated accessors:** generated code calling a deprecated accessor only warns when deprecation lint is on, and the build turns that off (`-Xlint:all,-deprecation,-removal`). So it is not a finding.

Scratch work is in /workspace/gauntlet/runs/20261002-1011-util-builders/round-1/scratch-breaker/ (copies of framework/ in `fw` and `fw2`, test records in `t/` and `t2/`).
