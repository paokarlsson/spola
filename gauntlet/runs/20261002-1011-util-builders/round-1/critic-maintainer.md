VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /workspace/framework/modules/codegen/src/spola/codegen/RecordReader.java:76-130
  problem: `read()` validates the name, file and package, finds the record chain, checks access, reads type parameters, components and names, and builds the model, all in one method of about 55 lines.
  evidence: The type-parameter loop appears twice, at lines 101-109 and 112-115. The first pass checks for the name `Builder` and the second pass builds the bounds.
  fix_hint: Pull out `readTypeParameters(record)` and `readComponents(record, printer)`. Then the three separate "Builder" rejections (lines 90, 104 and 370) can share one helper.

- id: MNT-2
  severity: MINOR
  where: /workspace/framework/modules/codegen/src/spola/codegen/GeneratedFiles.java:119-152 and 225-245
  problem: The write-with-IOException-wrapping code is duplicated in `write` and `regenerate`. Strict UTF-8 reading is also duplicated, in `GeneratedFiles.read` and `RecordReader.readText`.
  evidence: Both places contain `Files.writeString(..., UTF_8)` with the same `catch (IOException e) { throw new UncheckedIOException("cannot write " ...`. Both `read` and `readText` call `StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))`.
  fix_hint: Add one package-private `Utf8` helper (or a `writeIfChanged`) and use it from both classes.

- id: MNT-3
  severity: MINOR
  where: /workspace/framework/modules/codegen/src/spola/codegen/RecordReader.java:45-48
  problem: The class Javadoc says the generated file "repeats the source file's imports". That is no longer true, because single-type imports are pruned (lines 263-267).
  evidence: "and the generated file repeats the source file's imports" against `imports(unit, printer.used)`.
  fix_hint: Reword to "repeats the imports the component types need".

- id: MNT-4
  severity: MINOR
  where: /workspace/framework/modules/codegen/src/spola/codegen/Renderer.java:33-147
  problem: `render()` is a single method of about 115 lines made of `line(...)` calls with Javadoc text embedded as string literals. Changing the layout means finding the right place in a long flat sequence.
  evidence: `render()` runs from line 33 to line 147 and is one run of `line(n, ...)` calls.
  fix_hint: Split it into `header()`, `interfaceDeclaration()`, `withMethods()` and `builderClass()`.
