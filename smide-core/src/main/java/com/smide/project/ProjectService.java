package com.smide.project;

import com.smide.api.Ide;
import com.smide.api.project.ProjectImporter;
import com.smide.api.project.ProjectModel;
import com.smide.api.project.Projects;
import com.smide.api.util.Events;
import com.smide.api.workspace.Workspace;
import com.smide.core.ExtensionRegistry;
import com.smide.workspace.WorkspaceImpl;
import javafx.application.Platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Runs project importers against workspaces on a background thread and publishes the
 * model. A folder no importer recognises gets a plain model with the root as its only
 * source root, so everything downstream can assume there is always a model.
 */
public final class ProjectService implements Projects {

    private final Ide ide;
    private final ExtensionRegistry registry;
    private final List<BiConsumer<Workspace, ProjectModel>> listeners = new CopyOnWriteArrayList<>();

    public ProjectService(Ide ide, ExtensionRegistry registry) {
        this.ide = ide;
        this.registry = registry;
    }

    @Override
    public Optional<ProjectModel> modelOf(Workspace workspace) {
        return workspace.project();
    }

    @Override
    public void reimport(Workspace workspace) {
        if (!(workspace instanceof WorkspaceImpl impl)) {
            return;
        }
        ide.window().runInBackground(() -> {
            ProjectModel model = importModel(impl);
            Platform.runLater(() -> {
                impl.setProject(model);
                listeners.forEach(l -> l.accept(impl, model));
                ide.events().publish(new Events.ProjectImported(impl));
            });
        });
    }

    /**
     * Every build in the workspace, as one model.
     *
     * <p>A repository is often more than one thing: a Maven service beside a Gradle one, a
     * Cargo tool, a web front end with its package.json. Each is found and read by the plugin
     * that knows it, and their modules and tasks are put together, so all of them are in the
     * Project tree, the build tool window and the run configurations rather than whichever
     * happened to be found first.
     */
    private ProjectModel importModel(WorkspaceImpl workspace) {
        Path root = workspace.root();
        List<ProjectModel> found = new ArrayList<>();
        ProjectModel atRoot = importAt(workspace, root);
        if (atRoot != null) {
            found.add(atRoot);
        }
        for (Path dir : BuildFolders.below(root, registry.importers())) {
            // Not what the build at the root already covers: a Maven module is Maven's business.
            if (covered(found, dir)) {
                continue;
            }
            ProjectModel nested = importAt(workspace, dir);
            if (nested != null) {
                found.add(nested);
            }
        }
        if (found.isEmpty()) {
            return plain(root);
        }
        announce(workspace, found);
        return found.size() == 1 && found.get(0).root().equals(root) ? found.get(0) : merge(root, found);
    }

    /**
     * Whether a build has already claimed this folder: it is the build itself, or one of its
     * modules.
     *
     * <p>Not merely inside it. A Maven build found at the top of a repository has the whole
     * repository as its root, and everything else in it - the Gradle service, the Cargo tool,
     * the web folder - would be taken for part of it and never loaded. What Maven claims are
     * the folders it calls its modules.
     */
    private static boolean covered(List<ProjectModel> found, Path dir) {
        for (ProjectModel model : found) {
            if (dir.equals(model.root())) {
                return true;
            }
            for (ProjectModel.ProjectModule module : model.modules()) {
                if (dir.equals(module.root())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The builds as one model: every module and every task, each module named by where it is so
     * that two builds with a module called "app" stay apart.
     */
    private static ProjectModel merge(Path root, List<ProjectModel> builds) {
        List<ProjectModel.ProjectModule> modules = new ArrayList<>();
        List<ProjectModel.BuildTask> tasks = new ArrayList<>();
        java.util.Set<String> types = new java.util.LinkedHashSet<>();
        for (ProjectModel build : builds) {
            types.add(build.type());
            for (ProjectModel.ProjectModule module : build.modules()) {
                modules.add(new ProjectModel.ProjectModule(name(root, module.root(), module.name()), module.root(),
                        module.sourceRoots(), module.testRoots(), module.resourceRoots(), module.outputDir()));
            }
            tasks.addAll(build.tasks());
        }
        String name = root.getFileName() == null ? root.toString() : root.getFileName().toString();
        return new ProjectModel(String.join("+", types), name, root, modules, tasks);
    }

    /** A module of a build inside the project, by where it is: {@code backend/app}. */
    private static String name(Path root, Path moduleRoot, String given) {
        if (moduleRoot.equals(root)) {
            return given;
        }
        try {
            return root.relativize(moduleRoot).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return given;
        }
    }

    /** Says what was loaded, once per project and set of builds. */
    private void announce(WorkspaceImpl workspace, List<ProjectModel> builds) {
        Path root = workspace.root();
        List<ProjectModel> inside = builds.stream().filter(b -> !b.root().equals(root)).toList();
        if (inside.isEmpty() && builds.size() == 1) {
            return;
        }
        StringBuilder what = new StringBuilder();
        for (ProjectModel build : builds) {
            String where = build.root().equals(root) ? "at the top"
                    : "in " + root.relativize(build.root()).toString().replace('\\', '/');
            what.append(capitalized(build.type())).append(' ').append(where).append(", ");
        }
        String listed = what.substring(0, what.length() - 2);
        if (!announced.add(root + "|" + listed)) {
            return;
        }
        ide.window().runLater(() -> ide.notifications().info(
                builds.size() == 1 ? "Project loaded" : builds.size() + " builds loaded", "Found and loaded " + listed + "."));
    }

    private final java.util.Set<String> announced = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static String capitalized(String type) {
        return type == null || type.isEmpty() ? "Build" : Character.toUpperCase(type.charAt(0)) + type.substring(1);
    }

    /** The model the first importer that recognises {@code dir} reads, or null. */
    private ProjectModel importAt(WorkspaceImpl workspace, Path dir) {
        Path root = workspace.root();
        for (ProjectImporter importer : registry.importers()) {
            try {
                if (importer.detects(dir)) {
                    ProjectModel model = dir.equals(root)
                            ? importer.importProject(ide, workspace)
                            : importer.importProject(ide, workspace, dir);
                    if (model != null) {
                        return model;
                    }
                }
            } catch (Exception e) {
                System.err.println("smIDE: importer " + importer.id() + " failed on " + dir + ": " + e);
                ide.notifications().warn("Project import failed",
                        importer.id() + " could not read " + dir.getFileName() + ": " + e.getMessage());
            }
        }
        return null;
    }

    public static ProjectModel plain(Path root) {
        String name = root.getFileName() == null ? root.toString() : root.getFileName().toString();
        ProjectModel.ProjectModule module = new ProjectModel.ProjectModule(
                name, root, List.of(root), List.of(), List.of(), null);
        return new ProjectModel("plain", name, root, List.of(module), List.of());
    }

    @Override
    public void addListener(BiConsumer<Workspace, ProjectModel> listener) {
        listeners.add(listener);
    }
}
