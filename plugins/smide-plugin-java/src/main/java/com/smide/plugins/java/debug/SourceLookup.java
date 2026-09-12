package com.smide.plugins.java.debug;

import com.smide.api.project.ProjectModel;
import com.smide.api.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The file a stack frame came from.
 *
 * <p>A debugger knows a frame's source only as {@code com/smide/Launcher.java} - the path
 * inside the class file, relative to whatever source root it was compiled from - and the
 * root is not in there. Looking under the workspace and its usual {@code src/main/java}
 * finds it in a single-module project and nothing at all in a multi-module one, where
 * every source root is a directory further down. That is why double-clicking a frame in
 * this very IDE did nothing: {@code smide-core/src/main/java/com/smide/Launcher.java} is
 * not {@code src/main/java/com/smide/Launcher.java}, and a frame with no file is a frame
 * that silently cannot be opened.
 *
 * <p>So the build's own answer is asked for first - the project model lists every
 * module's source roots - and the guesses are only what is left when there is no model.
 */
final class SourceLookup {

    /** Where a source root sits under a module, when nothing has told us. */
    private static final List<String> USUAL = List.of(
            "src/main/java", "src/test/java", "src/main/kotlin", "src/test/kotlin", "src", "");

    private SourceLookup() {
    }

    /**
     * @param sourcePath the path inside the class file, such as {@code com/smide/Foo.java}
     */
    static Optional<Path> find(List<Workspace> workspaces, String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return Optional.empty();
        }
        String relative = sourcePath.replace('\\', '/');
        for (Workspace workspace : workspaces) {
            Optional<Path> found = inModel(workspace, relative)
                    .or(() -> under(workspace.root(), relative))
                    .or(() -> inModules(workspace.root(), relative));
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** What the build says: every module's source and test roots, as they really are. */
    private static Optional<Path> inModel(Workspace workspace, String relative) {
        Optional<ProjectModel> model;
        try {
            model = workspace.project();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (model.isEmpty()) {
            return Optional.empty();
        }
        for (ProjectModel.ProjectModule module : model.get().modules()) {
            for (List<Path> roots : List.of(module.sourceRoots(), module.testRoots())) {
                for (Path root : roots) {
                    Path candidate = root.resolve(relative);
                    if (Files.isRegularFile(candidate)) {
                        return Optional.of(candidate);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** The usual places directly under one directory. */
    private static Optional<Path> under(Path base, String relative) {
        for (String root : USUAL) {
            Path candidate = root.isEmpty() ? base.resolve(relative) : base.resolve(root).resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * One directory down, which is where a module lives.
     *
     * <p>Only one level, and only the usual roots inside it: this runs on every step and
     * a walk of a large repository is not something to do while somebody is stepping.
     */
    private static Optional<Path> inModules(Path root, String relative) {
        try (Stream<Path> children = Files.list(root)) {
            for (Path module : children.filter(Files::isDirectory).toList()) {
                Optional<Path> found = under(module, relative);
                if (found.isPresent()) {
                    return found;
                }
                // Nested module groups - plugins/smide-plugin-java - are one more down.
                try (Stream<Path> nested = Files.list(module)) {
                    for (Path inner : nested.filter(Files::isDirectory).toList()) {
                        Optional<Path> deeper = under(inner, relative);
                        if (deeper.isPresent()) {
                            return deeper;
                        }
                    }
                } catch (IOException | RuntimeException ignored) {
                    // Unreadable directories simply have nothing to offer.
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Same.
        }
        return Optional.empty();
    }
}
