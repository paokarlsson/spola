// Spola build. Run from framework/ with the JDK 25 source launcher:
//
//     java Build.java
//
// Steps: read module-info.java of every module listed in MODULES, order the modules by their
// `requires`, compile spola.codegen and check every generated file against its record, compile
// the other modules with javac (javax.tools), package one jar per module with the JDK jar tool,
// compile each module's tests, run the guardrails check from the freshly built jar, then run
// every module's tests with spola.test (the guardrails self-test is one of them).
// Exit code 0 means green; anything else means red.
//
// Generated record builders (spola.codegen):
//
//     java Build.java codegen                          regenerate every generated file
//     java Build.java codegen FILE RECORD              generate RECORD's <Record>With.java next to FILE

import module java.compiler;
import module jdk.compiler;

import java.util.spi.ToolProvider;

/// A module's test entry: a public class in the module's test sources and a public static
/// method without parameters that returns a `spola.test.Suite` with all the module's tests.
record TestEntry(String className, String method) {
    String packageName() {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }
}

/// A project module: its JPMS name, its directory below `framework/` and its test entry.
/// Sources live in `<dir>/src`, with `module-info.java` at the top; tests live in
/// `<dir>/test`, in the module's own packages, without a `module-info.java`.
record ModuleDir(String name, Path dir, TestEntry tests) {
    Path sources() {
        return dir.resolve("src");
    }

    Path testSources() {
        return dir.resolve("test");
    }
}

/// The modules of the framework. Explicit on purpose: nothing is discovered by scanning.
/// Add a module here when you create it; order does not matter, `requires` decides it.
/// Every module has tests, and its test entry is named here, not found by convention.
static final List<ModuleDir> MODULES = List.of(
        new ModuleDir("spola.guardrails", Path.of("modules/guardrails"),
                new TestEntry("spola.guardrails.GuardrailsTests", "suite")),
        new ModuleDir("spola.test", Path.of("modules/test"),
                new TestEntry("spola.test.RunnerTests", "suite")),
        new ModuleDir("spola.di", Path.of("modules/di"),
                new TestEntry("spola.di.DiTests", "suite")),
        new ModuleDir("spola.config", Path.of("modules/config"),
                new TestEntry("spola.config.ConfigTests", "suite")),
        new ModuleDir("spola.util", Path.of("modules/util"),
                new TestEntry("spola.util.UtilTests", "suite")),
        new ModuleDir("spola.codegen", Path.of("modules/codegen"),
                new TestEntry("spola.codegen.CodegenTests", "suite")),
        new ModuleDir("spola.demo", Path.of("modules/demo"),
                new TestEntry("spola.demo.DemoTests", "suite")));

/// The test runner module. Test sources of every other module read it (`--add-reads`);
/// the module itself never `requires` it, so the shipped jar does not depend on it.
static final String TEST_RUNNER = "spola.test";

/// The generator of record builders. It is compiled first and checks every generated file
/// before anything else is compiled, so it must not require other project modules.
static final String CODEGEN = "spola.codegen";

/// First argument that makes `java Build.java` generate instead of building.
static final String CODEGEN_COMMAND = "codegen";

/// Deliberately broken guardrails examples; never compiled into an artifact.
static final Path FIXTURES = Path.of("modules/guardrails/fixtures");

/// Build output: `out/classes/<module>/`, `out/<module>.jar` and, for the tests,
/// `out/test-classes/<module>/` (the module's classes plus its test classes; never packaged).
static final Path OUT = Path.of("out");
static final Path CLASSES = OUT.resolve("classes");
static final Path TEST_CLASSES = OUT.resolve("test-classes");

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

/// The exit code the build decided on; -1 until `main` has decided. Read by the exit guard.
static volatile int decidedExitCode = -1;

/// Fail closed: the build runs code it does not control (tests, guardrails) in this JVM. If
/// anything asks the JVM to exit before `main` has decided the exit code (a test calling
/// `System.exit(0)`, say), this hook makes the build red: it halts with 1 instead of letting
/// the JVM end with that status and without a verdict. Once `main` has decided, the hook does
/// nothing, so the other shutdown hooks and the JDK's `deleteOnExit` cleanup run as usual. A
/// late `System.exit` from an abandoned test thread cannot change the decided code: once
/// shutdown has begun, further `System.exit` calls block.
///
/// `spola.test`'s `TestTool` has a guard of its own (halting with 3) while a module's tests
/// run, so that the tool fails closed when used without this build too. When a test exits
/// the JVM during the build, both guards run and whichever halts first sets the code (1 or
/// 3); both are red, which is all that matters.
void installExitGuard() {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        if (decidedExitCode >= 0) {
            return;
        }
        System.out.flush();
        System.err.println();
        System.err.println("BUILD RED: the JVM was asked to exit before the build finished"
                + " (System.exit in a test or tool?)");
        System.err.flush();
        Runtime.getRuntime().halt(1);
    }, "build exit guard"));
}

