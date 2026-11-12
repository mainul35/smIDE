package com.smide.plugins.langgraph;

import com.smide.api.Ide;
import com.smide.api.lang.Toolchain;
import com.smide.api.util.Executables;
import com.smide.plugins.python.PythonToolchain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The {@code langgraph} CLI: the project's own virtual environment first, then the machine,
 * and failing both, the interpreter running the CLI as a module.
 *
 * <p>It is what serves a graph locally, builds its image and starts it, and it is installed per
 * project far more often than globally - so the environment beside the project is looked in
 * before anything else, the way the Python plugin uses a project's own interpreter. Where the
 * launcher is there but cannot be started - Smart App Control refusing an unsigned executable,
 * which is a thing that happens to every pip launcher on a machine that has it turned on - the
 * module is used instead, and nothing needs installing for that. {@link CliLaunch} chooses.
 */
public final class LangGraphToolchain implements Toolchain {

    public static final String HOME_SETTING = "langgraph.home";
    public static final String DOCS = "https://langchain-ai.github.io/langgraph/cloud/reference/cli/";
    /**
     * What to run to get it, said wherever it is missing.
     *
     * <p>Through the interpreter rather than as {@code pip}: on Windows the pip launcher is an
     * unsigned executable like any other and is blocked by the same policy, while
     * {@code python -m pip} is the signed interpreter doing the same work.
     */
    public static final String INSTALL = "python -m pip install \"langgraph-cli[inmem]\"";

    @Override
    public String id() {
        return "langgraph";
    }

    @Override
    public String displayName() {
        return "LangGraph CLI";
    }

    @Override
    public String purpose() {
        return "The dev server, building a deployment image and starting it need the langgraph CLI."
                + " A graph can be invoked without it. Install it with: " + INSTALL;
    }

    @Override
    public String downloadUrl() {
        return DOCS;
    }

    @Override
    public String homeSetting() {
        return HOME_SETTING;
    }

    @Override
    public boolean isNeededBy(Path root) {
        // Only where a deployment is actually described: a graph on its own needs no CLI.
        if (!Files.isRegularFile(root.resolve(LangGraph.CONFIG))) {
            return false;
        }
        // Nothing is missing if there is a way to run it, launcher or module.
        return CliLaunch.prefer(inProject(root), PythonToolchain.venvInterpreter(root)).isEmpty();
    }

    @Override
    public Optional<Path> locate(Ide ide) {
        return Executables.find(ide, HOME_SETTING, "langgraph", List.of(), p -> true);
    }

    @Override
    public boolean accepts(Path home) {
        return Executables.in(home, "langgraph").isPresent();
    }

    /** The CLI installed in the project's own virtual environment, which is where it usually is. */
    public static Optional<Path> inProject(Path root) {
        for (String name : List.of(".venv", "venv", "env")) {
            Path scripts = Executables.WINDOWS
                    ? root.resolve(name).resolve("Scripts")
                    : root.resolve(name).resolve("bin");
            Optional<Path> cli = Executables.in(scripts, "langgraph");
            if (cli.isPresent()) {
                return cli;
            }
        }
        return Optional.empty();
    }

    /** The CLI launcher to use for a project: its own, else the machine's. */
    public Optional<Path> forProject(Ide ide, Path root) {
        return inProject(root).or(() -> locate(ide));
    }

    /**
     * How to start the CLI for this project, whatever will run: the launcher, or the interpreter
     * and the module. Starts processes to find out, so not on the thread that draws the window.
     *
     * @param interpreter the Python to fall back to, which is the one a graph would be invoked with
     */
    public Optional<List<String>> commandFor(Ide ide, Path root, Optional<Path> interpreter) {
        return CliLaunch.prefer(forProject(ide, root), interpreter);
    }
}
