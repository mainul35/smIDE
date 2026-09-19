package com.smide.api.execution;

import com.smide.api.workspace.Workspace;

import java.util.List;
import java.util.Optional;

public interface Execution {

    /** Starts the process in a new console tab of the Run tool window and shows it. */
    ConsoleHandle run(ProcessSpec spec);

    /** Runs a configuration, remembering it as the selected one. */
    ConsoleHandle run(RunConfiguration configuration, ExecutionMode mode);

    List<ConsoleHandle> running();

    /** Run configurations saved for the workspace plus those its project auto-detects. */
    List<RunConfiguration> configurations(Workspace workspace);

    Optional<RunConfiguration> selectedConfiguration();

    void selectConfiguration(RunConfiguration configuration);

    void saveConfiguration(RunConfiguration configuration);

    void deleteConfiguration(RunConfiguration configuration);

    List<RunConfigurationType> configurationTypes();

    /**
     * Before building or running a project the IDE already knows to have errors: shows them
     * and asks whether to go ahead. True when there are none, or when the reader says so.
     * Run and Debug ask by themselves; a plugin's own Build action calls this first.
     *
     * @param action what is about to happen, as a button would say it: "Build", "Run"
     */
    default boolean proceedDespiteErrors(com.smide.api.workspace.Workspace workspace, String action) {
        return true;
    }
}