void main(String[] args) {
    if (args.length > 0 && args[0].equals(EXIT_GUARD_PROBE)) {
        exitGuardProbe(args);
        return;
    }
    installExitGuard();
    var start = System.nanoTime();
    var codegen = args.length > 0 && args[0].equals(CODEGEN_COMMAND);
    var what = codegen ? "CODEGEN" : "BUILD";
    int exitCode;
    try {
        if (codegen) {
            codegen(List.of(args).subList(1, args.length));
        } else if (args.length > 0) {
            throw new BuildFailure("unknown argument " + args[0] + "; use `java Build.java` to build or `java Build.java "
                    + CODEGEN_COMMAND + " [FILE RECORD]` to generate record builders");
        } else {
            build();
        }
        exitCode = 0;
    } catch (BuildFailure failure) {
        System.err.println(what + " FAILED: " + failure.getMessage());
        exitCode = 1;
    } catch (Exception | Error unexpected) {
        System.err.println(what + " FAILED with an unexpected error:");
        unexpected.printStackTrace();
        exitCode = 1;
    }
    var millis = (System.nanoTime() - start) / 1_000_000;
    System.out.println((exitCode == 0 ? what + " OK" : what + " RED") + " in " + millis + " ms");
    decidedExitCode = exitCode;
    System.exit(exitCode);
}

void requireFrameworkDirectory() {
    if (!Files.isRegularFile(Path.of("Build.java")) || !Files.isRegularFile(Path.of("REGLER.md"))) {
        throw new BuildFailure("run `java Build.java` from the framework/ directory");
    }
}

void build() throws IOException {
    requireFrameworkDirectory();
    var requires = readRequires();
    var order = dependencyOrder(requires);
    System.out.println("modules in build order: " + order.stream().map(ModuleDir::name).toList());

    deleteRecursively(OUT);
    Files.createDirectories(CLASSES);
    // spola.codegen first, then the check of the generated files, then everything else: a
    // generated file that is out of date gets the codegen message, not a javac error about it.
    var codegen = codegenModule(requires);
    compile(codegen);
    checkGeneratedFiles();
    for (var module : order) {
        if (module != codegen) {
            compile(module);
        }
    }
    for (var module : order) {
        jar(module);
    }
    for (var module : order) {
        compileTests(module);
    }
    runGuardrails();
    runTests(order);
    checkExitGuard();
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
    compile(module, CLASSES);
}

/// Compiles the module into `<output>/<module>/`, with the modules already in `output` on the
/// module path.
void compile(ModuleDir module, Path output) throws IOException {
    var sources = javaSources(module, module.sources());
    var options = new ArrayList<>(JAVAC_OPTIONS);
    options.addAll(List.of(
            "--module-path", output.toString(),           // modules compiled before this one
            "-d", output.resolve(module.name()).toString()));
    javac(module.name(), sources, options);
    System.out.println("compiled " + module.name() + " (" + sources.size() + " files)");
}

