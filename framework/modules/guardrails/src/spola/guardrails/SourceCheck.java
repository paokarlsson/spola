package spola.guardrails;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Source check: parses and attributes {@code .java} files with the Compiler Tree API and
 * reports disallowed annotations, annotation declarations, foreign imports and reflective
 * reads of annotations.
 */
final class SourceCheck {

    /**
     * Result of analysing one group of files.
     *
     * @param violations broken rules
     * @param errors     javac errors; when non-empty the attribution may be incomplete
     */
    record Outcome(List<Violation> violations, List<String> errors) {
    }

    private SourceCheck() {
    }

    /**
     * Analyses {@code files} in one javac task.
     *
     * @param root           paths in the report are relative to this directory
     * @param files          the {@code .java} files
     * @param options        extra javac options, e.g. {@code --module-source-path}
     * @param projectModules modules (besides the JDK's) whose packages may be imported
     */
    static Outcome analyze(Path root, List<Path> files, List<String> options, Set<String> projectModules) {
        if (files.isEmpty()) {
            return new Outcome(List.of(), List.of());
        }
        var analysis = analyze(root, fileManager -> fileManager.getJavaFileObjectsFromPaths(files), options,
                projectModules);
        return new Outcome(analysis.violations(), analysis.errors().stream().map(d -> describe(root, d)).toList());
    }

    /** Violations found and the javac errors (when non-empty, attribution may be incomplete). */
    private record Analysis(List<Violation> violations, List<Diagnostic<? extends JavaFileObject>> errors) {
    }

