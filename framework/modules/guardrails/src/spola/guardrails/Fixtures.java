package spola.guardrails;

import java.util.List;

/**
 * The explicit list of guardrails fixtures (at least one per rule) and, for each, every
 * violation it must produce. Lines and locations are exact: if a fixture is changed so that
 * a violation disappears, or a new one appears, the self-test fails.
 */
final class Fixtures {

    private Fixtures() {
    }

    static final List<Fixture> ALL = List.of(
            new Fixture("source-annotation", Fixture.Kind.SOURCE, List.of(
                    source("fx/Generated.java", 8, Rule.ANNOTATION_NOT_ALLOWED, "@javax.annotation.processing.Generated"),
                    source("fx/Generated.java", 16, Rule.ANNOTATION_NOT_ALLOWED, "@java.beans.BeanProperty"))),

            new Fixture("annotation-declaration", Fixture.Kind.SOURCE, List.of(
                    source("fx/Override.java", 4, Rule.ANNOTATION_DECLARATION, "@interface fx.Override"),
                    source("fx/Tag.java", 9, Rule.ANNOTATION_NOT_ALLOWED, "@java.lang.annotation.Retention"),
                    source("fx/Tag.java", 10, Rule.ANNOTATION_NOT_ALLOWED, "@java.lang.annotation.Target"),
                    source("fx/Tag.java", 11, Rule.ANNOTATION_DECLARATION, "@interface fx.Tag"),
                    // Same simple name as java.lang.Override, but attribution resolves it to fx.Override.
                    source("fx/User.java", 8, Rule.ANNOTATION_NOT_ALLOWED, "@fx.Override"),
                    source("fx/User.java", 13, Rule.ANNOTATION_NOT_ALLOWED, "@fx.Tag"))),

            new Fixture("bytecode-annotation", Fixture.Kind.BYTECODE, List.of(
                    // The annotation types themselves and their meta-annotations.
                    bytecode("fx/Marker$Visible.class", Rule.ANNOTATION_DECLARATION, "@interface fx.Marker$Visible", "class"),
                    bytecode("fx/Marker$Visible.class", Rule.ANNOTATION_NOT_ALLOWED, "@java.lang.annotation.Retention", "class"),
                    bytecode("fx/Marker$Invisible.class", Rule.ANNOTATION_DECLARATION, "@interface fx.Marker$Invisible", "class"),
                    bytecode("fx/Marker$Invisible.class", Rule.ANNOTATION_NOT_ALLOWED, "@java.lang.annotation.Retention", "class"),
                    bytecode("fx/Marker$Param.class", Rule.ANNOTATION_DECLARATION, "@interface fx.Marker$Param", "class"),
                    bytecode("fx/Marker$Param.class", Rule.ANNOTATION_NOT_ALLOWED, "@java.lang.annotation.Retention", "class"),
                    bytecode("fx/Marker$Param.class", Rule.ANNOTATION_NOT_ALLOWED, "@java.lang.annotation.Target", "class"),
                    bytecode("fx/Marker$TypeUse.class", Rule.ANNOTATION_DECLARATION, "@interface fx.Marker$TypeUse", "class"),
                    bytecode("fx/Marker$TypeUse.class", Rule.ANNOTATION_NOT_ALLOWED, "@java.lang.annotation.Retention", "class"),
                    bytecode("fx/Marker$TypeUse.class", Rule.ANNOTATION_NOT_ALLOWED, "@java.lang.annotation.Target", "class"),
                    // RuntimeVisibleAnnotations on class and method (@Deprecated on the class is allowed).
                    bytecode("fx/Marked.class", Rule.ANNOTATION_NOT_ALLOWED, "@fx.Marker$Visible", "class"),
                    bytecode("fx/Marked.class", Rule.ANNOTATION_NOT_ALLOWED, "@fx.Marker$Visible",
                            "method method(Ljava/lang/String;)V"),
                    // RuntimeInvisibleAnnotations (CLASS retention).
                    bytecode("fx/Marked.class", Rule.ANNOTATION_NOT_ALLOWED, "@fx.Marker$Invisible", "field field"),
                    // RuntimeVisibleParameterAnnotations.
                    bytecode("fx/Marked.class", Rule.ANNOTATION_NOT_ALLOWED, "@fx.Marker$Param",
                            "method method(Ljava/lang/String;)V parameter 0"),
                    // RuntimeVisibleTypeAnnotations on a field type and inside a method body.
                    bytecode("fx/Marked.class", Rule.ANNOTATION_NOT_ALLOWED, "@fx.Marker$TypeUse",
                            "field names type-use FIELD"),
                    bytecode("fx/Marked.class", Rule.ANNOTATION_NOT_ALLOWED, "@fx.Marker$TypeUse",
                            "method method(Ljava/lang/String;)V code type-use CAST"),
                    // Record component annotation, propagated by javac to field, accessor and
                    // canonical constructor parameter (RuntimeInvisibleParameterAnnotations).
                    bytecode("fx/Marked$Point.class", Rule.ANNOTATION_NOT_ALLOWED, "@fx.Marker$Invisible",
                            "record component x"),
                    bytecode("fx/Marked$Point.class", Rule.ANNOTATION_NOT_ALLOWED, "@fx.Marker$Invisible", "field x"),
                    bytecode("fx/Marked$Point.class", Rule.ANNOTATION_NOT_ALLOWED, "@fx.Marker$Invisible", "method x()I"),
                    bytecode("fx/Marked$Point.class", Rule.ANNOTATION_NOT_ALLOWED, "@fx.Marker$Invisible",
                            "method <init>(I)V parameter 0"))),

            new Fixture("foreign-requires", Fixture.Kind.DEPENDENCIES, List.of(
                    // requires java.desktop and requires static java.compiler are JDK modules: allowed.
                    bytecode("fixture.app/module-info.class", Rule.FOREIGN_REQUIRES, "requires foo.bar",
                            "module fixture.app"))),

            new Fixture("foreign-import", Fixture.Kind.SOURCE, List.of(
                    source("fx/Imports.java", 5, Rule.FOREIGN_IMPORT, "import org.junit.jupiter.api.Test"),
                    source("fx/Imports.java", 6, Rule.FOREIGN_IMPORT,
                            "import static org.assertj.core.api.Assertions.assertThat"),
                    source("fx/Imports.java", 7, Rule.FOREIGN_IMPORT, "import com.example.lib.*"),
                    source("fx/Imports.java", 8, Rule.FOREIGN_IMPORT, "import module org.example.lib"),
                    // An annotation that cannot be resolved cannot be proven allowed.
                    source("fx/Imports.java", 13, Rule.ANNOTATION_NOT_ALLOWED, "@Test (unresolved)"))),

            new Fixture("reflective-annotation-read", Fixture.Kind.SOURCE, List.of(
                    source("fx/Reader.java", 11, Rule.ANNOTATION_REFLECTION, "java.lang.Class.isAnnotationPresent"),
                    source("fx/Reader.java", 15, Rule.ANNOTATION_REFLECTION, "java.lang.reflect.Method.getAnnotation"),
                    source("fx/Reader.java", 19, Rule.ANNOTATION_REFLECTION,
                            "java.lang.reflect.Method.getDeclaredAnnotations"))),

            new Fixture("foreign-jar", Fixture.Kind.FILES, List.of(
                    // out/allowed.jar and out/nested/also-allowed.jar are in the output directory: allowed.
                    file("lib/x.jar", Rule.FOREIGN_JAR),
                    file("modules/demo/libs/Thing.JAR", Rule.FOREIGN_JAR))),

            new Fixture("foreign-build-file", Fixture.Kind.FILES, List.of(
                    // docs/pom.xml.md is not a build file: allowed.
                    file("pom.xml", Rule.FOREIGN_BUILD_FILE),
                    file("modules/a/build.gradle", Rule.FOREIGN_BUILD_FILE),
                    file("modules/b/build.gradle.kts", Rule.FOREIGN_BUILD_FILE),
                    file("settings.gradle", Rule.FOREIGN_BUILD_FILE),
                    file("build.xml", Rule.FOREIGN_BUILD_FILE))));

    private static Violation source(String file, int line, Rule rule, String subject) {
        return new Violation(file, line, rule, subject, "");
    }

    private static Violation bytecode(String file, Rule rule, String subject, String location) {
        return new Violation(file, 0, rule, subject, location);
    }

    private static Violation file(String path, Rule rule) {
        return new Violation(path, 0, rule, path, "");
    }
}