/// Compiles the module once more, together with its test sources, into
/// `out/test-classes/<module>/`. The test sources belong to the module's own packages, so
/// they reach package-private code. They read the test runner through `--add-reads`
/// (and `--add-modules`, since nothing `requires` it); the shipped classes and jar are the
/// ones compiled by `compile`, without tests.
void compileTests(ModuleDir module) throws IOException {
    var testDir = module.testSources();
    if (!Files.isDirectory(testDir, LinkOption.NOFOLLOW_LINKS)) {
        throw new BuildFailure("module " + module.name() + " has no test directory " + testDir
                + " (every module is tested; its entry " + module.tests().className() + " lives there)");
    }
    var tests = javaSources(module, testDir);
    var moduleInfos = tests.stream().filter(f -> f.getFileName().toString().equals("module-info.java")).toList();
    if (!moduleInfos.isEmpty()) {
        throw new BuildFailure(moduleInfos + ": test sources are part of " + module.name()
                + " and must not declare a module");
    }
    var entryFile = testDir.resolve(module.tests().className().replace('.', '/') + ".java");
    if (!Files.isRegularFile(entryFile, LinkOption.NOFOLLOW_LINKS)) {
        throw new BuildFailure("test entry " + module.tests().className() + " of " + module.name()
                + " not found: expected " + entryFile);
    }
    var sources = new ArrayList<>(javaSources(module, module.sources()));
    sources.addAll(tests);
    var options = new ArrayList<>(JAVAC_OPTIONS);
    // The public entry method returns spola.test.Suite from an exported package of a module
    // that does not `requires transitive spola.test` (it only reads it via --add-reads). That
    // is intended for test code, so the `exports` lint is off for test compilation only.
    options.addAll(List.of("-Xlint:-exports", "--module-path", CLASSES.toString()));
    if (!module.name().equals(TEST_RUNNER)) {
        options.addAll(List.of("--add-modules", TEST_RUNNER, "--add-reads", module.name() + "=" + TEST_RUNNER));
    }
    options.addAll(List.of("-d", TEST_CLASSES.resolve(module.name()).toString()));
    javac(module.name() + " tests", sources, options);
    System.out.println("compiled tests of " + module.name() + " (" + tests.size() + " files)");
}

/// Every `.java` file below `dir`. Stops on a symbolic link: guardrails only checks regular
/// files, so a linked .java (or directory) would be compiled without being checked.
List<Path> javaSources(ModuleDir module, Path dir) throws IOException {
    try (var files = Files.walk(dir)) { // does not follow links
        var all = files.sorted().toList();
        var links = all.stream().filter(Files::isSymbolicLink).map(Path::toString).toList();
        if (!links.isEmpty()) {
            throw new BuildFailure("symbolic link in the sources of " + module.name() + ": "
                    + String.join(", ", links) + " (REGLER.md 1. Inga tredjepartsberoenden;"
                    + " links can bring in unchecked code, copy the file instead)");
        }
        return all.stream()
                .filter(f -> f.toString().endsWith(".java") && Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS))
                .toList();
    }
}

void javac(String what, List<Path> sources, List<String> options) throws IOException {
    var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
    try (var fileManager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
        var ok = compiler.getTask(null, fileManager, null, options, null,
                fileManager.getJavaFileObjectsFromPaths(sources)).call();
        if (!ok) {
            throw new BuildFailure("compilation of " + what + " failed (see javac output above)");
        }
    }
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

/// spola.codegen from MODULES, after checking that it requires no project module (it is
/// compiled before the others).
ModuleDir codegenModule(Map<String, List<String>> requires) {
    var module = MODULES.stream().filter(m -> m.name().equals(CODEGEN)).findFirst()
            .orElseThrow(() -> new BuildFailure(CODEGEN + " is missing from MODULES"));
    var project = requires.get(CODEGEN).stream().filter(name -> !isJdkModule(name)).toList();
    if (!project.isEmpty()) {
        throw new BuildFailure(CODEGEN + " requires " + project + "; it is compiled and run before every other module,"
                + " so it may require JDK modules only");
    }
    return module;
}

/// The source and test directories of every module: where generated files are looked for.
List<String> generatedFileRoots() {
    var roots = new ArrayList<String>();
    for (var module : MODULES) {
        roots.add(module.sources().toString());
        roots.add(module.testSources().toString());
    }
    return roots;
}

/// Runs spola.codegen's `check` from `out/classes/spola.codegen`: every generated file is
/// generated again in memory from its record's source and compared with the file. Runs before
/// the other modules are compiled.
void checkGeneratedFiles() {
    var args = new ArrayList<>(List.of("check"));
    args.addAll(generatedFileRoots());
    System.out.println();
    System.out.println("generated files:");
    if (codegenTool(CLASSES.resolve(CODEGEN)).run(System.out, System.err, args.toArray(String[]::new)) != 0) {
        throw new BuildFailure("generated files do not match their records (see above); regenerate them with"
                + " `java Build.java " + CODEGEN_COMMAND + "` in framework/");
    }
    System.out.println();
}

/// `java Build.java codegen [FILE RECORD]`: compiles spola.codegen alone into `out/codegen/`
/// and runs it. Without arguments it regenerates every generated file in the modules' source
/// and test directories; with a file and a record name it generates that record's file.
void codegen(List<String> args) throws IOException {
    requireFrameworkDirectory();
    if (args.size() != 0 && args.size() != 2) {
        throw new BuildFailure("usage: java Build.java " + CODEGEN_COMMAND + " [FILE RECORD], e.g. java Build.java "
                + CODEGEN_COMMAND + " modules/demo/src/spola/demo/OrderLine.java OrderLine");
    }
    var codegen = codegenModule(readRequires());
    var output = OUT.resolve("codegen");
    deleteRecursively(output);
    compile(codegen, output);
    var toolArgs = new ArrayList<String>();
    if (args.isEmpty()) {
        toolArgs.add("regenerate");
        toolArgs.addAll(generatedFileRoots());
    } else {
        toolArgs.add("generate");
        toolArgs.addAll(args);
    }
    if (codegenTool(output.resolve(CODEGEN)).run(System.out, System.err, toolArgs.toArray(String[]::new)) != 0) {
        throw new BuildFailure("code generation failed (see above)");
    }
}

/// The ToolProvider of spola.codegen, loaded from its compiled classes in a fresh module layer.
ToolProvider codegenTool(Path classes) {
    var boot = ModuleLayer.boot();
    var configuration = boot.configuration().resolve(ModuleFinder.of(classes), ModuleFinder.of(), Set.of(CODEGEN));
    var layer = boot.defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());
    return findTool(layer, layer.findModule(CODEGEN).orElseThrow(), "spola-codegen");
}

