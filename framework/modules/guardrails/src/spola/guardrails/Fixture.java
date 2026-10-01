package spola.guardrails;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A deliberately broken example under {@code modules/guardrails/fixtures/<name>/} and the
 * exact violations guardrails must report for it.
 *
 * @param name     directory name below the fixtures directory
 * @param kind     which check the fixture exercises, and how it is prepared
 * @param expected every violation the check must report, no more and no less
 */
record Fixture(String name, Kind kind, List<Violation> expected) {

    /**
     * The files of the fixture directory {@code dir} that the self-test reads when it runs
     * this fixture. This is the only place that says so: {@code SelfTest} runs the checks on
     * exactly these files, and its layout check reports every other file in the directory
     * (the main check leaves fixture directories out, so such a file would go unchecked).
     */
    List<Path> inputs(Path dir) {
        return switch (kind) {
            case SOURCE, BYTECODE -> FileTree.files(dir, List.of(), f -> FileTree.hasExtension(f, ".java"));
            case DEPENDENCIES -> Files.isDirectory(dir.resolve("src"))
                    ? FileTree.files(dir.resolve("src"), List.of(), f -> FileTree.hasExtension(f, ".java"))
                    : List.of();
            case FILES -> FileTree.files(dir, List.of(), f -> FileTree.relative(dir, f).equals("tree.txt"));
            case LAUNCHER -> FileTree.files(dir, List.of(), f -> !FileTree.hasExtension(f, ".java"));
        };
    }

    /** How a fixture is prepared and which check runs on it. */
    enum Kind {
        /** Source check on the fixture's {@code .java} files (unnamed module, not compiled). */
        SOURCE,
        /**
         * Source check on the fixture's {@code .java} files, which are then compiled to a
         * scratch directory; bytecode check on the result.
         */
        BYTECODE,
        /**
         * Source check on the fixture's {@code src/} (one directory per module), which is then
         * compiled as modules to a scratch directory; dependency and bytecode check on the result.
         */
        DEPENDENCIES,
        /**
         * The fixture's {@code tree.txt} describes a file tree (empty files, directories,
         * symbolic links and generated archives, see {@link TreeSpec}) that the self-test creates
         * in a scratch directory; file check on it.
         */
        FILES,
        /**
         * Source check of the fixture's files that do not end in {@code .java}, exactly as the
         * main check treats such files ({@link Guardrails#analyzeLauncherSources}): launcher
         * scripts and other Java source are analysed, jshell scripts reported, the rest skipped.
         */
        LAUNCHER
    }
}
