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
        for (ProjectImporter importer : registry.importers()) {
            try {
                if (importer.detects(root)) {
                    ProjectModel model = importer.importProject(ide, workspace);
                    if (model != null) {
                        return model;
                    }
                }
            } catch (Exception e) {
                System.err.println("smIDE: importer " + importer.id() + " failed on " + root + ": " + e);
                ide.notifications().warn("Project import failed",
                        importer.id() + " could not read " + root.getFileName() + ": " + e.getMessage());
            }
        }
        return plain(root);
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
