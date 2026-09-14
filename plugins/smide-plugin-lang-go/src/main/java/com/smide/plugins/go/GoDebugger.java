package com.smide.plugins.go;

import com.smide.api.Ide;
import com.smide.api.debug.DebugSession;
import com.smide.api.debug.Debugger;
import com.smide.api.execution.ConsoleHandle;
import com.smide.api.execution.RunConfiguration;

import java.time.Duration;
import java.util.Map;

/**
 * Debugs Go programs and tests with Delve.
 *
 * <p>The run configuration starts {@code dlv debug} or {@code dlv test} headless in the Run
 * window, so the program's output is where it always is; this attaches to it over the Debug
 * Adapter Protocol, which a headless Delve accepts as well as its own API.
 */
final class GoDebugger implements Debugger {

    @Override
    public String id() {
        return "go.delve";
    }

    @Override
    public boolean supports(RunConfiguration configuration) {
        return GoRunType.ID.equals(configuration.type().id());
    }

    @Override
    public DebugSession attach(Ide ide, RunConfiguration configuration, int port) throws Exception {
        return attach(ide, configuration, port, null);
    }

    @Override
    public DebugSession attach(Ide ide, RunConfiguration configuration, int port, ConsoleHandle console) throws Exception {
        /* Delve builds the program before it listens, which on a first build of a large module
           takes minutes. A build that fails ends the console, and that ends the wait too. */
        return ide.debugAdapters().attach(configuration.name(), port, Map.of("mode", "remote"),
                Duration.ofMinutes(10), console);
    }
}
