package com.smide.plugins.langgraph;

import com.smide.api.Ide;
import com.smide.api.execution.CommandRunType;
import com.smide.api.execution.ExecutionMode;
import com.smide.api.execution.Forms;
import com.smide.api.execution.RunMarker;
import com.smide.api.util.ProjectFiles;
import com.smide.api.workspace.Workspace;
import com.smide.plugins.python.PythonDebugger;
import com.smide.plugins.python.PythonToolchain;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The four things done with a LangGraph project: serve it, invoke one graph, build its image,
 * start that image.
 *
 * <p>Invoking is the one that is not the CLI's: it runs the graph in the project's own
 * interpreter and prints each node's update as it happens, so a graph can be tried without a
 * server, a browser or a deployment, and stopped at a breakpoint inside a node like any other
 * Python.
 */
public final class LangGraphRunType extends CommandRunType {

    static final String ID = "langgraph.run";

    /** Where a graph is assigned, for the run icon beside it: {@code graph = builder.compile()}. */
    private static final Pattern COMPILED =
            Pattern.compile("(?m)^(\\w+)\\s*(?::[^=\\r\\n]+)?=\\s*\\w+\\s*\\.\\s*compile\\s*\\(");

    private final LangGraphToolchain cli;

    public LangGraphRunType(Ide ide, LangGraphToolchain cli) {
        super(ide, ID, "LangGraph", "mdi2g-graph-outline");
        this.cli = cli;
    }

    /** Before the plain Python kind, so a graph is offered as a graph rather than as a script. */
    @Override
    public int order() {
        return 80;
    }

    @Override
    protected List<Field> fields() {
        return List.of(
                Field.choice("kind", "Kind", List.of("dev", "invoke", "build", "up")),
                Field.text("graph", "Graph", "the id in langgraph.json, or ./graph.py:graph"),
                Field.text("input", "Input", "{\"messages\": [\"hello\"]}"),
                Field.text("thread", "Thread id", "empty: no checkpointing of this run"),
                Field.text("port", "Port", "2024 (dev, up)"),
                Field.text("tag", "Image tag", "langgraph-app (build)"),
                Field.text("args", "Arguments", ""),
                Field.text("interpreter", "Interpreter", "empty: the project's .venv (invoke)"));
    }

    @Override
    protected Map<String, String> defaults() {
        return Map.of("kind", "dev");
    }

    @Override
    public boolean supportsDebug() {
        return true;
    }

    @Override
    protected String note() {
        return "dev, build and up are the langgraph CLI. invoke runs the graph in the project's own"
                + " interpreter and prints what each node writes - that is the one that can be debugged,"
                + " with breakpoints in the nodes.";
    }

    @Override
    protected Command command(Config c, ExecutionMode mode) throws Exception {
        Path root = c.moduleDir();
        Path configDir = configDir(root);
        String kind = c.get("kind", "dev");
        if (kind.equals("invoke")) {
            return invoke(c, root, configDir, mode);
        }
        if (mode == ExecutionMode.DEBUG) {
            throw new IllegalStateException("Only an invoke configuration can be debugged: dev, build and up"
                    + " are the CLI's own processes. Debug the graph itself with an invoke configuration.");
        }
        Path command = cli.forProject(ide, root).orElseThrow(() -> new IllegalStateException(
                "The langgraph CLI was not found. Install it with: " + LangGraphToolchain.INSTALL
                        + ", or set its folder in Settings > Languages > LangGraph."));
        List<String> argv = new ArrayList<>(List.of(command.toString(), kind));
        String port = c.get("port", "");
        String tag = c.get("tag", "");
        switch (kind) {
            case "build" -> {
                if (tag.isBlank()) {
                    throw new IllegalStateException("Building an image needs a tag: set one, such as langgraph-app.");
                }
                argv.addAll(List.of("-t", tag));
            }
            case "up" -> {
                if (!port.isBlank()) {
                    argv.addAll(List.of("--port", port));
                }
            }
            default -> {
                if (!port.isBlank()) {
                    argv.addAll(List.of("--port", port));
                }
            }
        }
        argv.addAll(Forms.splitArgs(c.get("args", "")));
        return new Command(argv, configDir, Map.of("PYTHONUNBUFFERED", "1", "PYTHONIOENCODING", "utf-8"));
    }

    private Command invoke(Config c, Path root, Path configDir, ExecutionMode mode) throws Exception {
        LangGraph.GraphSpec spec = spec(c, configDir);
        Path interpreter = interpreter(c, root);
        List<String> argv = InvokeProgram.command(interpreter, spec, configDir,
                c.get("input", ""), c.get("thread", ""));
        Map<String, String> env = new LinkedHashMap<>(Map.of(
                "PYTHONUNBUFFERED", "1", "PYTHONIOENCODING", "utf-8"));
        // A graph imports the rest of its own project, which is not on the path of a bare interpreter.
        env.put("PYTHONPATH", pythonPath(configDir));
        if (mode == ExecutionMode.DEBUG) {
            return PythonDebugger.command(ide, c, interpreter, argv, configDir, env);
        }
        return new Command(argv, configDir, env);
    }

