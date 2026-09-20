package com.smide.lsp;

import com.smide.api.project.ProjectModel;
import com.smide.api.workspace.Workspace;
import org.eclipse.lsp4j.ExecuteCommandParams;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tells a language server that keeps its own model of the build to read it again.
 *
 * <p>After a {@code git pull} the sources are new and the Java server's model is not: it holds
 * the class path it worked out when the project was imported, resolved against whatever was in
 * the local repository at that moment. Every class added to a module since then is missing from
 * it, and every file that uses one is reported as broken - which is what "errors in several
 * plugins after the latest pull" is. The errors are real to the server and imaginary to the
 * compiler, and they stay until the project is imported again, because nothing has asked it to.
 *
 * <p>So reloading the project model asks. JDT takes {@code java.projectConfiguration.update} on
 * a build file and re-reads it, its modules and its dependencies; the errors that were only
 * out-of-date go with it. Only the build files at the top of each build are sent - one for a
 * reactor, not one per module - because each re-import keeps the server busy for a while.
 */
public final class ProjectReload {

    /** What a build is kept in, in the order a folder is likely to hold them. */
    private static final List<String> BUILD_FILES =
            List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");

    private ProjectReload() {
    }

    /** Asks the servers serving this workspace to read its build again. */
    public static void afterReimport(LspManager lsp, Workspace workspace, ProjectModel model) {
        if (lsp == null || workspace == null || model == null) {
            return;
        }
        List<Path> builds = buildFiles(workspace, model);
        if (builds.isEmpty()) {
            return;
        }
        for (LspSession session : lsp.sessions()) {
            if (!"jdtls".equals(session.serverId()) || session.state() != LspSession.State.READY) {
                continue;
            }
            for (Path build : builds) {
                update(session, build);
            }
        }
    }

    /**
     * What a server says when its model of the build is older than the project.
     *
     * <p>These two messages do not mean the code is wrong. The first is JDT saying a type it
     * needs is not on the class path it holds - after a pull, typically, when a class was added
     * to a module and the class path still points at the jar that module was last installed as.
     * The second is the same thing said about a whole project. Both go away when the build is
     * read again, and nothing else asks for that, so a file full of them is a file reporting an
     * error about the IDE rather than about the project.
     */
    private static final List<String> STALE = List.of(
            "is indirectly referenced from required type",
            "build path is incomplete");

    /** Workspaces already reloaded for this reason; once each, because a reload is not free. */
    private static final java.util.Set<Path> HEALED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Notices a stale build path in what a server just reported, and reads the build again.
     *
     * <p>Once per project per session: if the same errors come back afterwards they are real,
     * and reloading on every publish would keep the server importing forever.
     */
    public static void noticeStale(com.smide.api.Ide ide, LspSession session, Path file,
                                   List<com.smide.api.problems.Diagnostic> diagnostics) {
        if (ide == null || file == null || !"jdtls".equals(session.serverId())) {
            return;
        }
        boolean stale = diagnostics.stream()
                .filter(d -> d.severity() == com.smide.api.problems.Diagnostic.Severity.ERROR)
                .anyMatch(d -> d.message() != null && STALE.stream().anyMatch(d.message()::contains));
        if (!stale) {
            return;
        }
        Workspace workspace = ide.workspaces().containing(file).orElse(session.workspace());
        if (workspace == null || !HEALED.add(workspace.root())) {
            return;
        }
        ide.notifications().info("Reloading the Java project",
                "The language server's class path is older than the project - after a pull, usually."
                        + " Reading the build again; the errors it reported go with it.");
        for (Path build : buildFiles(workspace, ide.projects().modelOf(workspace).orElse(null))) {
            update(session, build);
        }
    }

    private static void update(LspSession session, Path build) {
        try {
            session.server().getWorkspaceService()
                    .executeCommand(new ExecuteCommandParams("java.projectConfiguration.update",
                            List.of(Positions.uri(build))))
                    .exceptionally(error -> {
                        System.err.println("smIDE: could not reload " + build + " - " + error);
                        return null;
                    });
        } catch (RuntimeException e) {
            System.err.println("smIDE: could not reload " + build + " - " + e);
        }
    }

    /**
     * One build file per build: the workspace root's, and the root of each build inside it that
     * the model found.
     */
    private static List<Path> buildFiles(Workspace workspace, ProjectModel model) {
        List<Path> roots = new ArrayList<>();
        roots.add(workspace.root());
        if (model != null && !roots.contains(model.root())) {
            roots.add(model.root());
        }
        List<Path> out = new ArrayList<>();
        for (Path root : roots) {
            for (String name : BUILD_FILES) {
                Path file = root.resolve(name);
                if (Files.isRegularFile(file)) {
                    out.add(file);
                    break;
                }
            }
        }
        return out;
    }
}
