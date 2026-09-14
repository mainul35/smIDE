package com.smide.plugins.python;

import com.smide.api.Ide;
import com.smide.api.execution.CommandRunType;
import com.smide.api.execution.ExecutionMode;
import com.smide.api.execution.Forms;
import com.smide.api.util.ProjectFiles;
import com.smide.api.workspace.Workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Python scripts, modules and pytest. Every script with a {@code __main__} block is offered
 * in the run list, and a project with tests gets pytest - no configuration written.
 */
public final class PythonRunType extends CommandRunType {

    private static final Pattern MAIN = Pattern.compile("if\\s+__name__\\s*==\\s*[\"']__main__[\"']");
    private static final int MAX_DETECTED = 50;

    static final String ID = "python.run";

    private final Function<Ide, Optional<Path>> python;

    /** @param python finds an interpreter installed on the machine, or nothing */
    public PythonRunType(Ide ide, Function<Ide, Optional<Path>> python) {
        super(ide, ID, "Python", "mdi2l-language-python");
        this.python = python;
    }

    @Override
    protected List<Field> fields() {
        return List.of(
                Field.choice("kind", "Kind", List.of("script", "module", "pytest")),
                Field.text("target", "Script, module or tests", "main.py, package.module or tests/"),
                Field.text("args", "Arguments", ""),
                Field.text("interpreter", "Interpreter", "empty: the project's .venv, else the Python found"));
    }

    @Override
    protected Map<String, String> defaults() {
        return Map.of("kind", "script");
    }

    @Override
    public boolean supportsDebug() {
        return true;
    }

    @Override
    protected String note() {
        return "Uses the project's .venv, venv or env when there is one, so the packages installed there are importable.";
    }

    @Override
    protected Command command(Config c, ExecutionMode mode) throws Exception {
        Path root = c.workspace().root();
        Path interpreter = interpreter(c, root);
        String kind = c.get("kind", "script");
        String target = c.get("target", "");
        List<String> cmd = new ArrayList<>();
        cmd.add(interpreter.toString());
        switch (kind) {
            case "module" -> {
                require(target, "a module to run, such as package.module");
                cmd.addAll(List.of("-u", "-m", target));
            }
            case "pytest" -> {
                cmd.addAll(List.of("-m", "pytest"));
                if (!target.isBlank()) {
                    cmd.add(target);
                }
            }
            default -> {
                require(target, "a script to run, such as main.py");
                cmd.addAll(List.of("-u", target));
            }
        }
        cmd.addAll(Forms.splitArgs(c.get("args", "")));
        // Unbuffered and UTF-8, so output reaches the console as it happens and non-ASCII prints on Windows.
        Map<String, String> env = Map.of("PYTHONUNBUFFERED", "1", "PYTHONIOENCODING", "utf-8");
        if (mode == ExecutionMode.DEBUG) {
            return PythonDebugger.command(ide, c, interpreter, cmd, root, env);
        }
        return new Command(cmd, root, env);
    }

    private Path interpreter(Config c, Path root) {
        String chosen = c.get("interpreter", "");
        if (!chosen.isBlank()) {
            return Path.of(chosen);
        }
        return PythonToolchain.venvInterpreter(root)
                .or(() -> python.apply(ide))
                .orElseThrow(() -> new IllegalStateException("Python was not found. Install it from "
                        + PythonToolchain.DOWNLOAD + ", or set its folder in Settings > Languages > Python."));
    }

    private static void require(String value, String what) {
        if (value.isBlank()) {
            throw new IllegalStateException("Nothing to run: set " + what + ".");
        }
    }

    @Override
    protected List<Detected> find(Workspace workspace) {
        Path root = workspace.root();
        List<Detected> out = new ArrayList<>();
        for (Path script : ProjectFiles.find(root, 6, 4000,
                p -> ProjectFiles.hasExtension(p, "py") && ProjectFiles.contains(p, MAIN))) {
            if (out.size() >= MAX_DETECTED) {
                break;
            }
            String relative = ProjectFiles.relative(root, script);
            out.add(new Detected("python " + relative, Map.of("kind", "script", "target", relative)));
        }
        boolean tests = ProjectFiles.any(root, 6, p -> {
            String name = p.getFileName().toString();
            return name.endsWith(".py") && (name.startsWith("test_") || name.endsWith("_test.py"));
        });
        if (tests) {
            out.add(new Detected("pytest", Map.of("kind", "pytest", "target", "")));
        }
        return out;
    }
}
