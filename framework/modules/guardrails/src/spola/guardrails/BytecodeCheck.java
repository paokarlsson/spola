package spola.guardrails;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attribute;
import java.lang.classfile.ClassFile;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.attribute.AnnotationDefaultAttribute;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleTypeAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Bytecode check: reads {@code .class} files with the Class-File API and reports every
 * annotation outside the allowlist (declaration, parameter and type annotations, visible
 * and invisible, including type annotations inside method bodies) and every annotation type.
 */
final class BytecodeCheck {

    private final Path root;
    private final List<Violation> out = new ArrayList<>();
    private String file;

    private BytecodeCheck(Path root) {
        this.root = root;
    }

    static List<Violation> run(Path root, List<Path> classFiles) {
        var check = new BytecodeCheck(root);
        for (var classFile : classFiles) {
            check.checkClass(classFile);
        }
        return List.copyOf(check.out);
    }

    private void checkClass(Path classFile) {
        file = FileTree.relative(root, classFile);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(classFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var model = ClassFile.of().parse(bytes);
        var className = model.thisClass().asInternalName().replace('/', '.');
        if (model.flags().has(AccessFlag.ANNOTATION)) {
            out.add(new Violation(file, 0, Rule.ANNOTATION_DECLARATION, "@interface " + className, "class"));
        }
        attributes(model.attributes(), "class");
        for (var field : model.fields()) {
            attributes(field.attributes(), "field " + field.fieldName().stringValue());
        }
        for (var method : model.methods()) {
            attributes(method.attributes(),
                    "method " + method.methodName().stringValue() + method.methodType().stringValue());
        }
    }

    private void attributes(List<Attribute<?>> attributes, String where) {
        for (var attribute : attributes) {
            switch (attribute) {
                case RuntimeVisibleAnnotationsAttribute a -> annotations(a.annotations(), where);
                case RuntimeInvisibleAnnotationsAttribute a -> annotations(a.annotations(), where);
                case RuntimeVisibleParameterAnnotationsAttribute a -> parameters(a.parameterAnnotations(), where);
                case RuntimeInvisibleParameterAnnotationsAttribute a -> parameters(a.parameterAnnotations(), where);
                case RuntimeVisibleTypeAnnotationsAttribute a -> typeAnnotations(a.annotations(), where);
                case RuntimeInvisibleTypeAnnotationsAttribute a -> typeAnnotations(a.annotations(), where);
                case RecordAttribute a -> a.components().forEach(component ->
                        attributes(component.attributes(), "record component " + component.name().stringValue()));
                case CodeAttribute a -> attributes(a.attributes(), where + " code");
                case AnnotationDefaultAttribute a -> value(a.defaultValue(), where + " default value");
                default -> {
                    // Other attributes cannot carry annotations.
                }
            }
        }
    }

    private void annotations(List<Annotation> annotations, String where) {
        annotations.forEach(annotation -> annotation(annotation, where));
    }

    private void parameters(List<List<Annotation>> perParameter, String where) {
        for (int i = 0; i < perParameter.size(); i++) {
            annotations(perParameter.get(i), where + " parameter " + i);
        }
    }

    private void typeAnnotations(List<TypeAnnotation> annotations, String where) {
        for (var annotation : annotations) {
            annotation(annotation.annotation(), where + " type-use " + annotation.targetInfo().targetType());
        }
    }

    private void annotation(Annotation annotation, String where) {
        var descriptor = annotation.className().stringValue();
        if (!Allowlist.ANNOTATION_DESCRIPTORS.contains(descriptor)) {
            out.add(new Violation(file, 0, Rule.ANNOTATION_NOT_ALLOWED, "@" + binaryName(descriptor), where));
        }
        for (var element : annotation.elements()) {
            value(element.value(), where);
        }
    }

    private void value(AnnotationValue value, String where) {
        switch (value) {
            case AnnotationValue.OfAnnotation nested -> annotation(nested.annotation(), where);
            case AnnotationValue.OfArray array -> array.values().forEach(v -> value(v, where));
            default -> {
                // Constants, enums and classes cannot carry annotations.
            }
        }
    }

    /** {@code Lfoo/Bar$Baz;} to {@code foo.Bar$Baz}; other descriptors are returned unchanged. */
    private static String binaryName(String descriptor) {
        return descriptor.startsWith("L") && descriptor.endsWith(";")
                ? descriptor.substring(1, descriptor.length() - 1).replace('/', '.')
                : descriptor;
    }
}
