package com.smide.plugins.shell;

import com.smide.api.Ide;
import com.smide.api.execution.CommandRunType;
import com.smide.api.execution.ExecutionMode;
import com.smide.api.execution.Forms;
import com.smide.api.util.Executables;
import com.smide.api.util.ProjectFiles;
import com.smide.api.workspace.Workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Scripts: bash, PowerShell or batch, chosen by the file's extension. Scripts near the top
 * of the project are offered in the run list - no configuration written.
 */
public final class ShellRunType extends CommandRunType {

    private static final int MAX_DETECTED = 40;

    private final Function<Ide, Optional<Path>> bash;
    private final Function<Ide, Optional<Path>> powershell;

    public ShellRunType(Ide ide, Function<Ide, Optional<Path>> bash, Function<Ide, Optional<Path>> powershell) {
        super(ide, "shell.run", "Script", "fth-terminal");
        this.bash = bash;
        this.powershell = powershell;
    }

    @Override
    protected List<Field> fields() {
        return List.of(
                Field.text("script", "Script", "build.sh, deploy.ps1 or setup.bat, relative"),
                Field.text("args", "Arguments", ""),
                Field.text("interpreter", "Interpreter", "empty: chosen from the extension"));
    }

    @Override
    protected String note() {
        return "Runs in the script's own folder. .sh and .bash use bash (Git's bash on Windows), .ps1 uses"
                + " PowerShell, .bat and .cmd use cmd.";
    }

    @Override
    protected Command command(Config c, ExecutionMode mode) {
        String written = c.get("script", "");
        if (written.isBlank()) {
            throw new IllegalStateException("Nothing to run: set a script.");
        }
        Path script = c.workspace().root().resolve(written).normalize();
        List<String> args = Forms.splitArgs(c.get("args", ""));
        List<String> cmd = new ArrayList<>(interpreterFor(script, c.get("interpreter", "")));
        cmd.add(script.toString());
        cmd.addAll(args);
        return new Command(cmd, script.getParent());
    }

    /** The interpreter and its flags for a script, from the extension unless one was chosen. */
    List<String> interpreterFor(Path script, String chosen) {
        if (!chosen.isBlank()) {
            return List.of(chosen);
        }
        String name = script.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".ps1")) {
            Path shell = powershell.apply(ide).orElseThrow(() -> new IllegalStateException(
                    "PowerShell was not found. Install it from " + ShellToolchains.PowerShell.DOWNLOAD
                            + ", or set its folder in Settings > Languages > Shell."));
            return List.of(shell.toString(), "-NoProfile", "-ExecutionPolicy", "Bypass", "-File");
        }
        if (name.endsWith(".bat") || name.endsWith(".cmd")) {
            if (!Executables.WINDOWS) {
                throw new IllegalStateException("Batch files run only on Windows.");
            }
            String comspec = System.getenv("ComSpec");
            return List.of(comspec == null || comspec.isBlank() ? "cmd.exe" : comspec, "/c");
        }
        for (String other : List.of("zsh", "fish", "ksh")) {
            if (name.endsWith("." + other)) {
                return List.of(Executables.onPath(other).map(Path::toString).orElseThrow(() ->
                        new IllegalStateException(other + " was not found on the PATH.")));
            }
        }
        Path shell = bash.apply(ide).orElseThrow(() -> new IllegalStateException(
                "bash was not found. " + (Executables.WINDOWS ? "Install Git for Windows from "
                        + ShellToolchains.Bash.DOWNLOAD + ", or set bash's folder in Settings > Languages > Shell."
                        : "Install bash, or set its folder in Settings > Languages > Shell.")));
        return List.of(shell.toString());
    }

    @Override
    protected List<Detected> find(Workspace workspace) {
        Path root = workspace.root();
        List<Detected> out = new ArrayList<>();
        List<String> extensions = new ArrayList<>(List.of("sh", "bash", "ps1"));
        if (Executables.WINDOWS) {
            extensions.addAll(List.of("bat", "cmd"));
        }
        String[] wanted = extensions.toArray(new String[0]);
        for (Path script : ProjectFiles.find(root, 2, 2000, p -> ProjectFiles.hasExtension(p, wanted))) {
            if (out.size() >= MAX_DETECTED) {
                break;
            }
            String relative = ProjectFiles.relative(root, script);
            out.add(new Detected("run " + relative, Map.of("script", relative)));
        }
        return out;
    }
}