    /** The graph to invoke: the one named, the only one there is, or a spec written out in full. */
    private LangGraph.GraphSpec spec(Config c, Path configDir) {
        String written = c.get("graph", "").strip();
        if (written.contains(":")) {
            return LangGraph.parseSpec(written.substring(written.lastIndexOf(':') + 1), written)
                    .orElseThrow(() -> new IllegalStateException(
                            "A graph is written as a file and what is in it: ./src/agent/graph.py:graph."));
        }
        Map<String, LangGraph.GraphSpec> graphs = LangGraphJson.graphsOf(configDir);
        if (graphs.isEmpty()) {
            throw new IllegalStateException("No graph to invoke: name one as ./src/agent/graph.py:graph,"
                    + " or add a langgraph.json that names the project's graphs.");
        }
        if (written.isBlank()) {
            if (graphs.size() == 1) {
                return graphs.values().iterator().next();
            }
            throw new IllegalStateException("This project has several graphs - "
                    + String.join(", ", graphs.keySet()) + " - so the configuration must say which.");
        }
        LangGraph.GraphSpec spec = graphs.get(written);
        if (spec == null) {
            throw new IllegalStateException("langgraph.json names no graph called " + written
                    + ". It names " + String.join(", ", graphs.keySet()) + ".");
        }
        return spec;
    }

    private Path interpreter(Config c, Path root) {
        String chosen = c.get("interpreter", "");
        if (!chosen.isBlank()) {
            return Path.of(chosen);
        }
        return PythonToolchain.venvInterpreter(root)
                .or(() -> new PythonToolchain().locate(ide))
                .orElseThrow(() -> new IllegalStateException("Python was not found. Install it,"
                        + " or set its folder in Settings > Languages > Python."));
    }

    /** The project's own folders, so a graph can import the package it is part of. */
    private static String pythonPath(Path configDir) {
        List<String> parts = new ArrayList<>(List.of(configDir.toString()));
        for (String where : List.of("src", "app")) {
            if (Files.isDirectory(configDir.resolve(where))) {
                parts.add(configDir.resolve(where).toString());
            }
        }
        String existing = System.getenv("PYTHONPATH");
        if (existing != null && !existing.isBlank()) {
            parts.add(existing);
        }
        return String.join(File.pathSeparator, parts);
    }

    /** Where langgraph.json is: the root, or the one folder below it that holds one. */
    static Path configDir(Path root) {
        if (Files.isRegularFile(root.resolve(LangGraph.CONFIG))) {
            return root;
        }
        for (Path found : ProjectFiles.find(root, 3, 2000,
                p -> p.getFileName() != null && p.getFileName().toString().equals(LangGraph.CONFIG))) {
            Path parent = found.getParent();
            if (parent != null) {
                return parent;
            }
        }
        return root;
    }

    @Override
    protected List<Detected> find(Workspace workspace) {
        Path root = workspace.root();
        if (!LangGraph.isProject(root)) {
            return List.of();
        }
        Path configDir = configDir(root);
        List<Detected> out = new ArrayList<>();
        if (Files.isRegularFile(configDir.resolve(LangGraph.CONFIG))) {
            out.add(new Detected("langgraph dev", Map.of("kind", "dev")));
        }
        for (String id : LangGraphJson.graphsOf(configDir).keySet()) {
            out.add(new Detected("invoke " + id, Map.of("kind", "invoke", "graph", id)));
        }
        return out;
    }

    /**
     * A run icon beside the graph a deployment serves - the line it is assigned on, in the file
     * langgraph.json points at - so a graph is run from where it is written.
     */
    @Override
    public List<RunMarker> markers(Workspace workspace, Path file, String text) {
        if (!ProjectFiles.hasExtension(file, "py") || !file.startsWith(workspace.root())) {
            return List.of();
        }
        Path configDir = configDir(workspace.root());
        Map<String, LangGraph.GraphSpec> graphs = LangGraphJson.graphsOf(configDir);
        if (graphs.isEmpty()) {
            return List.of();
        }
        Path here = file.toAbsolutePath().normalize();
        List<RunMarker> out = new ArrayList<>();
        Matcher assigned = COMPILED.matcher(text);
        while (assigned.find()) {
            for (LangGraph.GraphSpec spec : graphs.values()) {
                if (spec.isFile() && spec.file(configDir).toAbsolutePath().equals(here)
                        && spec.attribute().equals(assigned.group(1))) {
                    out.add(marker(workspace, RunMarker.lineOf(text, assigned.start()), "invoke " + spec.id(),
                            Map.of("kind", "invoke", "graph", spec.id())));
                }
            }
        }
        return out;
    }
}
