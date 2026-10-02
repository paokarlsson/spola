package spola.codegen;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Reads one record from its source file into a {@link RecordModel}: parses the file with the
 * Compiler Tree API ({@link JavacTask#parse()}, no attribution, no class path) and checks
 * everything the generated code depends on. Every failure is a {@link CodegenException} that
 * names the file and the record.
 *
 * <p>Types are written from the syntax tree, not resolved. So that a name means in the
 * generated file what it means in the record, a type name that is a member type of the record
 * or of a type around it is qualified ({@code Rejection} in {@code Summary} becomes
 * {@code Summary.Rejection}), and the generated file repeats the source file's imports.
 * Member types inherited from a supertype cannot be seen in the syntax tree and are not
 * qualified (see the module's README).
 */
final class RecordReader {

    /** Component names that would clash with a generated method: {@code builder()}, {@code toBuilder()}, {@code build()}, and the builder's setter {@code equals(...)} with {@link Object#equals}. */
    private static final List<String> RESERVED = List.of("builder", "toBuilder", "build", "equals");

    /** The generated interface's nested class; inside the interface this simple name means it. */
    private static final String BUILDER = "Builder";

    private final Path file;
    private final String recordName;

    private RecordReader(Path file, String recordName) {
        this.file = file;
        this.recordName = recordName;
    }

    static RecordModel read(Path file, String recordName) {
        return new RecordReader(file, recordName).read();
    }

    private CodegenException error(String reason) {
        return CodegenException.of(file, recordName, reason);
    }

    private RecordModel read() {
        if (!SourceVersion.isName(recordName)) {
            throw error("'" + recordName + "' is not a record name; give the record's name within its package,"
                    + " e.g. Person or Summary.Rejection");
        }
        var fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        if (!fileName.endsWith(".java")) {
            throw error("not a .java file");
        }
        var unit = parse(readText());
        if (unit.getPackageName() == null) {
            throw error("the file has no package declaration; records in the default package are not supported");
        }
        var segments = List.of(recordName.split("\\."));
        if (segments.getFirst().equals(BUILDER)) {
            throw error("a top-level type named Builder would mean the generated " + RecordCodegen.interfaceName(recordName)
                    + ".Builder inside the generated interface; rename it");
        }
        var chain = findChain(unit, segments);
        var record = chain.getLast();
        if (record.getKind() != Tree.Kind.RECORD) {
            throw error(recordName + " is " + kindName(record.getKind()) + ", not a record");
        }
        var isPublic = access(chain);

        var typeParameterNames = new HashSet<String>();
        for (var parameter : record.getTypeParameters()) {
            var name = parameter.getName().toString();
            if (name.equals(BUILDER)) {
                throw error("the type parameter Builder would mean the generated " + RecordCodegen.interfaceName(recordName)
                        + ".Builder inside the generated interface; rename it");
            }
            typeParameterNames.add(name);
        }
        var printer = new TypePrinter(typeParameterNames, scopes(chain, segments));
        var typeParameters = new ArrayList<RecordModel.TypeParameter>();
        for (TypeParameterTree parameter : record.getTypeParameters()) {
            var bounds = String.join(" & ", parameter.getBounds().stream().map(printer::print).toList());
            typeParameters.add(new RecordModel.TypeParameter(parameter.getName().toString(), bounds));
        }
        var components = new ArrayList<RecordModel.Component>();
        for (var member : record.getMembers()) {
            // In a record, the parser turns each component into a field; every other field is static.
            if (member instanceof VariableTree variable && !variable.getModifiers().getFlags().contains(Modifier.STATIC)) {
                components.add(new RecordModel.Component(variable.getName().toString(), printer.print(variable.getType())));
            }
        }
        if (components.isEmpty()) {
            throw error("the record has no components; there is nothing to build or replace");
        }
        checkNames(record, components);

        return new RecordModel(fileName, name(unit.getPackageName()), imports(unit, printer.used),
                recordName, isPublic, typeParameters, components, implementsNote(record, typeParameters));
    }

    private String readText() {
        if (!Files.exists(file)) {
            throw error("the file does not exist");
        }
        if (!Files.isRegularFile(file)) {
            throw error("not a regular file");
        }
        try {
            var bytes = Files.readAllBytes(file);
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw error("the file is not valid UTF-8");
        } catch (IOException e) {
            throw error("cannot read the file: " + e);
        }
    }

    private CompilationUnitTree parse(String text) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw error("no Java compiler in this runtime (module jdk.compiler)");
        }
        var source = new SimpleJavaFileObject(file.toUri(), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var task = (JavacTask) compiler.getTask(null, null, diagnostics, List.of("-proc:none"), null, List.of(source));
        List<CompilationUnitTree> units = new ArrayList<>();
        try {
            task.parse().forEach(units::add);
        } catch (IOException e) {
            throw error("cannot parse the file: " + e);
        }
        for (var diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                throw error("the file does not parse: line " + diagnostic.getLineNumber() + ": "
                        + diagnostic.getMessage(Locale.ROOT).lines().findFirst().orElse(""));
            }
        }
        if (units.size() != 1) {
            throw error("the file does not parse into one compilation unit");
        }
        return units.getFirst();
    }

    /** The record and the types around it, outermost first. */
    private List<ClassTree> findChain(CompilationUnitTree unit, List<String> segments) {
        var chain = new ArrayList<ClassTree>();
        List<? extends Tree> candidates = unit.getTypeDecls();
        for (var segment : segments) {
            var found = candidates.stream()
                    .filter(tree -> tree instanceof ClassTree type && type.getSimpleName().contentEquals(segment))
                    .map(ClassTree.class::cast)
                    .findFirst();
            if (found.isEmpty()) {
                throw error(chain.isEmpty()
                        ? "the file declares no top-level type named " + segment
                        : "the record is not in the file: " + String.join(".", segments.subList(0, chain.size()))
                                + " has no member type named " + segment);
            }
            chain.add(found.get());
            candidates = found.get().getMembers();
        }
        return chain;
    }

    /** Whether the generated interface is public: only if the record and every type around it is. */
    private boolean access(List<ClassTree> chain) {
        var isPublic = true;
        for (var i = 0; i < chain.size(); i++) {
            var flags = chain.get(i).getModifiers().getFlags();
            if (flags.contains(Modifier.PRIVATE)) {
                throw error(i == chain.size() - 1
                        ? "the record is private, so the generated interface beside it cannot name it;"
                                + " make it package-private or public"
                        : "the record is inside the private type " + chain.get(i).getSimpleName()
                                + ", so the generated interface beside it cannot name it");
            }
            var inInterface = i > 0 && (chain.get(i - 1).getKind() == Tree.Kind.INTERFACE
                    || chain.get(i - 1).getKind() == Tree.Kind.ANNOTATION_TYPE);
            if (!flags.contains(Modifier.PUBLIC) && !inInterface) {
                isPublic = false;
            }
        }
        return isPublic;
    }

    /** The member types visible in the record's header, innermost scope first: qualified prefix -> names. */
    private static LinkedHashMap<String, Set<String>> scopes(List<ClassTree> chain, List<String> segments) {
        var scopes = new LinkedHashMap<String, Set<String>>();
        for (var i = chain.size() - 1; i >= 0; i--) {
            var names = new HashSet<String>();
            for (var member : chain.get(i).getMembers()) {
                if (member instanceof ClassTree type) {
                    names.add(type.getSimpleName().toString());
                }
            }
            scopes.put(String.join(".", segments.subList(0, i + 1)), names);
        }
        return scopes;
    }

    private void checkNames(ClassTree record, List<RecordModel.Component> components) {
        var withNames = new LinkedHashMap<String, String>();
        for (var component : components) {
            if (RESERVED.contains(component.name())) {
                throw error("the component " + component.name() + " clashes with the generated method "
                        + component.name() + (component.name().equals("equals") ? "(...) of the builder" : "()")
                        + "; rename the component");
            }
            var previous = withNames.put(component.withName(), component.name());
            if (previous != null) {
                throw error("the components " + previous + " and " + component.name() + " both give the method "
                        + component.withName() + "; rename one of them");
            }
        }
        for (var member : record.getMembers()) {
            if (member instanceof MethodTree method) {
                var name = method.getName().toString();
                var parameters = method.getParameters().size();
                if ((name.equals("toBuilder") && parameters == 0) || (withNames.containsKey(name) && parameters == 1)) {
                    throw error("the record declares " + name + "(" + (parameters == 0 ? "" : "...")
                            + ") itself, which clashes with the generated method of that name; remove or rename it");
                }
            }
        }
    }

    /**
     * The source file's imports, as written. A single-type import (also {@code import static})
     * is left out when no component type or bound uses its simple name, so that the generated
     * file depends only on what its types need; on-demand and module imports are kept.
     */
    private static List<String> imports(CompilationUnitTree unit, Set<String> usedNames) {
        var imports = new ArrayList<String>();
        for (ImportTree importTree : unit.getImports()) {
            var target = name(importTree.getQualifiedIdentifier());
            var simple = target.substring(target.lastIndexOf('.') + 1);
            if (importTree.isModule()) {
                imports.add("import module " + target + ";");
            } else if (simple.equals("*") || usedNames.contains(simple)) {
                imports.add("import " + (importTree.isStatic() ? "static " : "") + target + ";");
            }
        }
        return imports;
    }

    /** A note when the record does not implement the generated interface with its type parameters, in order. */
    private Optional<String> implementsNote(ClassTree record, List<RecordModel.TypeParameter> typeParameters) {
        var interfaceName = RecordCodegen.interfaceName(recordName);
        var expected = interfaceName + (typeParameters.isEmpty() ? ""
                : "<" + String.join(", ", typeParameters.stream().map(RecordModel.TypeParameter::name).toList()) + ">");
        for (var implemented : record.getImplementsClause()) {
            var raw = implemented instanceof ParameterizedTypeTree parameterized ? parameterized.getType() : implemented;
            var simple = switch (raw) {
                case IdentifierTree identifier -> identifier.getName().toString();
                case MemberSelectTree select -> select.getIdentifier().toString();
                default -> "";
            };
            if (simple.equals(interfaceName)) {
                var written = simple;
                if (implemented instanceof ParameterizedTypeTree parameterized) {
                    written += "<" + String.join(", ", parameterized.getTypeArguments().stream().map(Tree::toString).toList()) + ">";
                }
                return written.equals(expected) ? Optional.empty()
                        : Optional.of("record " + recordName + " implements " + written + ", but the generated interface"
                                + " needs `implements " + expected + "`");
            }
        }
        return Optional.of("record " + recordName + " does not implement " + expected + " yet; add `implements "
                + expected + "` to it (the build does not compile until it does)");
    }

    private static String kindName(Tree.Kind kind) {
        return switch (kind) {
            case CLASS -> "a class";
            case INTERFACE -> "an interface";
            case ENUM -> "an enum";
            case ANNOTATION_TYPE -> "an annotation interface";
            default -> "a " + kind.name().toLowerCase(Locale.ROOT);
        };
    }

    /** A dotted name ({@code java.util.List}, {@code java.util.*}) from an identifier or member select. */
    private static String name(Tree tree) {
        return switch (tree) {
            case IdentifierTree identifier -> identifier.getName().toString();
            case MemberSelectTree select -> name(select.getExpression()) + "." + select.getIdentifier();
            default -> tree.toString();
        };
    }

    /**
     * Writes a type from the syntax tree in a fixed form (one space after each comma, no
     * annotations), qualifying member types and collecting the simple names that the imports
     * must provide.
     */
    private final class TypePrinter {
        private final Set<String> typeParameters;
        private final LinkedHashMap<String, Set<String>> scopes;
        final Set<String> used = new HashSet<>();

        TypePrinter(Set<String> typeParameters, LinkedHashMap<String, Set<String>> scopes) {
            this.typeParameters = typeParameters;
            this.scopes = scopes;
        }

        String print(Tree tree) {
            return switch (tree) {
                case PrimitiveTypeTree primitive -> primitive.getPrimitiveTypeKind().name().toLowerCase(Locale.ROOT);
                case IdentifierTree identifier -> first(identifier.getName().toString());
                case MemberSelectTree select -> print(select.getExpression()) + "." + select.getIdentifier();
                case ParameterizedTypeTree parameterized -> print(parameterized.getType()) + "<"
                        + String.join(", ", parameterized.getTypeArguments().stream().map(this::print).toList()) + ">";
                case ArrayTypeTree array -> print(array.getType()) + "[]";
                case WildcardTree wildcard -> switch (wildcard.getKind()) {
                    case EXTENDS_WILDCARD -> "? extends " + print(wildcard.getBound());
                    case SUPER_WILDCARD -> "? super " + print(wildcard.getBound());
                    default -> "?";
                };
                case AnnotatedTypeTree annotated -> print(annotated.getUnderlyingType());
                default -> throw error("unsupported type syntax in the record header: " + tree);
            };
        }

        /** The first name of a type: a type parameter, a member type to qualify, or a name the imports or the package provide. */
        private String first(String name) {
            if (typeParameters.contains(name)) {
                return name;
            }
            for (var scope : scopes.entrySet()) {
                if (scope.getValue().contains(name)) {
                    return scope.getKey() + "." + name;
                }
            }
            if (name.equals(BUILDER)) {
                throw error("a component type named Builder would mean the generated "
                        + RecordCodegen.interfaceName(recordName) + ".Builder inside the generated interface;"
                        + " write its qualified name (e.g. com.example.Builder)");
            }
            used.add(name);
            return name;
        }
    }
}
