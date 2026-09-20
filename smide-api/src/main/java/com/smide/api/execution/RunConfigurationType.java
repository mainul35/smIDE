package com.smide.api.execution;

import com.smide.api.workspace.Workspace;
import javafx.scene.Node;

import java.util.List;

/** A kind of run configuration and the form that edits one. */
public interface RunConfigurationType {

    String id();

    String displayName();

    String iconLiteral();

    /**
     * Where this kind comes among the others - lower first - in what is detected and offered.
     * A more specific kind goes before a general one: Spring Boot before a plain application,
     * so a Spring application is offered as one. Kinds of equal order keep the order their
     * plugins registered them in.
     */
    default int order() {
        return 100;
    }

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
     * Fills in what the project itself can answer, and names what was filled.
     *
     * <p>Behind the Detect button in the configurations dialog. A configuration made by hand
     * starts empty, and what belongs in its fields is usually not a matter of taste but of how
     * the project is built - which module holds the main class, which module assembles the
     * application, where it should run from. Anything already filled in is the reader's and is
     * left alone; what comes back are the fields that were blank and now are not, named as the
     * form names them, so the dialog can say what it did.
     */
    default List<String> complete(Workspace workspace, RunConfiguration configuration) {
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
