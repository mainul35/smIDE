package com.smide.api.execution;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A process to run into a console tab.
 *
 * @param title      the console tab's label
 * @param command    program and arguments
 * @param workingDir where to run it
 * @param environment extra environment variables, layered over the IDE's own
 */
public record ProcessSpec(String title, List<String> command, Path workingDir, Map<String, String> environment) {

    public ProcessSpec(String title, List<String> command, Path workingDir) {
        this(title, command, workingDir, Map.of());
    }
}
