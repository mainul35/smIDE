package com.smide.plugins.java.run;

import com.smide.api.Ide;
import com.smide.plugins.java.JavaTools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Where Maven has to be run for a module, and whether it needs {@code -pl}.
 *
 * <p>A workspace root is not always a Maven project. Opening a repository that holds
 * {@code server/}, {@code web/} and {@code docs/} with no aggregator at the top is
 * ordinary, and running Maven at that root with {@code -pl server} fails before it
 * starts: there is no reactor there to select from. So the reactor is looked for rather
 * than assumed - up from the module while the directories above it are Maven builds too,
 * stopping at the workspace - and {@code -pl} is added only when the module really is
 * one project inside a larger one.
 *
 * @param directory where to start Maven
 * @param selector  the {@code -pl} value, empty when Maven runs in the module itself
 */
public record MavenLayout(Path directory, String selector) {

    /** Files that mean "a build lives here". */
    private static final List<String> BUILD_FILES =
            List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");

    private static final List<String> NOT_PROJECTS =
            List.of("target", "build", "out", "bin", "node_modules", "dist", ".git", ".smide", ".idea");

    public static MavenLayout of(Path workspaceRoot, Path moduleDir) {
        Path module = moduleDir == null ? workspaceRoot : moduleDir.normalize();
        if (!isMavenProject(module)) {
            module = buildRootFor(workspaceRoot);
        }
        Path reactor = module;
        for (Path parent = reactor.getParent();
             parent != null && !parent.equals(reactor) && parent.startsWith(workspaceRoot) && isMavenProject(parent);
             parent = reactor.getParent()) {
            reactor = parent;
        }
        String selector = reactor.equals(module) ? "" : reactor.relativize(module).toString().replace('\\', '/');
        return new MavenLayout(reactor, selector);
    }

    /** The Maven command for this layout, with {@code -pl <module> -am} when one is needed. */
    public List<String> command(Ide ide) {
        List<String> cmd = JavaTools.maven(ide, directory);
        if (!selector.isEmpty()) {
            cmd.add("-pl");
            cmd.add(selector);
            // Sibling modules the selected one depends on have to be built too.
            cmd.add("-am");
        }
        return cmd;
    }

    public boolean isReactor() {
        return !selector.isEmpty();
    }

    /**
     * The directory a build tool should be started in for a workspace: the root when it
     * holds a build, otherwise the first build found a level or two down.
     */
    public static Path buildRootFor(Path workspaceRoot) {
        if (hasBuildFile(workspaceRoot)) {
            return workspaceRoot;
        }
        List<Path> found = new ArrayList<>();
        search(workspaceRoot, found, 0);
        return found.isEmpty() ? workspaceRoot : found.get(0);
    }

    private static void search(Path dir, List<Path> found, int depth) {
        if (depth > 2 || !found.isEmpty()) {
            return;
        }
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                String name = child.getFileName().toString();
                if (name.startsWith(".") || NOT_PROJECTS.contains(name)) {
                    continue;
                }
                if (hasBuildFile(child)) {
                    found.add(child);
                    return;
                }
                search(child, found, depth + 1);
            }
        } catch (IOException | RuntimeException ignored) {
            // A directory we cannot read holds no build we can use.
        }
    }

    private static boolean isMavenProject(Path dir) {
        return Files.isRegularFile(dir.resolve("pom.xml"));
    }

    private static boolean hasBuildFile(Path dir) {
        return BUILD_FILES.stream().anyMatch(f -> Files.isRegularFile(dir.resolve(f)));
    }
}