    /**
     * Source check of a file that does not end in {@code .java} but that the source launcher
     * can still run ({@code java --source 25 FILE}, or {@code ./FILE} with a {@code #!} first
     * line, JEP 330). The file is read like the launcher reads it: UTF-8, a first line
     * starting with {@code #!} is blanked (so line numbers stay exact), the implicit class of a
     * compact source file is named after the file, and preview features are enabled.
     *
     * <ul>
     *   <li>A {@code .jsh} file (a jshell script) is Java code that is not a compilation unit,
     *       so the source check cannot analyse it: it is reported as {@link Rule#UNCHECKABLE_SOURCE}.</li>
     *   <li>A file whose first line is a {@code #!} line naming {@code java} is a launcher script:
     *       it is analysed, and if javac cannot read, parse or attribute it, it is reported as
     *       {@link Rule#UNCHECKABLE_SOURCE} (fail-closed).</li>
     *   <li>Any other file is analysed if it is UTF-8 text that javac parses without errors
     *       (then the launcher could run it); attribution errors are reported as
     *       {@link Rule#UNCHECKABLE_SOURCE}. A file javac cannot parse is not Java source and
     *       the launcher cannot run it either: it is skipped.</li>
     * </ul>
     *
     * @return the violations, or empty if {@code file} is not Java source
     */
    static Optional<List<Violation>> analyzeLauncherSource(Path root, Path file) {
        var relative = FileTree.relative(root, file);
        if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jsh")) {
            return Optional.of(List.of(new Violation(relative, 0, Rule.UNCHECKABLE_SOURCE, "jshell script", "")));
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        var shebang = bytes.length >= 2 && bytes[0] == '#' && bytes[1] == '!';
        var launcherScript = shebang && firstLine(bytes).toLowerCase(Locale.ROOT).contains("java");
        var unchecked = Optional.of(List.of(
                new Violation(relative, 0, Rule.UNCHECKABLE_SOURCE, "launcher script that is not UTF-8 text", "")));
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException notText) {
            return launcherScript ? unchecked : Optional.empty();
        }
        if (shebang) {
            // Keep the line break so that line numbers match the file.
            var end = text.indexOf('\n');
            text = end < 0 ? "" : text.substring(end);
        }
        var source = new LauncherSource(file, text);
        var options = List.of("--release", String.valueOf(Runtime.version().feature()), "--enable-preview");
        var parseErrors = parseErrors(source, options);
        if (!parseErrors.isEmpty()) {
            return launcherScript
                    ? Optional.of(List.of(new Violation(relative, parseErrors.getFirst().intValue(),
                            Rule.UNCHECKABLE_SOURCE, "launcher script javac cannot parse", "")))
                    : Optional.empty();
        }
        var analysis = analyze(root, fileManager -> List.of(source), options, Set.of());
        if (analysis.errors().isEmpty()) {
            return Optional.of(analysis.violations());
        }
        var violations = new ArrayList<>(analysis.violations());
        violations.add(new Violation(relative, (int) analysis.errors().getFirst().getLineNumber(),
                Rule.UNCHECKABLE_SOURCE, "Java source javac cannot attribute", ""));
        return Optional.of(List.copyOf(violations));
    }

    private static String firstLine(byte[] bytes) {
        int end = 0;
        while (end < bytes.length && bytes[end] != '\n') {
            end++;
        }
        return new String(bytes, 0, end, StandardCharsets.ISO_8859_1);
    }

    /** Lines of the errors javac reports when only parsing {@code source}; empty if it parses. */
    private static List<Long> parseErrors(JavaFileObject source, List<String> options) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var allOptions = new ArrayList<>(List.of("-proc:none", "-Xlint:none"));
        allOptions.addAll(options);
        try (var fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(new StringWriter(), fileManager, diagnostics, allOptions, null,
                    List.of(source));
            task.parse();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(Diagnostic::getLineNumber)
                .toList();
    }

    /**
     * Source text of a launcher program, under the URI of its file (so reports name the file).
     * Its name need not match a public class, as in the source launcher.
     */
    private static final class LauncherSource extends SimpleJavaFileObject {
        private final String text;

        LauncherSource(Path file, String text) {
            super(file.toAbsolutePath().normalize().toUri(), Kind.SOURCE);
            this.text = text;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return text;
        }

        @Override
        public boolean isNameCompatible(String simpleName, Kind kind) {
            return true;
        }
    }

    private static Analysis analyze(Path root,
                                    Function<StandardJavaFileManager, Iterable<? extends JavaFileObject>> units,
                                    List<String> options, Set<String> projectModules) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var allOptions = new ArrayList<String>(List.of(
                "-proc:none",
                // Keep attributing after the first error, so that broken fixtures are still
                // fully analysed (default javac policy stops attribution after an error).
                "--should-stop=ifError=FLOW"));
        allOptions.addAll(options);
        try (var fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            // No class path: anything not in the JDK, the project or these files stays unresolved.
            fileManager.setLocation(StandardLocation.CLASS_PATH, List.of());
            var task = (JavacTask) compiler.getTask(new StringWriter(), fileManager, diagnostics, allOptions, null,
                    units.apply(fileManager));
            var compilationUnits = task.parse();
            task.analyze();
            var violations = new ArrayList<Violation>();
            var scanner = new Scanner(root, Trees.instance(task), task.getElements(), task.getTypes(),
                    projectModules, violations);
            for (var unit : compilationUnits) {
                scanner.scanUnit(unit);
            }
            List<Diagnostic<? extends JavaFileObject>> errors = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .toList();
            // javac's generated record members (canonical constructor, accessors) share the
            // component's annotation trees, so one annotation can be visited more than once.
            return new Analysis(violations.stream().distinct().toList(), errors);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String describe(Path root, Diagnostic<? extends JavaFileObject> d) {
        var source = d.getSource() == null ? "<javac>" : FileTree.relative(root, Path.of(d.getSource().toUri()));
        return source + ":" + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT);
    }

    /**
     * {@code AnnotatedElement.isAnnotationPresent}: reads an annotation but returns a
     * {@code boolean}, so the return-type criterion below does not catch it.
     */
    private static final String IS_ANNOTATION_PRESENT = "isAnnotationPresent";

    /**
     * {@code Method.getDefaultValue}: reads the default of an annotation element but returns
     * {@code Object}, so the return-type criterion below does not catch it.
     */
    private static final String GET_DEFAULT_VALUE = "getDefaultValue";

    private static final class Scanner extends TreePathScanner<Void, Void> {
        private final Path root;
        private final Trees trees;
        private final Elements elements;
        private final Types types;
        private final Set<String> projectModules;
        private final List<Violation> out;
        private final TypeElement annotatedElement;
        private final TypeElement annotation;
        private final TypeElement annotatedType;
        private CompilationUnitTree unit;
        private String file;

        Scanner(Path root, Trees trees, Elements elements, Types types, Set<String> projectModules,
                List<Violation> out) {
            this.root = root;
            this.trees = trees;
            this.elements = elements;
            this.types = types;
            this.projectModules = projectModules;
            this.out = out;
            var javaBase = elements.getModuleElement("java.base");
            this.annotatedElement = elements.getTypeElement(javaBase, "java.lang.reflect.AnnotatedElement");
            this.annotation = elements.getTypeElement(javaBase, "java.lang.annotation.Annotation");
            this.annotatedType = elements.getTypeElement(javaBase, "java.lang.reflect.AnnotatedType");
        }

        void scanUnit(CompilationUnitTree compilationUnit) {
            unit = compilationUnit;
            file = FileTree.relative(root, Path.of(compilationUnit.getSourceFile().toUri()));
            scan(compilationUnit, null);
        }

        @Override
        public Void visitAnnotation(AnnotationTree node, Void unused) {
            var type = trees.getElement(new TreePath(getCurrentPath(), node.getAnnotationType()));
            if (type instanceof TypeElement annotation && annotation.getKind() == ElementKind.ANNOTATION_TYPE
                    && annotation.asType().getKind() != TypeKind.ERROR) {
                var name = annotation.getQualifiedName().toString();
                if (!Allowlist.ANNOTATIONS.contains(name)) {
                    report(node, Rule.ANNOTATION_NOT_ALLOWED, "@" + name);
                }
            } else {
                // Cannot prove the annotation is allowed, so it is not.
                report(node, Rule.ANNOTATION_NOT_ALLOWED, "@" + node.getAnnotationType() + " (unresolved)");
            }
            return super.visitAnnotation(node, unused);
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            if (node.getKind() == Tree.Kind.ANNOTATION_TYPE) {
                var name = trees.getElement(getCurrentPath()) instanceof TypeElement type
                        ? type.getQualifiedName().toString()
                        : node.getSimpleName().toString();
                out.add(new Violation(file, annotationKeywordLine(node), Rule.ANNOTATION_DECLARATION,
                        "@interface " + name, ""));
            }
            return super.visitClass(node, unused);
        }

        @Override
        public Void visitImport(ImportTree node, Void unused) {
            var identifier = node.getQualifiedIdentifier();
            if (node.isModule()) {
                var module = identifier.toString();
                if (!Allowlist.isAllowedModule(module, projectModules)) {
                    report(node, Rule.FOREIGN_IMPORT, "import module " + module);
                }
            } else {
                // For "a.b.*", "static a.B.m" and "static a.B.*" the imported package or type is
                // the qualifier; for "a.b.C" it is the identifier itself.
                var onDemand = identifier instanceof MemberSelectTree select
                        && select.getIdentifier().contentEquals("*");
                var target = (node.isStatic() || onDemand) && identifier instanceof MemberSelectTree select
                        ? select.getExpression()
                        : identifier;
                var element = trees.getElement(TreePath.getPath(unit, target));
                if (!isAllowedImport(element)) {
                    report(node, Rule.FOREIGN_IMPORT, "import " + (node.isStatic() ? "static " : "") + identifier);
                }
            }
            return super.visitImport(node, unused);
        }

        private boolean isAllowedImport(Element element) {
            if (element == null || element.asType().getKind() == TypeKind.ERROR) {
                return false;
            }
            ModuleElement module = elements.getModuleOf(element);
            if (module == null) {
                return false;
            }
            if (!module.isUnnamed()) {
                return Allowlist.isAllowedModule(module.getQualifiedName().toString(), projectModules);
            }
            // The unnamed module has no class path here, so it only holds the analysed files.
            return switch (element) {
                case TypeElement type -> trees.getPath(type) != null;
                case PackageElement pkg -> pkg.getEnclosedElements().stream().anyMatch(e -> trees.getPath(e) != null);
                default -> false;
            };
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            checkReflectiveRead(node, trees.getElement(new TreePath(getCurrentPath(), node.getMethodSelect())));
            return super.visitMethodInvocation(node, unused);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree node, Void unused) {
            checkReflectiveRead(node, trees.getElement(getCurrentPath()));
            return super.visitMemberReference(node, unused);
        }

        private void checkReflectiveRead(Tree node, Element element) {
            if (element instanceof ExecutableElement method
                    && method.getEnclosingElement() instanceof TypeElement owner
                    && readsAnnotations(owner, method)) {
                report(node, Rule.ANNOTATION_REFLECTION, owner.getQualifiedName() + "." + method.getSimpleName());
            }
        }

        /**
         * A JDK ({@code java.base}) method reads annotations reflectively if it returns
         * annotations or annotated types ({@code Annotation}, {@code AnnotatedType}, or
         * arrays of them, after erasure: {@code getAnnotation}, {@code getParameterAnnotations},
         * {@code getAnnotatedReturnType}, ...), or if it is
         * {@code AnnotatedElement.isAnnotationPresent} or {@code Method.getDefaultValue}
         * (the default of an annotation element). Matching on the return type instead of a
         * list of names also covers methods a future JDK adds.
         */
        private boolean readsAnnotations(TypeElement owner, ExecutableElement method) {
            var module = elements.getModuleOf(owner);
            if (module == null || !module.getQualifiedName().contentEquals("java.base")) {
                return false;
            }
            var name = method.getSimpleName().toString();
            if (name.equals(IS_ANNOTATION_PRESENT)) {
                return isSubtype(owner.asType(), annotatedElement);
            }
            if (name.equals(GET_DEFAULT_VALUE)) {
                return owner.getQualifiedName().contentEquals("java.lang.reflect.Method");
            }
            TypeMirror type = types.erasure(method.getReturnType());
            while (type instanceof ArrayType array) {
                type = array.getComponentType();
            }
            return type.getKind() == TypeKind.DECLARED
                    && (isSubtype(type, annotation) || isSubtype(type, annotatedType));
        }

        private boolean isSubtype(TypeMirror type, TypeElement supertype) {
            return supertype != null && types.isSubtype(types.erasure(type), types.erasure(supertype.asType()));
        }

        private void report(Tree node, Rule rule, String subject) {
            out.add(new Violation(file, lineOf(trees.getSourcePositions().getStartPosition(unit, node)), rule,
                    subject, ""));
        }

        private int lineOf(long position) {
            return position < 0 ? 0 : (int) unit.getLineMap().getLineNumber(position);
        }

        /**
         * Line of the {@code @interface} keyword, after any modifiers and annotations.
         * The tree API has no position for the keyword itself, so this skips whitespace and
         * comments from the end of the modifiers; nothing else can stand between them.
         */
        private int annotationKeywordLine(ClassTree node) {
            var positions = trees.getSourcePositions();
            long position = positions.getEndPosition(unit, node.getModifiers());
            if (position < 0) {
                return lineOf(positions.getStartPosition(unit, node));
            }
            CharSequence text;
            try {
                text = unit.getSourceFile().getCharContent(true);
            } catch (IOException e) {
                return lineOf(positions.getStartPosition(unit, node));
            }
            int i = (int) position;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                    while (i < text.length() && text.charAt(i) != '\n') {
                        i++;
                    }
                } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                    int end = text.toString().indexOf("*/", i + 2);
                    i = end < 0 ? text.length() : end + 2;
                } else {
                    break;
                }
            }
            return lineOf(Math.min(i, Math.max(0, text.length() - 1)));
        }
    }
}
