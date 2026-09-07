package com.smide.api.lang;

import com.smide.api.Ide;
import com.smide.api.workspace.Workspace;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How to start a language server for a workspace.
 *
 * <p>The IDE owns the process and the protocol; a plugin only says what to run. A
 * server that is not installed is offered through {@link #installRecipe()}, which is an
 * explicit action the user takes, never something that happens on opening a file.
 */
public interface LanguageServerLauncher {

    /** Stable id, used for the tools directory and the settings key. */
    String serverId();

    String displayName();

    boolean isInstalled(Ide ide);

    /** How to install the server, if the plugin knows how. */
    default Optional<InstallRecipe> installRecipe() {
        return Optional.empty();
    }

    /** The command line, resolved for this workspace. Called only when installed. */
    List<String> command(Ide ide, Workspace workspace);

    default Map<String, String> environment(Ide ide, Workspace workspace) {
        return Map.of();
    }

    default Path workingDirectory(Ide ide, Workspace workspace) {
        return workspace.root();
    }

    /** JSON-serialisable initialization options, or null. */
    default Object initializationOptions(Ide ide, Workspace workspace) {
        return null;
    }

    /** Whether one server instance serves every workspace, or one per workspace root. */
    default boolean perWorkspace() {
        return true;
    }

    /**
     * An install the user can run from Settings or from the offer shown when a file of
     * this language is first opened. {@code run} executes on a background thread and
     * reports through the progress callback; it throws to signal failure.
     */
    interface InstallRecipe {
        String description();

        void run(Ide ide, ProgressReporter progress) throws Exception;
    }

    interface ProgressReporter {
        void progress(String message, double fraction);
    }
}
