// Spola build. Run from framework/ with the JDK 25 source launcher:
//
//     java Build.java
//
// Steps: read module-info.java of every module listed in MODULES, order the modules by their
// `requires`, compile each one with javac (javax.tools), package one jar per module with the
// JDK jar tool, then run guardrails (check + self-test) from the freshly built jar.
// Exit code 0 means green; anything else means red.

import module java.compiler;
import module jdk.compiler;

import java.util.spi.ToolProvider;

/// A project module: its JPMS name and its directory below `framework/`.
/// Sources live in `<dir>/src`, with `module-info.java` at the top.
record ModuleDir(String name, Path dir) {
    Path sources() {
        return dir.resolve("src");
    }
}

/// The modules of the framework. Explicit on purpose: nothing is discovered by scanning.
/// Add a module here when you create it; order does not matter, `requires` decides it.
static final List<ModuleDir> MODULES = List.of(
        new ModuleDir("spola.guardrails", Path.of("modules/guardrails")));

/// Deliberately broken guardrails examples; never compiled into an artifact.
static final Path FIXTURES = Path.of("modules/guardrails/fixtures");

/// Build output: `out/classes/<module>/` and `out/<module>.jar`.
static final Path OUT = Path.of("out");
static final Path CLASSES = OUT.resolve("classes");

/// javac options for framework code. Lint warnings are errors; `-deprecation` and `-removal`
/// are left out so that marking an API `@Deprecated` (also `forRemoval = true`) never breaks
/// the build. Doclint enforces Javadoc on public and protected API (REGLER.md §5).
static final List<String> JAVAC_OPTIONS = List.of(
        "--release", "25",
        "-encoding", "UTF-8",
        "-proc:none",
        "-Xlint:all,-deprecation,-removal",
        "-Xdoclint:all/protected",
        "-Werror");

/// A failed build step, reported without a stack trace.
static final class BuildFailure extends RuntimeException {
    BuildFailure(String message) {
        super(message);
    }
}

void main() {
    var start = System.nanoTime();
    int exitCode;
    try {
        build();
        exitCode = 0;
    } catch (BuildFailure failure) {
        System.err.println("BUILD FAILED: " + failure.getMessage());
        exitCode = 1;
    } catch (Exception | Error unexpected) {
        System.err.println("BUILD FAILED with an unexpected error:");
        unexpected.printStackTrace();
        exitCode = 1;
    }
    var millis = (System.nanoTime() - start) / 1_000_000;
    System.out.println((exitCode == 0 ? "BUILD OK" : "BUILD RED") + " in " + millis + " ms");
    System.exit(exitCode);
}

void build() throws IOException {
    if (!Files.isRegularFile(Path.of("Build.java")) || !Files.isRegularFile(Path.of("REGLER.md"))) {
        throw new BuildFailure("run `java Build.java` from the framework/ directory");
    }
    var order = dependencyOrder(readRequires());
    System.out.println("modules in build order: " + order.stream().map(ModuleDir::name).toList());

    deleteRecursively(OUT);
    Files.createDirectories(CLASSES);
    for (var module : order) {
        compile(module);
    }
    for (var module : order) {
        jar(module);
    }
    runGuardrails();
}

/// Reads `module-info.java` of every listed module and returns name -> required modules.
/// Fails early, with file and line, on a `requires` that is neither a JDK module nor a
/// listed module. This duplicates guardrails' DependencyCheck (which checks the compiled
/// `module-info.class`) on purpose: javac would otherwise stop first with a "module not
/// found" that does not name the rule. Change both if the rule changes.
Map<String, List<String>> readRequires() throws IOException {
    var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
    var requires = new LinkedHashMap<String, List<String>>();
    for (var module : MODULES) {
        var moduleInfo = module.sources().resolve("module-info.java");
        if (!Files.isRegularFile(moduleInfo)) {
            throw new BuildFailure("module " + module.name() + ": missing " + moduleInfo);
        }
        try (var fileManager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(null, fileManager, null, List.of(), null,
                    fileManager.getJavaFileObjects(moduleInfo));
            for (var unit : task.parse()) {
                var declaration = unit.getModule();
                if (declaration == null) {
                    throw new BuildFailure(moduleInfo + ": no module declaration");
                }
                var declared = declaration.getName().toString();
                if (!declared.equals(module.name())) {
                    throw new BuildFailure(moduleInfo + ": declares module " + declared
                            + " but Build.java lists it as " + module.name());
                }
                var positions = Trees.instance(task).getSourcePositions();
                var names = new ArrayList<String>();
                for (var directive : declaration.getDirectives()) {
                    if (directive instanceof RequiresTree required) {
                        var name = required.getModuleName().toString();
                        if (!isJdkModule(name) && MODULES.stream().noneMatch(m -> m.name().equals(name))) {
                            var line = unit.getLineMap().getLineNumber(positions.getStartPosition(unit, required));
                            throw new BuildFailure(moduleInfo + ":" + line + ": requires " + name
                                    + " is neither a JDK module nor a module listed in Build.java"
                                    + " (REGLER.md 1. Inga tredjepartsberoenden)");
                        }
                        names.add(name);
                    }
                }
                requires.put(declared, List.copyOf(names));
            }
        }
    }
    return requires;
}

