package com.smide.api.debug;

import com.smide.api.Ide;
import com.smide.api.execution.RunConfiguration;

/**
 * Starts a debug session for a run configuration.
 *
 * <p>Registered by a language plugin; the core asks the one whose {@link #supports}
 * accepts the configuration being debugged.
 */
public interface Debugger {

    String id();

    boolean supports(RunConfiguration configuration);

    /**
     * Attaches to a process that has already been started with a debug agent.
     *
     * <p>Called on a background thread: connecting waits for the other side. Throws with
     * a message the user can act on.
     */
    DebugSession attach(Ide ide, RunConfiguration configuration, int port) throws Exception;

    /**
     * Attaches, knowing the console the program was started in: a debugger waiting for the
     * program to open its port can stop waiting once the program has ended - a build that
     * failed, say - instead of waiting out its whole timeout.
     */
    default DebugSession attach(Ide ide, RunConfiguration configuration, int port,
                                com.smide.api.execution.ConsoleHandle console) throws Exception {
        return attach(ide, configuration, port);
    }
}
