package com.smide.plugins.langgraph;

import com.smide.api.Ide;
import com.smide.api.project.NewProjectTemplate;
import com.smide.api.util.Executables;
import com.smide.plugins.python.PythonToolchain;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * File - New Project - LangGraph: a project that runs the moment it is made.
 *
 * <p>The files alone would not be enough. A LangGraph project is Python, and Python without
 * an environment is a project that cannot run its own example - so this makes the virtual
 * environment and installs what the example imports, which is the step everybody has to do
 * by hand and nobody enjoys. It then opens a notebook with a working graph in it, because a
 * graph you can run and change a cell at a time teaches more in a minute than a README does
 * in ten.
 *
 * <p>Everything the environment needs is installed through the interpreter, never through a
 * {@code Scripts\pip.exe} launcher: pip's launchers are unsigned, and on Windows with Smart
 * App Control they do not start at all. {@link CliLaunch} deals with the same problem for
 * the CLI, and the reasoning is written out there.
 *
 * <p>Nothing here is fatal. A machine with no Python still gets the whole project, and is
 * told in plain words what to run to finish it - a half-made project the user can complete
 * is better than an error where a project should have been.
 */
public final class LangGraphTemplate implements NewProjectTemplate {

    /** What goes in the venv: the library, the CLI with its in-process server, and a kernel. */
    private static final java.util.List<String> PACKAGES =
            java.util.List.of("langgraph", "langgraph-cli[inmem]", "ipykernel");

    static final String NOTEBOOK = "hello_graph.ipynb";

    @Override
    public String id() {
        return "langgraph.project";
    }

    @Override
    public String displayName() {
        return "LangGraph";
    }

    @Override
    public String description() {
        return "An agent graph that already runs: langgraph.json, a graph module, and a"
                + " notebook to try it in. Makes a .venv and installs LangGraph into it.";
    }

    @Override
    public String iconLiteral() {
        return "mdi2g-graph-outline";
    }

    @Override
    public Node form(Map<String, String> values) {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        CheckBox venv = new CheckBox("Create .venv and install LangGraph");
        venv.setSelected(true);
        values.put("venv", "true");
        venv.selectedProperty().addListener((o, was, now) -> values.put("venv", String.valueOf(now)));
        Label note = new Label("""
                Installing fetches langgraph, the CLI and ipykernel from PyPI, which takes a
                minute and needs the network. Without it the project is still complete; the
                README says what to run.""");
        note.setWrapText(true);
        note.getStyleClass().add("muted");
        grid.add(new VBox(6, venv, note), 0, 0);
        return grid;
    }

    @Override
    public Optional<Path> openAfterCreating(Path location) {
        return Optional.of(location.resolve(NOTEBOOK));
    }

    @Override
    public void generate(Ide ide, Path location, String name, Map<String, String> values) throws Exception {
        write(location, name);
        if (!Boolean.parseBoolean(values.getOrDefault("venv", "true"))) {
            return;
        }
        Optional<Path> python = basePython(ide);
        if (python.isEmpty()) {
            ide.notifications().error("No Python found",
                    "The project is ready, but there is no interpreter to build a .venv with."
                            + " Install Python, then run: python -m venv .venv");
            return;
        }
        makeEnvironment(ide, location, python.get());
    }

    // ------------------------------------------------------------------ files

    private void write(Path location, String name) throws java.io.IOException {
        Files.createDirectories(location);
        Files.writeString(location.resolve("langgraph.json"), Scaffold.config());
        Files.writeString(location.resolve("agent.py"), Scaffold.agent());
        Files.writeString(location.resolve(NOTEBOOK), Scaffold.notebook(name));
        Files.writeString(location.resolve("pyproject.toml"), Scaffold.pyproject(name));
        Files.writeString(location.resolve(".env"), Scaffold.env());
        Files.writeString(location.resolve(".gitignore"), Scaffold.gitignore());
        Files.writeString(location.resolve("README.md"), Scaffold.readme(name));
    }

    // ------------------------------------------------------------ environment

    /** The interpreter to build the venv with: whatever the Python toolchain would use. */
    private Optional<Path> basePython(Ide ide) {
        return ide.toolchains().all().stream()
                .filter(t -> "python".equals(t.id()))
                .findFirst()
                .flatMap(t -> t.locate(ide));
    }

