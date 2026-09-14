package com.smide.plugins.go;

import com.smide.api.Ide;
import com.smide.api.debug.DebugSession;
import com.smide.api.debug.Debugger;
import com.smide.api.execution.ConsoleHandle;
import com.smide.api.execution.RunConfiguration;
import com.smide.api.ui.StatusBar;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Debugs Go programs and tests with Delve.
 *
 * <p>The run configuration starts {@code dlv debug} or {@code dlv test} headless in the Run
 * window, so the program's output is where it always is; this attaches to it over the Debug
 * Adapter Protocol, which a headless Delve accepts as well as its own API.
 */
final class GoDebugger implements Debugger {

    static final String DELVE_PACKAGE = "github.com/go-delve/delve/cmd/dlv@latest";

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

    /** Installs Delve with the toolchain that will build what it debugs. */
    static void install(Ide ide, Path go) {
        StatusBar.Progress progress = ide.statusBar().progress("Installing Delve", false);
        ide.window().runInBackground(() -> {
            try {
                // -v names each package as it is built, so the task shows where it has got to.
                ide.downloads().runTool(List.of(go.toString(), "install", "-v", DELVE_PACKAGE),
                        ide.downloads().toolsDir(), progress::update);
                if (GoBinaries.find("dlv").isPresent()) {
                    ide.notifications().info("Delve installed", "Debug the configuration again to start debugging.");
                } else {
                    ide.notifications().error("Delve was not found after installing",
                            "go install finished, but dlv is not in GOPATH/bin or on the PATH.");
                }
            } catch (IOException | RuntimeException e) {
                ide.notifications().error("Delve was not installed", String.valueOf(e.getMessage()));
            } finally {
                progress.done();
            }
        });
    }
}
