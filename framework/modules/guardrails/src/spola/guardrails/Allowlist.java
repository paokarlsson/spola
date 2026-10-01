package spola.guardrails;

import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
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

    /** Every module in the running JDK image (REGLER.md §1). */
    static final Set<String> JDK_MODULES = ModuleFinder.ofSystem().findAll().stream()
            .map(ModuleReference::descriptor)
            .map(descriptor -> descriptor.name())
            .collect(Collectors.toUnmodifiableSet());

    private Allowlist() {
    }

    static boolean isAllowedModule(String module, Set<String> projectModules) {
        return JDK_MODULES.contains(module) || projectModules.contains(module);
    }
}
