package com.smide.api.execution;

import com.smide.api.workspace.Workspace;
import javafx.scene.Node;

import java.util.List;

/** A kind of run configuration and the form that edits one. */
public interface RunConfigurationType {

    String id();

    String displayName();

    String iconLiteral();

    /** Whether Debug is offered for this kind. */
    default boolean supportsDebug() {
        return false;
    }

    RunConfiguration create(Workspace workspace);

    /** Configurations found by looking at the project: main classes, tests. */
    default List<RunConfiguration> detect(Workspace workspace) {
        return List.of();
    }

    /** The edit form; changes apply to the configuration directly. */
    Node editor(RunConfiguration configuration);
}