    private void makeEnvironment(Ide ide, Path location, Path python) {
        if (!run(ide, location, "Creating .venv", python.toString(), "-m", "venv", ".venv")) {
            ide.notifications().error("Could not create .venv",
                    "The project is ready. To finish it: python -m venv .venv");
            return;
        }
        Optional<Path> venv = PythonToolchain.venvInterpreter(location);
        if (venv.isEmpty()) {
            ide.notifications().error("The .venv has no interpreter in it",
                    "The project is ready, but something went wrong making the environment.");
            return;
        }
        String[] install = new String[PACKAGES.size() + 4];
        install[0] = venv.get().toString();
        install[1] = "-m";
        install[2] = "pip";
        install[3] = "install";
        for (int i = 0; i < PACKAGES.size(); i++) {
            install[i + 4] = PACKAGES.get(i);
        }
        if (!run(ide, location, "Installing LangGraph", install)) {
            ide.notifications().error("Could not install LangGraph",
                    "The project and its .venv are ready. To finish it, from the project:\n"
                            + ".venv" + java.io.File.separator
                            + (Executables.WINDOWS ? "Scripts" : "bin")
                            + java.io.File.separator + "python -m pip install "
                            + String.join(" ", PACKAGES));
            return;
        }
        checkItRuns(ide, location, venv.get());
    }

    /**
     * Imports the library once, because installing it is not the same as being allowed to run it.
     *
     * <p>LangGraph brings compiled extensions with it, and on Windows with Smart App Control a
     * binary nobody has seen before is refused until its reputation is established - which
     * happens on Microsoft's schedule, not ours, and takes somewhere between minutes and
     * longer. A project made in that window installs perfectly and then fails on the first
     * cell with {@code DLL load failed while importing ormsgpack}, which tells the user
     * nothing about what is happening or that waiting fixes it.
     *
     * <p>Measured on a machine with it enforcing: a venv built minutes earlier was still
     * refused on the fifth consecutive attempt, while one built ninety minutes earlier
     * imported perfectly. There is nothing to do about it but say so.
     */
    private void checkItRuns(Ide ide, Path location, Path venv) {
        Outcome tried = ask(location, venv.toString(), "-c", "import langgraph.graph");
        if (tried.ok()) {
            ide.notifications().info("LangGraph project ready",
                    "A .venv with " + String.join(", ", PACKAGES) + ". Open " + NOTEBOOK
                            + " and run a cell.");
            return;
        }
        if (notClearedYet(tried.said())) {
            ide.notifications().warn("Windows has not cleared LangGraph yet",
                    "The project and its .venv are complete, but Smart App Control is still"
                            + " checking a newly downloaded part of LangGraph and will not let it"
                            + " load. Nothing is wrong with the project and there is nothing to"
                            + " reinstall - it starts working by itself, usually within the hour.");
            return;
        }
        ide.notifications().warn("LangGraph is installed but would not import",
                "The project is ready. Running a cell will show what is wrong.");
    }

    /**
     * Whether a failed import was Windows refusing a binary rather than anything being wrong.
     *
     * <p>This refusal reaches Python as an {@link ImportError} about a DLL, so the only thing
     * that distinguishes it from a broken install is the sentence Windows put in it. Both the
     * words and the code are matched, because the words are what a user sees and the code is
     * what survives a machine running in another language.
     */
    static boolean notClearedYet(String said) {
        return said != null
                && (said.contains("Application Control") || said.contains("4551"));
    }

    /**
     * Runs one step of the setup, waiting for it.
     *
     * <p>Already on a background thread - {@code generate} is called off the UI thread - so
     * waiting here is what keeps the steps in order, which they must be: there is no
     * installing into an environment that is still being made.
     */
    private boolean run(Ide ide, Path at, String what, String... command) {
        Outcome outcome = ask(at, command);
        if (!outcome.ok()) {
            System.err.println("smIDE langgraph: " + what + " failed:\n" + outcome.said());
        }
        return outcome.ok();
    }

    /** Whether a step worked, and what it printed - the second part is how a refusal is told. */
    record Outcome(boolean ok, String said) {
    }

    private Outcome ask(Path at, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(at.toFile())
                    .redirectErrorStream(true)
                    .start();
            String said = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return new Outcome(false, said + "\n(it did not finish)");
            }
            return new Outcome(process.exitValue() == 0, said);
        } catch (java.io.IOException e) {
            // A refusal arrives here, and its message is the only thing that names it.
            return new Outcome(false, String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(false, "interrupted");
        }
    }
}
