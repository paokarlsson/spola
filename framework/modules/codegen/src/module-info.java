/**
 * Builders and with-methods for records, as generated, checked-in source code. Replaces
 * Lombok's {@code @Builder}, {@code @Builder(toBuilder = true)} and {@code @With} for records,
 * without annotations and without reflection.
 *
 * <p>For a record {@code Person} the generator writes {@code PersonWith.java} next to it: a
 * {@code sealed interface PersonWith permits Person} with a {@code withX} method per
 * component, {@code toBuilder()}, {@code builder()} and a nested {@code Builder}. The record
 * opts in explicitly with {@code record Person(...) implements PersonWith}. The generated code
 * depends only on the JDK and the record; nothing needs {@code requires spola.codegen} to use
 * it.
 *
 * <ul>
 *   <li>{@link spola.codegen.RecordCodegen} reads a record's source file (Compiler Tree API,
 *       parsing only) and returns the generated source as text.</li>
 *   <li>{@link spola.codegen.GeneratedFiles} finds generated files by their header, writes
 *       them and checks that each one still matches its record; the build runs that check
 *       before it compiles anything that could contain a generated file.</li>
 *   <li>{@link spola.codegen.CodegenTool} is the {@link java.util.spi.ToolProvider} named
 *       {@code spola-codegen} behind {@code java Build.java codegen}.</li>
 * </ul>
 */
module spola.codegen {
    requires jdk.compiler;

    exports spola.codegen;

    provides java.util.spi.ToolProvider with spola.codegen.CodegenTool;
}
