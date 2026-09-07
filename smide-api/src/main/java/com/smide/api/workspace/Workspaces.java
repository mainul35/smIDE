package com.smide.api.workspace;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public interface Workspaces {

    List<Workspace> all();

    /** The workspace whose tab is selected. */
    Optional<Workspace> active();

    /** Opens {@code root} as a workspace, or selects it if it is already open. */
    Workspace open(Path root);

    /** The open workspace containing {@code file}, if any. */
    Optional<Workspace> containing(Path file);

    /** Closes the workspace and its documents, prompting for unsaved changes. Returns false if cancelled. */
    boolean close(Workspace workspace);

    /** Folders opened before, most recent first. */
    List<Path> recent();

    void addOpenedListener(Consumer<Workspace> listener);

    void addClosedListener(Consumer<Workspace> listener);

    void addActiveListener(Consumer<Optional<Workspace>> listener);
}