/// Loads spola.guardrails from its jar into a fresh module layer and runs its ToolProvider:
/// the rule check of the whole framework, test sources included. Its self-test against the
/// fixtures is part of the module's tests (see runTests).
void runGuardrails() {
    var jarFile = OUT.resolve("spola.guardrails.jar");
    var boot = ModuleLayer.boot();
    var configuration = boot.configuration()
            .resolve(ModuleFinder.of(jarFile), ModuleFinder.of(), Set.of("spola.guardrails"));
    var layer = boot.defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());
    var guardrails = findTool(layer, layer.findModule("spola.guardrails").orElseThrow(), "spola-guardrails");

    var check = new ArrayList<>(List.of("check", "--root", ".", "--output", OUT.toString(),
            "--fixtures", FIXTURES.toString()));
    for (var module : MODULES) {
        check.addAll(List.of("--module", module.name() + "=" + module.sources(),
                "--tests", module.name() + "=" + module.testSources()));
    }
    check.addAll(List.of("--test-reads", TEST_RUNNER));
    if (guardrails.run(System.out, System.err, check.toArray(String[]::new)) != 0) {
        throw new BuildFailure("guardrails check failed (see above)");
    }
}

/// Runs the tests of every module, each in a fresh module layer holding the module compiled
/// with its tests (`out/test-classes/<module>`), the test runner and the jars of the other
/// modules. The layer gets the same readability the test compilation had (`--add-reads`), and
/// the entry's package is exported to the runner so that it can call the entry method. All
/// modules are run, then the build fails if any of them had a failing test. Tests run with
/// `framework/` as working directory.
void runTests(List<ModuleDir> order) {
    var failed = new ArrayList<String>();
    for (var module : order) {
        System.out.println();
        System.out.println("tests of " + module.name() + ":");
        var paths = new ArrayList<Path>();
        paths.add(TEST_CLASSES.resolve(module.name()));
        for (var other : MODULES) {
            if (!other.name().equals(module.name())) {
                paths.add(OUT.resolve(other.name() + ".jar"));
            }
        }
        var boot = ModuleLayer.boot();
        var configuration = boot.configuration().resolve(ModuleFinder.of(paths.toArray(Path[]::new)),
                ModuleFinder.of(), Set.copyOf(List.of(module.name(), TEST_RUNNER)));
        var controller = ModuleLayer.defineModulesWithOneLoader(configuration, List.of(boot),
                ClassLoader.getSystemClassLoader());
        var layer = controller.layer();
        var tested = layer.findModule(module.name()).orElseThrow();
        var runnerModule = layer.findModule(TEST_RUNNER).orElseThrow();
        var entry = module.tests();
        if (!tested.getPackages().contains(entry.packageName())) {
            throw new BuildFailure("test entry " + entry.className() + ": package " + entry.packageName()
                    + " is not in module " + module.name());
        }
        if (tested != runnerModule) {
            controller.addReads(tested, runnerModule);
            controller.addExports(tested, entry.packageName(), runnerModule);
        }
        var runner = findTool(layer, runnerModule, "spola-test");
        int exit = runner.run(System.out, System.err,
                "--module", module.name(), "--class", entry.className(), "--method", entry.method());
        if (exit != 0) {
            failed.add(module.name() + " (exit code " + exit + ")");
        }
    }
    if (!failed.isEmpty()) {
        throw new BuildFailure("tests failed in " + String.join(", ", failed) + " (see the reports above)");
    }
}

