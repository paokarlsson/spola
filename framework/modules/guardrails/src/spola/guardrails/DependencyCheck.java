package spola.guardrails;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.Attributes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Dependency check: reads {@code module-info.class} files and reports every {@code requires}
 * (including {@code static} and {@code transitive}) that is neither a JDK module nor a
 * project module.
 *
 * <p>{@code Build.java} (method {@code readRequires}) applies the same rule to
 * {@code module-info.java} before compiling, to give a clear error before javac's "module
 * not found". Change both if the rule changes.
 */
final class DependencyCheck {

    private DependencyCheck() {
    }

    static List<Violation> run(Path root, List<Path> moduleInfoFiles, Set<String> projectModules) {
        var out = new ArrayList<Violation>();
        for (var moduleInfo : moduleInfoFiles) {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(moduleInfo);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            var file = FileTree.relative(root, moduleInfo);
            var model = BytecodeCheck.parse(bytes, file);
            model.findAttribute(Attributes.module()).ifPresent(module -> {
                var self = module.moduleName().name().stringValue();
                for (var requires : module.requires()) {
                    var name = requires.requires().name().stringValue();
                    if (!Allowlist.isAllowedModule(name, projectModules)) {
                        out.add(new Violation(file, 0, Rule.FOREIGN_REQUIRES, "requires " + name, "module " + self));
                    }
                }
            });
        }
        return List.copyOf(out);
    }
}