boolean isJdkModule(String name) {
    return ModuleFinder.ofSystem().find(name).isPresent();
}

/// Topological order of MODULES: every module comes after the project modules it requires.
List<ModuleDir> dependencyOrder(Map<String, List<String>> requires) {
    var byName = new LinkedHashMap<String, ModuleDir>();
    MODULES.forEach(m -> byName.put(m.name(), m));
    var ordered = new ArrayList<ModuleDir>();
    var done = new HashSet<String>();
    var inProgress = new LinkedHashSet<String>();
    for (var module : MODULES) {
        visit(module.name(), byName, requires, done, inProgress, ordered);
    }
    return ordered;
}

void visit(String name, Map<String, ModuleDir> byName, Map<String, List<String>> requires,
           Set<String> done, Set<String> inProgress, List<ModuleDir> ordered) {
    if (done.contains(name)) {
        return;
    }
    if (!inProgress.add(name)) {
        throw new BuildFailure("module dependency cycle: " + String.join(" -> ", inProgress) + " -> " + name);
    }
    for (var dependency : requires.get(name)) {
        if (byName.containsKey(dependency)) { // JDK modules need no ordering
            visit(dependency, byName, requires, done, inProgress, ordered);
        }
    }
    inProgress.remove(name);
    done.add(name);
    ordered.add(byName.get(name));
}

void compile(ModuleDir module) throws IOException {
    List<Path> sources;
    try (var files = Files.walk(module.sources())) { // does not follow links
        var all = files.sorted().toList();
        // Guardrails only checks regular files, so a linked .java (or directory) would be
        // compiled without being checked. Stop here; guardrails reports links as well.
        var links = all.stream().filter(Files::isSymbolicLink).map(Path::toString).toList();
        if (!links.isEmpty()) {
            throw new BuildFailure("symbolic link in the sources of " + module.name() + ": "
                    + String.join(", ", links) + " (REGLER.md 1. Inga tredjepartsberoenden;"
                    + " links can bring in unchecked code, copy the file instead)");
        }
        sources = all.stream()
                .filter(f -> f.toString().endsWith(".java") && Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS))
                .toList();
    }
    var options = new ArrayList<>(JAVAC_OPTIONS);
    options.addAll(List.of(
            "--module-path", CLASSES.toString(),          // modules compiled before this one
            "-d", CLASSES.resolve(module.name()).toString()));
    var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
    try (var fileManager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
        var ok = compiler.getTask(null, fileManager, null, options, null,
                fileManager.getJavaFileObjectsFromPaths(sources)).call();
        if (!ok) {
            throw new BuildFailure("compilation of " + module.name() + " failed (see javac output above)");
        }
    }
    System.out.println("compiled " + module.name() + " (" + sources.size() + " files)");
}

void jar(ModuleDir module) {
    var jarTool = ToolProvider.findFirst("jar").orElseThrow(() -> new BuildFailure("JDK jar tool not found"));
    var jarFile = OUT.resolve(module.name() + ".jar");
    int exit = jarTool.run(System.out, System.err,
            "--create", "--file", jarFile.toString(), "-C", CLASSES.resolve(module.name()).toString(), ".");
    if (exit != 0) {
        throw new BuildFailure("jar for " + module.name() + " failed with exit code " + exit);
    }
    System.out.println("packaged " + jarFile);
}

/// Loads spola.guardrails from its jar into a fresh module layer and runs its ToolProvider:
/// first the rule check of the whole framework, then the self-test against the fixtures.
void runGuardrails() {
    var jarFile = OUT.resolve("spola.guardrails.jar");
    var boot = ModuleLayer.boot();
    var configuration = boot.configuration()
            .resolve(ModuleFinder.of(jarFile), ModuleFinder.of(), Set.of("spola.guardrails"));
    var layer = boot.defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());
    var guardrails = ServiceLoader.load(layer, ToolProvider.class).stream()
            .filter(provider -> provider.type().getModule().getLayer() == layer)
            .map(ServiceLoader.Provider::get)
            .filter(tool -> tool.name().equals("spola-guardrails"))
            .findFirst()
            .orElseThrow(() -> new BuildFailure("spola.guardrails provides no ToolProvider named spola-guardrails"));

    var check = new ArrayList<>(List.of("check", "--root", ".", "--output", OUT.toString(),
            "--exclude", FIXTURES.toString()));
    for (var module : MODULES) {
        check.addAll(List.of("--module", module.name() + "=" + module.sources()));
    }
    if (guardrails.run(System.out, System.err, check.toArray(String[]::new)) != 0) {
        throw new BuildFailure("guardrails check failed (see above)");
    }
    if (guardrails.run(System.out, System.err,
            "selftest", "--fixtures", FIXTURES.toString(), "--rules", "REGLER.md") != 0) {
        throw new BuildFailure("guardrails self-test failed (see above)");
    }
}

void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
        return;
    }
    try (var paths = Files.walk(root)) {
        for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
            Files.delete(path);
        }
    }
}
