package spola.codegen;

/**
 * Writes the generated file for a {@link RecordModel}. The layout is fixed: header, package,
 * imports, the interface with {@code builder()}, {@code toBuilder()} and one {@code withX} per
 * component, then the nested {@code Builder} with one field and one setter per component and
 * {@code build()}. Four spaces per level, line ends {@code \n}, no trailing whitespace.
 */
final class Renderer {

    private final RecordModel model;
    private final StringBuilder out = new StringBuilder();

    private Renderer(RecordModel model) {
        this.model = model;
    }

    static String render(RecordModel model) {
        return new Renderer(model).render();
    }

    private void line(int indent, String text) {
        if (!text.isEmpty()) {
            out.append("    ".repeat(indent)).append(text);
        }
        out.append('\n');
    }

    private void blank() {
        line(0, "");
    }

    private String render() {
        var record = model.recordName();
        var type = model.recordType();
        var iface = model.interfaceName();
        var args = model.typeArguments();
        var declarations = model.typeParameterDeclarations();
        var builder = "Builder" + args;

        out.append(GeneratedFiles.header(model.sourceFileName(), record));
        blank();
        line(0, "package " + model.packageName() + ";");
        blank();
        if (!model.imports().isEmpty()) {
            model.imports().forEach(declaration -> line(0, declaration));
            blank();
        }

        line(0, "/**");
        line(0, " * With-methods and a builder for the record {@code " + record + "}, generated from {@code "
                + model.sourceFileName() + "}.");
        line(0, " *");
        line(0, " * <p>{@code " + record + "} opts in with {@code implements " + iface + args + "}. Every record these");
        line(0, " * methods return is created by its canonical constructor, so its validation always runs.");
        if (!model.typeParameters().isEmpty()) {
            line(0, " *");
            typeParameterTags(0);
        }
        line(0, " */");
        line(0, (model.isPublic() ? "public " : "") + "sealed interface " + iface + declarations + " permits " + record + " {");
        blank();

        line(1, "/**");
        line(1, " * Returns a new builder with no component set: each has Java's default value ({@code null},");
        line(1, " * {@code 0} or {@code false}) until it is set.");
        line(1, " *");
        typeParameterTags(1);
        line(1, " * @return a new builder");
        line(1, " */");
        line(1, "static " + (declarations.isEmpty() ? "" : declarations + " ") + builder + " builder() {");
        line(2, "return new Builder" + (args.isEmpty() ? "" : "<>") + "();");
        line(1, "}");
        blank();

        line(1, "/**");
        line(1, " * Returns a new builder with every component set to this record's value.");
        line(1, " *");
        line(1, " * @return a new builder, filled in from this record");
        line(1, " */");
        line(1, "default " + builder + " toBuilder() {");
        line(2, "return new Builder" + (args.isEmpty() ? "" : "<>") + "((" + type + ") this);");
        line(1, "}");

        for (var component : model.components()) {
            blank();
            line(1, "/**");
            line(1, " * Returns a copy of this record with {@code " + component.name() + "} replaced.");
            line(1, " *");
            line(1, " * @param " + component.name() + " the new value of {@code " + component.name() + "}");
            line(1, " * @return a new record; the other components are unchanged");
            line(1, " */");
            line(1, "default " + type + " " + component.withName() + "(" + component.type() + " " + component.name() + ") {");
            line(2, "return toBuilder()." + component.name() + "(" + component.name() + ").build();");
            line(1, "}");
        }
        blank();

        line(1, "/**");
        line(1, " * Builds {@code " + record + "} records one component at a time. A component that is not set");
        line(1, " * has Java's default value. The builder can be reused: every {@link #build()} returns a new record.");
        if (!model.typeParameters().isEmpty()) {
            line(1, " *");
            typeParameterTags(1);
        }
        line(1, " */");
        line(1, "final class Builder" + declarations + " {");
        for (var component : model.components()) {
            line(2, "private " + component.type() + " " + component.name() + ";");
        }
        blank();
        line(2, "private Builder() {");
        line(2, "}");
        blank();
        line(2, "private Builder(" + type + " from) {");
        for (var component : model.components()) {
            line(3, "this." + component.name() + " = from." + component.name() + "();");
        }
        line(2, "}");
        for (var component : model.components()) {
            blank();
            line(2, "/**");
            line(2, " * Sets {@code " + component.name() + "}.");
            line(2, " *");
            line(2, " * @param " + component.name() + " the value");
            line(2, " * @return this builder");
            line(2, " */");
            line(2, "public " + builder + " " + component.name() + "(" + component.type() + " " + component.name() + ") {");
            line(3, "this." + component.name() + " = " + component.name() + ";");
            line(3, "return this;");
            line(2, "}");
        }
        blank();
        line(2, "/**");
        line(2, " * Returns a new {@code " + record + "} with the components set so far, created by its");
        line(2, " * canonical constructor.");
        line(2, " *");
        line(2, " * @return the new record");
        line(2, " */");
        line(2, "public " + type + " build() {");
        line(3, "return new " + type + "(" + String.join(", ",
                model.components().stream().map(component -> "this." + component.name()).toList()) + ");");
        line(2, "}");
        line(1, "}");
        line(0, "}");
        return out.toString();
    }

    /** {@code @param <A> ...} for each type parameter. */
    private void typeParameterTags(int indent) {
        for (var parameter : model.typeParameters()) {
            line(indent, " * @param <" + parameter.name() + "> the record's type parameter {@code " + parameter.name() + "}");
        }
    }
}
