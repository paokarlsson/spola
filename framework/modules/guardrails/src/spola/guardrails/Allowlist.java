package spola.guardrails;

import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

/** The allowlists from {@code REGLER.md}, as plain Java data. */
final class Allowlist {

    /** REGLER.md §2: the only annotations that may appear anywhere. */
    static final Set<String> ANNOTATIONS = Set.of(
            "java.lang.Override",
            "java.lang.SuppressWarnings",
            "java.io.Serial",
            "java.lang.FunctionalInterface",
            "java.lang.SafeVarargs",
            "java.lang.Deprecated");

    /** The same annotations as field descriptors, for bytecode ({@code Ljava/lang/Deprecated;}). */
    static final Set<String> ANNOTATION_DESCRIPTORS = ANNOTATIONS.stream()
            .map(name -> "L" + name.replace('.', '/') + ";")
            .collect(Collectors.toUnmodifiableSet());

    /**
     * The only files besides {@code .java} sources that may lie outside the build output
     * directory and the fixture directories: Markdown documents (REGLER.md §5). The source
     * launcher and jshell run a file of any name, so any other file could be Java code the
     * source check never sees; see {@link LauncherCheck}. Adding an extension here is a
     * deliberate change to what guardrails accepts.
     */
    static final Set<String> DOCUMENT_EXTENSIONS = Set.of(".md");

    /** Every module in the running JDK image (REGLER.md §1). */
    static final Set<String> JDK_MODULES = ModuleFinder.ofSystem().findAll().stream()
            .map(ModuleReference::descriptor)
            .map(descriptor -> descriptor.name())
            .collect(Collectors.toUnmodifiableSet());

    private Allowlist() {
    }

    static boolean isDocument(Path file) {
        var name = file.getFileName().toString();
        return DOCUMENT_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    static boolean isAllowedModule(String module, Set<String> projectModules) {
        return JDK_MODULES.contains(module) || projectModules.contains(module);
    }
}
