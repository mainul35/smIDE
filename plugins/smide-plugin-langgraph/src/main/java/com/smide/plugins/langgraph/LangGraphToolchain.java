package com.smide.plugins.langgraph;

import com.smide.api.Ide;
import com.smide.api.lang.Toolchain;
import com.smide.api.util.Executables;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The {@code langgraph} CLI: the project's own virtual environment first, then the machine.
 *
 * <p>It is what serves a graph locally, builds its image and starts it, and it is installed per
 * project far more often than globally - so the environment beside the project is looked in
 * before anything else, the way the Python plugin uses a project's own interpreter.
 */
public final class LangGraphToolchain implements Toolchain {

    public static final String HOME_SETTING = "langgraph.home";
    public static final String DOCS = "https://langchain-ai.github.io/langgraph/cloud/reference/cli/";
    /** What to run to get it, said wherever it is missing. */
    public static final String INSTALL = "pip install \"langgraph-cli[inmem]\"";

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
        return Files.isRegularFile(root.resolve(LangGraph.CONFIG)) && inProject(root).isEmpty();
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

    /** The CLI to use for a project: its own, else the machine's. */
    public Optional<Path> forProject(Ide ide, Path root) {
        return inProject(root).or(() -> locate(ide));
    }
}
