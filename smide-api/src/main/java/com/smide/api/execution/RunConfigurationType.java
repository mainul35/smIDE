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

    /**
     * The lines of a file a run can start from - a main method, a main function - which the
     * editor marks with a run icon.
     *
     * <p>Reads the text it is given, which is what the editor shows and may not be saved,
     * and does nothing slow: it is asked again as the file is edited, off the UI thread.
     */
    default List<RunMarker> markers(Workspace workspace, java.nio.file.Path file, String text) {
        return List.of();
    }

    /** The edit form; changes apply to the configuration directly. */
    Node editor(RunConfiguration configuration);
}
