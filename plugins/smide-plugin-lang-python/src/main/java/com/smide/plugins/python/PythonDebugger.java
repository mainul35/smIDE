package com.smide.plugins.python;

import com.smide.api.Ide;
import com.smide.api.debug.DebugAdapters;
import com.smide.api.debug.DebugSession;
import com.smide.api.debug.Debugger;
import com.smide.api.execution.CannotRunException;
import com.smide.api.execution.CommandRunType;
import com.smide.api.execution.ConsoleHandle;
import com.smide.api.execution.RunConfiguration;
import com.smide.api.ui.Notifications;
import com.smide.api.ui.StatusBar;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Debugs Python scripts, modules and pytest with debugpy.
 *
 * <p>The run configuration starts the program under {@code python -m debugpy --listen
 * --wait-for-client} in the Run window; this attaches to it over the Debug Adapter Protocol.
 * debugpy is used from the project's environment when it is installed there, and otherwise
 * from a copy in smIDE's tools folder, one for each Python version - so debugging never
 * installs anything into the reader's own environments.
 */
final class PythonDebugger implements Debugger {

    @Override
    public String id() {
        return "python.debugpy";
    }

    @Override
    public boolean supports(RunConfiguration configuration) {
        return PythonRunType.ID.equals(configuration.type().id());
    }

    @Override
    public DebugSession attach(Ide ide, RunConfiguration configuration, int port) throws Exception {
        return attach(ide, configuration, port, null);
    }

    @Override
    public DebugSession attach(Ide ide, RunConfiguration configuration, int port, ConsoleHandle console) throws Exception {
        return ide.debugAdapters().attach(configuration.name(), port, Map.of(), Duration.ofMinutes(2), console);
    }

    /**
     * The command a run configuration debugs with: the one it runs with, started through debugpy.
     *
     * @param run the interpreter and what it would run, as {@code [python, -u, script, args...]}
     */
    static CommandRunType.Command command(Ide ide, CommandRunType.Config configuration, Path interpreter,
                                          List<String> run, Path directory, Map<String, String> environment)
            throws CannotRunException {
        Map<String, String> env = new java.util.LinkedHashMap<>(environment);
        if (!importable(interpreter, null)) {
            Path copy = copyFor(ide, interpreter);
            if (copy == null || !importable(interpreter, copy)) {
                throw new CannotRunException("Debugging Python needs debugpy, which " + interpreter + " cannot import.",
                        new Notifications.NotificationAction("Install debugpy", () -> install(ide, interpreter)));
            }
            String existing = System.getenv("PYTHONPATH");
            env.put("PYTHONPATH", copy + (existing == null || existing.isBlank() ? "" : File.pathSeparator + existing));
        }
        int port = DebugAdapters.freePort();
        configuration.set("debugPort", String.valueOf(port));
        List<String> cmd = new ArrayList<>(List.of(interpreter.toString(),
                // Frozen modules make debugpy warn that it may miss breakpoints in them.
                "-X", "frozen_modules=off",
                "-m", "debugpy", "--listen", "127.0.0.1:" + port, "--wait-for-client"));
        List<String> rest = run.subList(1, run.size());
        // debugpy takes the script or -m module itself; the interpreter's own -u is not one of its options.
        cmd.addAll(!rest.isEmpty() && rest.get(0).equals("-u") ? rest.subList(1, rest.size()) : rest);
        env.put("PYDEVD_DISABLE_FILE_VALIDATION", "1");
        return new CommandRunType.Command(cmd, directory, env);
    }

    /** smIDE's own copy of debugpy for this interpreter's Python version, or null if the version cannot be read. */
    private static Path copyFor(Ide ide, Path interpreter) {
        String version = output(List.of(interpreter.toString(), "-c",
                "import sys;print(sys.version_info[0]*100+sys.version_info[1])"), Map.of());
        if (version == null || !version.matches("\\d+")) {
            return null;
        }
        return ide.downloads().toolsDir().resolve("debugpy").resolve("py" + version);
    }

    /**
     * Whether debugpy can start under this interpreter.
     *
     * <p>Its server, not just the package: debugpy carries pydevd deep inside it, and on a
     * Windows without long paths Python cannot open a file whose path passes 260 characters.
     * pydevd's are long, so a copy in a deep folder imports as a package and then dies the
     * moment it is run.
     */
    private static boolean importable(Path interpreter, Path pythonPath) {
        Map<String, String> env = pythonPath == null ? Map.of() : Map.of("PYTHONPATH", pythonPath.toString());
        return output(List.of(interpreter.toString(), "-c", "import debugpy.server"), env) != null;
    }

    static void install(Ide ide, Path interpreter) {
        StatusBar.Progress progress = ide.statusBar().progress("Installing debugpy", false);
        ide.window().runInBackground(() -> {
            try {
                Path copy = copyFor(ide, interpreter);
                if (copy == null) {
                    throw new IOException(interpreter + " did not say which Python version it is.");
                }
                ide.downloads().runTool(List.of(interpreter.toString(), "-m", "pip", "install", "--upgrade",
                        "--target", copy.toString(), "debugpy"), ide.downloads().toolsDir(), progress::update);
                if (!importable(interpreter, copy)) {
                    throw new IOException("pip finished, but debugpy in " + copy + " cannot start. On Windows, a path"
                            + " in it may be longer than Python can open there; turning on long paths fixes that.");
                }
                ide.notifications().info("debugpy installed", "Debug the configuration again to start debugging.");
            } catch (IOException | RuntimeException e) {
                ide.notifications().error("debugpy was not installed", String.valueOf(e.getMessage()));
            } finally {
                progress.done();
            }
        });
    }

    /** What a short command printed, or null when it failed or took too long. */
    private static String output(List<String> command, Map<String, String> env) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().putAll(env);
            Process process = builder.start();
            process.getOutputStream().close();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String text = new String(process.getInputStream().readAllBytes()).strip();
            return process.exitValue() == 0 ? text : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
