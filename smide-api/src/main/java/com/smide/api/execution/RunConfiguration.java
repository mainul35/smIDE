package com.smide.api.execution;

import com.smide.api.Ide;
import com.smide.api.workspace.Workspace;

import java.util.Map;

/**
 * A saved way of running something: a main class, a test, a Maven goal. Stored as a
 * flat string map in {@code <root>/.smide/run-configurations.json}.
 */
public interface RunConfiguration {

    String name();

    void setName(String name);

    RunConfigurationType type();

    Workspace workspace();

    /** The process to start. Throws with a message the user can act on. */
    ProcessSpec prepare(Ide ide, ExecutionMode mode) throws Exception;

    Map<String, String> toMap();

    void fromMap(Map<String, String> values);

    /** Auto-detected configurations are not saved unless the user edits them. */
    default boolean isTemporary() {
        return false;
    }
}
