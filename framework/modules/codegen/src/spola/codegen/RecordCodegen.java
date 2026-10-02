package spola.codegen;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Generates the with-interface of a record: for a record {@code Person} in
 * {@code Person.java}, the source of {@code PersonWith.java}, to be written next to it.
 *
 * <p>The generated file holds a {@code sealed interface PersonWith permits Person} with the
 * same access as the record (public only if the record and every type around it are public)
 * and
 * <ul>
 *   <li>a {@code default} method {@code withX(T x)} per component, in component order, that
 *       returns a new record with that component replaced,</li>
 *   <li>{@code default Builder toBuilder()}, a builder filled in from the record,</li>
 *   <li>{@code static Builder builder()} and the nested {@code final class Builder} with one
 *       setter per component (named like the component, returning the builder) and
 *       {@code build()}.</li>
 * </ul>
 * Every record the generated code returns is created by the record's canonical constructor,
 * so a compact constructor's validation always runs. A component the builder was not given
 * has Java's default value ({@code null}, {@code 0}, {@code false}). A builder can be reused:
 * every {@code build()} returns a new record. The record opts in with
 * {@code record Person(String name, int age) implements PersonWith}.
 *
 * <pre>{@code
 * var ada = PersonWith.builder().name("Ada").age(36).build();
 * var older = ada.withAge(37);
 * var grace = ada.toBuilder().name("Grace").build();
 * }</pre>
 *
 * <p>The source file is parsed with the Compiler Tree API (no attribution, no class path), so
 * types are written as in the source and the generated file repeats the source file's imports
 * (single-type imports only if a component type uses them). The output depends only on the
 * text of the source file and its file name, not on its directory, and is the same byte for
 * byte for the same input (line ends {@code \n}). It starts with a fixed header,
 * {@link GeneratedFiles#header(String, String)}, by which the build recognizes generated files.
 */
public final class RecordCodegen {

    /** Appended to the record's simple name to name the generated interface and its file. */
    public static final String SUFFIX = "With";

    private RecordCodegen() {
    }

    /**
     * Generates the with-interface of a record.
     *
     * @param sourceFile the record's source file
     * @param recordName the record's name within its package: {@code Person}, or
     *     {@code Summary.Rejection} for a record nested in {@code Summary}
     * @return the source code of {@code <Record>With.java}
     * @throws CodegenException if the file does not exist or does not parse, the record is not
     *     in it, the type is not a record, the record is private or has no components, or a
     *     component's name clashes with a generated method; the message names the file and the
     *     record
     */
    public static String generate(Path sourceFile, String recordName) {
        return generation(sourceFile, recordName).source();
    }

    /**
     * The file the with-interface of a record is written to: {@code <Record>With.java} in the
     * directory of the record's source file. For {@code Summary.Rejection} it is
     * {@code RejectionWith.java}.
     *
     * @param sourceFile the record's source file
     * @param recordName the record's name within its package
     * @return the generated file's path
     */
    public static Path targetFile(Path sourceFile, String recordName) {
        return sourceFile.resolveSibling(interfaceName(recordName) + ".java");
    }

    /**
     * The name of the generated interface: the record's simple name and {@value #SUFFIX}.
     *
     * @param recordName the record's name within its package
     * @return e.g. {@code RejectionWith} for {@code Summary.Rejection}
     */
    public static String interfaceName(String recordName) {
        return recordName.substring(recordName.lastIndexOf('.') + 1) + SUFFIX;
    }

    /** The with-method of a component: {@code withAge} for {@code age}, {@code withURL} for {@code URL}. */
    static String withMethodName(String component) {
        var first = component.codePointAt(0);
        return "with" + Character.toString(Character.toUpperCase(first)) + component.substring(Character.charCount(first));
    }

    /**
     * The generated source and, if the record does not implement the generated interface yet,
     * a one-line note that says what to add.
     */
    record Generation(String source, Optional<String> note) {
    }

    static Generation generation(Path sourceFile, String recordName) {
        var model = RecordReader.read(sourceFile, recordName);
        return new Generation(Renderer.render(model), model.implementsNote());
    }
}
