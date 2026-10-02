package spola.codegen;

import java.util.List;
import java.util.Optional;

/**
 * What the generator needs to know about one record, read from its source file by
 * {@link RecordReader}. Every type is already written the way the generated file must write it
 * (member types qualified, annotations left out, a varargs component as an array).
 *
 * @param sourceFileName the source file's name, e.g. {@code Person.java}
 * @param packageName the package of the record
 * @param imports the import declarations to repeat, as written ({@code import java.util.List;})
 * @param recordName the record's name within the package, e.g. {@code Summary.Rejection}
 * @param isPublic whether the record is accessible outside its package (the record and every
 *     enclosing type public)
 * @param typeParameters the record's type parameters
 * @param components the record's components, in order
 * @param implementsNote empty if the record implements the generated interface as it should,
 *     else a one-line note that says what to add
 */
record RecordModel(String sourceFileName, String packageName, List<String> imports, String recordName,
                   boolean isPublic, List<TypeParameter> typeParameters, List<Component> components,
                   Optional<String> implementsNote) {

    RecordModel {
        imports = List.copyOf(imports);
        typeParameters = List.copyOf(typeParameters);
        components = List.copyOf(components);
    }

    /**
     * A type parameter of the record.
     *
     * @param name the name, e.g. {@code B}
     * @param bounds the bounds joined with {@code &}, e.g. {@code Comparable<B>}; empty if none
     */
    record TypeParameter(String name, String bounds) {

        /** The declaration: {@code B extends Comparable<B>}, or just {@code B}. */
        String declaration() {
            return bounds.isEmpty() ? name : name + " extends " + bounds;
        }
    }

    /**
     * A record component.
     *
     * @param name the component's name
     * @param type the component's type; a varargs component ({@code String... names}) is an
     *     array ({@code String[]})
     */
    record Component(String name, String type) {

        /** The with-method's name: {@code withAge} for {@code age}. */
        String withName() {
            return RecordCodegen.withMethodName(name);
        }
    }

    /** The simple name of the record: {@code Rejection} for {@code Summary.Rejection}. */
    String simpleName() {
        return recordName.substring(recordName.lastIndexOf('.') + 1);
    }

    /** The generated interface's name: {@code RejectionWith}. */
    String interfaceName() {
        return RecordCodegen.interfaceName(recordName);
    }

    /** {@code <A, B extends Comparable<B>>}, or empty for a record without type parameters. */
    String typeParameterDeclarations() {
        return typeParameters.isEmpty() ? ""
                : "<" + String.join(", ", typeParameters.stream().map(TypeParameter::declaration).toList()) + ">";
    }

    /** {@code <A, B>}, or empty for a record without type parameters. */
    String typeArguments() {
        return typeParameters.isEmpty() ? ""
                : "<" + String.join(", ", typeParameters.stream().map(TypeParameter::name).toList()) + ">";
    }

    /** The record's type as the generated code uses it: {@code Pair<A, B>}, {@code Summary.Rejection}. */
    String recordType() {
        return recordName + typeArguments();
    }
}
