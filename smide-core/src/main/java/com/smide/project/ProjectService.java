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

    private ProjectModel importModel(WorkspaceImpl workspace) {
        Path root = workspace.root();
        ProjectModel atRoot = importAt(workspace, root);
        if (atRoot != null) {
            return atRoot;
        }
        /* Nothing at the root: the build may be further in - a repository with its Gradle
           build in POISYA-administration/poisya/ - which IntelliJ finds and loads. The
           shallowest one is loaded, and the reader told where it was found. */
        for (Path dir : BuildFolders.below(root, registry.importers())) {
            ProjectModel nested = importAt(workspace, dir);
            if (nested != null) {
                if (announced.add(workspace.root() + "|" + dir)) {
                    String where = root.relativize(dir).toString().replace('\\', '/');
                    ide.window().runLater(() -> ide.notifications().info(capitalized(nested.type()) + " project loaded",
                            "Found its build in " + where + " and loaded it."));
                }
                return nested;
            }
        }
        return plain(root);
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
