package spola.guardrails;

import java.nio.file.Path;
import java.util.List;

/**
 * The explicit list of guardrails fixtures (at least one per rule) and, for each, every
 * violation it must produce. Lines and locations are exact: if a fixture is changed so that
 * a violation disappears, or a new one appears, the self-test fails.
 *
 * <p>Maintenance: the line numbers below refer to the fixture files as they are. Editing a
 * fixture file shifts lines, so update the numbers here in the same change; the self-test
 * then prints every "expected but not reported" / "reported but not expected" pair with
 * the actual line, which tells you the new numbers.
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
                    // Source check of the fixture's files (every fixture .java file is source-checked).
                    source("fx/Marker.java", 11, Rule.ANNOTATION_NOT_ALLOWED, "@java.lang.annotation.Retention"),
                    source("fx/Marker.java", 12, Rule.ANNOTATION_DECLARATION, "@interface fx.Marker.Visible"),
                    source("fx/Marker.java", 15, Rule.ANNOTATION_NOT_ALLOWED, "@java.lang.annotation.Retention"),
                    source("fx/Marker.java", 16, Rule.ANNOTATION_DECLARATION, "@interface fx.Marker.Invisible"),
                    source("fx/Marker.java", 19, Rule.ANNOTATION_NOT_ALLOWED, "@java.lang.annotation.Retention"),
                    source("fx/Marker.java", 20, Rule.ANNOTATION_NOT_ALLOWED, "@java.lang.annotation.Target"),
                    source("fx/Marker.java", 21, Rule.ANNOTATION_DECLARATION, "@interface fx.Marker.Param"),
                    source("fx/Marker.java", 24, Rule.ANNOTATION_NOT_ALLOWED, "@java.lang.annotation.Retention"),
                    source("fx/Marker.java", 25, Rule.ANNOTATION_NOT_ALLOWED, "@java.lang.annotation.Target"),
                    source("fx/Marker.java", 26, Rule.ANNOTATION_DECLARATION, "@interface fx.Marker.TypeUse"),
                    source("fx/Marked.java", 5, Rule.ANNOTATION_NOT_ALLOWED, "@fx.Marker.Visible"),
                    source("fx/Marked.java", 9, Rule.ANNOTATION_NOT_ALLOWED, "@fx.Marker.Invisible"),
                    source("fx/Marked.java", 12, Rule.ANNOTATION_NOT_ALLOWED, "@fx.Marker.TypeUse"),
                    source("fx/Marked.java", 14, Rule.ANNOTATION_NOT_ALLOWED, "@fx.Marker.Visible"),
                    source("fx/Marked.java", 15, Rule.ANNOTATION_NOT_ALLOWED, "@fx.Marker.Param"),
                    source("fx/Marked.java", 17, Rule.ANNOTATION_NOT_ALLOWED, "@fx.Marker.TypeUse"),
                    source("fx/Marked.java", 21, Rule.ANNOTATION_NOT_ALLOWED, "@fx.Marker.Invisible"),
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
                    source("fx/Reader.java", 16, Rule.ANNOTATION_REFLECTION, "java.lang.Class.isAnnotationPresent"),
                    source("fx/Reader.java", 20, Rule.ANNOTATION_REFLECTION, "java.lang.reflect.Method.getAnnotation"),
                    source("fx/Reader.java", 24, Rule.ANNOTATION_REFLECTION,
                            "java.lang.reflect.Method.getDeclaredAnnotations"),
                    // Parameter annotations, annotated types and annotation defaults.
                    source("fx/Reader.java", 34, Rule.ANNOTATION_REFLECTION,
                            "java.lang.reflect.Method.getParameterAnnotations"),
                    source("fx/Reader.java", 38, Rule.ANNOTATION_REFLECTION,
                            "java.lang.reflect.Method.getAnnotatedReturnType"),
                    source("fx/Reader.java", 38, Rule.ANNOTATION_REFLECTION, "java.lang.reflect.Field.getAnnotatedType"),
                    source("fx/Reader.java", 38, Rule.ANNOTATION_REFLECTION, "java.lang.Class.getAnnotatedSuperclass"),
                    source("fx/Reader.java", 42, Rule.ANNOTATION_REFLECTION, "java.lang.reflect.Method.getDefaultValue"),
                    source("fx/Reader.java", 46, Rule.ANNOTATION_REFLECTION,
                            "java.lang.reflect.Parameter.getDeclaredAnnotations"),
                    source("fx/Reader.java", 47, Rule.ANNOTATION_REFLECTION,
                            "java.lang.reflect.Parameter.getAnnotatedType"))),

            new Fixture("foreign-jar", Fixture.Kind.FILES, List.of(
                    // out/allowed.jar and out/nested/also-allowed.jar are in the output directory: allowed.
                    file("lib/x.jar", Rule.FOREIGN_JAR),
                    file("modules/demo/libs/Thing.JAR", Rule.FOREIGN_JAR),
                    // Archives that package Java code like a jar; out/allowed.jmod is allowed.
                    file("vendor/jdk.foo.jmod", Rule.FOREIGN_JAR),
                    file("deploy/app.war", Rule.FOREIGN_JAR),
                    file("deploy/app.ear", Rule.FOREIGN_JAR),
                    // Recognised by content, whatever the name; zips without Java code are allowed.
                    file("lib/thing.zip", Rule.FOREIGN_JAR),
                    file("lib/thing", Rule.FOREIGN_JAR),
                    file("lib/manifest-only.zip", Rule.FOREIGN_JAR),
                    file("lib/hidden.dat", Rule.FOREIGN_JAR),
                    file("lib/nested.zip", Rule.FOREIGN_JAR),
                    file("lib/bundle.zip", Rule.FOREIGN_JAR),
                    file("bin/run.sh", Rule.FOREIGN_JAR),
                    file("vendor/sdk", Rule.FOREIGN_JAR))),

            new Fixture("foreign-build-file", Fixture.Kind.FILES, List.of(
                    // docs/pom.xml.md is not a build file: allowed.
                    file("pom.xml", Rule.FOREIGN_BUILD_FILE),
                    file("modules/a/build.gradle", Rule.FOREIGN_BUILD_FILE),
                    file("modules/b/build.gradle.kts", Rule.FOREIGN_BUILD_FILE),
                    file("settings.gradle", Rule.FOREIGN_BUILD_FILE),
                    file("build.xml", Rule.FOREIGN_BUILD_FILE),
                    // Bazel and Make; docs/building.md is not a build file: allowed.
                    file("third_party/BUILD", Rule.FOREIGN_BUILD_FILE),
                    file("MODULE.bazel", Rule.FOREIGN_BUILD_FILE),
                    file("Makefile", Rule.FOREIGN_BUILD_FILE),
                    // Mill, Buck, Pants, version catalogs and the tool directories; docs/mvn/ is allowed.
                    file("build.mill", Rule.FOREIGN_BUILD_FILE),
                    file("tools/BUCK", Rule.FOREIGN_BUILD_FILE),
                    file("pants.toml", Rule.FOREIGN_BUILD_FILE),
                    file("gradle/libs.versions.toml", Rule.FOREIGN_BUILD_FILE),
                    file(".mvn/jvm.config", Rule.FOREIGN_BUILD_FILE),
                    file(".gradle/8.0/checksums.lock", Rule.FOREIGN_BUILD_FILE))),

            new Fixture("symbolic-link", Fixture.Kind.FILES, List.of(
                    // vendor/** are regular files and a directory: allowed.
                    file("modules/a/src/a/Evil.java", Rule.SYMBOLIC_LINK),
                    file("lib/x.jar", Rule.SYMBOLIC_LINK),
                    file("lib/x.jar", Rule.FOREIGN_JAR),
                    file("modules/b/lib", Rule.SYMBOLIC_LINK),
                    file("dangling.txt", Rule.SYMBOLIC_LINK))));

    /**
     * The directories of all listed fixtures below {@code fixturesDir}. Only these are left
     * out of the main check; anything else below {@code fixturesDir} is checked like any
     * other file (and fails the self-test).
     */
    static List<Path> directories(Path fixturesDir) {
        return ALL.stream().map(fixture -> fixturesDir.resolve(fixture.name())).toList();
    }

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
