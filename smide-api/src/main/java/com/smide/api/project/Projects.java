package com.smide.api.project;

import com.smide.api.workspace.Workspace;

import java.util.Optional;
import java.util.function.BiConsumer;

public interface Projects {

    Optional<ProjectModel> modelOf(Workspace workspace);

    /** Re-runs the importer, for example after {@code pom.xml} changed. */
    void reimport(Workspace workspace);

    /** Notified with the workspace and its new model whenever an import completes. */
    void addListener(BiConsumer<Workspace, ProjectModel> listener);
}