/// First argument that makes `java Build.java` run the exit-guard probe instead of building.
static final String EXIT_GUARD_PROBE = "--exit-guard-probe";

/// Checks the exit guard in two child JVMs (`java Build.java --exit-guard-probe ...`), since a
/// shutdown hook can only be observed from outside: after `main` has decided, `System.exit`
/// must end with the decided code and still run the other shutdown hooks and `deleteOnExit`;
/// before it has decided, `System.exit(0)` must end red (1).
void checkExitGuard() throws IOException {
    System.out.println();
    System.out.println("exit guard:");
    var dir = Files.createTempDirectory("spola-exit-guard");
    try {
        var decided = probe("decided", dir);
        var doomed = dir.resolve("decided", "delete-on-exit");
        var marker = dir.resolve("decided", "hook-ran");
        if (decided.exit() != 0 || Files.exists(doomed) || !Files.exists(marker)) {
            throw new BuildFailure("exit guard: after the decision a System.exit(0) must exit 0 and let"
                    + " the other shutdown hooks and deleteOnExit run; got exit " + decided.exit()
                    + ", delete-on-exit " + (Files.exists(doomed) ? "left behind" : "deleted")
                    + ", hook " + (Files.exists(marker) ? "ran" : "did not run")
                    + "\n" + decided.output());
        }
        System.out.println("  ok - after the decision: exit 0, other hooks and deleteOnExit ran");
        var undecided = probe("undecided", dir);
        if (undecided.exit() != 1 || !undecided.output().contains("BUILD RED: the JVM was asked to exit")) {
            throw new BuildFailure("exit guard: a System.exit(0) before the decision must exit 1; got exit "
                    + undecided.exit() + "\n" + undecided.output());
        }
        System.out.println("  ok - before the decision: System.exit(0) ends red (exit 1)");
    } finally {
        deleteRecursively(dir);
    }
}

record ProbeRun(int exit, String output) {
}

ProbeRun probe(String mode, Path dir) throws IOException {
    var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    var process = new ProcessBuilder(java, "Build.java", EXIT_GUARD_PROBE, mode, dir.toString())
            .redirectErrorStream(true)
            .start();
    process.getOutputStream().close();
    var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    try {
        return new ProbeRun(process.waitFor(), output);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new BuildFailure("interrupted while waiting for the exit-guard probe");
    }
}

/// The child side of `checkExitGuard`: `--exit-guard-probe decided|undecided DIR`. Installs
/// the guard, a `deleteOnExit` file and an application hook that leaves a marker, then calls
/// `System.exit(0)`, with or without a decided exit code.
void exitGuardProbe(String[] args) {
    var dir = Path.of(args[2]).resolve(args[1]);
    try {
        installExitGuard();
        Files.createDirectories(dir);
        var doomed = Files.createFile(dir.resolve("delete-on-exit"));
        doomed.toFile().deleteOnExit();
        var marker = dir.resolve("hook-ran");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Thread.sleep(200); // give a halting guard every chance to cut this hook off
                Files.createFile(marker);
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }, "probe hook"));
    } catch (IOException e) {
        throw new UncheckedIOException(e);
    }
    if (args[1].equals("decided")) {
        decidedExitCode = 0;
    }
    System.exit(0);
}

/// The ToolProvider named `name` that `module` (in `layer`) provides.
ToolProvider findTool(ModuleLayer layer, Module module, String name) {
    return ServiceLoader.load(layer, ToolProvider.class).stream()
            .filter(provider -> provider.type().getModule() == module)
            .map(ServiceLoader.Provider::get)
            .filter(tool -> tool.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new BuildFailure(module.getName() + " provides no ToolProvider named " + name));
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
