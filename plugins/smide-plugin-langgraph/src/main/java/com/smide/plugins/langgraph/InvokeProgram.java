package com.smide.plugins.langgraph;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs one graph, a superstep at a time, and prints what each node wrote.
 *
 * <p>A graph is normally reached through the dev server, which is a web server, a browser and a
 * deployment configuration between the reader and a question as small as "what does this graph do
 * with this input". This runs it in the project's own interpreter instead - so the Run window
 * shows the nodes in the order they ran and what each one changed, and a breakpoint in a node is
 * an ordinary Python breakpoint.
 *
 * <p>The program is passed to the interpreter with {@code -c} rather than written to a file:
 * nothing is left in the project, and nothing has to be cleaned up after a run.
 */
final class InvokeProgram {

    /**
     * Imports the graph named by langgraph.json, compiles it if it is a builder, calls it if it
     * is a factory, and streams it. Arguments: the file or module, the attribute, the input JSON,
     * and the thread id.
     */
    static final String SOURCE = """
            import importlib, importlib.util, json, sys

            path, attribute, payload, thread = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
            if path.endswith(".py"):
                spec = importlib.util.spec_from_file_location("_smide_graph", path)
                module = importlib.util.module_from_spec(spec)
                sys.modules["_smide_graph"] = module
                spec.loader.exec_module(module)
            else:
                module = importlib.import_module(path)

            graph = getattr(module, attribute)
            if not hasattr(graph, "stream") and callable(graph) and not hasattr(graph, "compile"):
                graph = graph()
            if not hasattr(graph, "stream") and hasattr(graph, "compile"):
                graph = graph.compile()
            if not hasattr(graph, "stream"):
                sys.exit(attribute + " in " + path + " is not a graph: it has no stream().")

            state = json.loads(payload) if payload.strip() else {}
            config = {"configurable": {"thread_id": thread}} if thread.strip() else None
            steps = 0
            for chunk in graph.stream(state, config=config, stream_mode="updates"):
                for node, update in (chunk or {}).items():
                    steps += 1
                    print("-- " + str(node))
                    print(json.dumps(update, default=str, indent=2, ensure_ascii=False))
                    sys.stdout.flush()
            print("-- done: " + str(steps) + " node update(s)")
            """;

    private InvokeProgram() {
    }

    /**
     * The command that invokes one graph.
     *
     * @param configDir the folder langgraph.json is in, which a file spec is relative to
     */
    static List<String> command(Path interpreter, LangGraph.GraphSpec spec, Path configDir,
                                String input, String thread) {
        List<String> command = new ArrayList<>(List.of(interpreter.toString(), "-u", "-c", SOURCE));
        command.add(spec.isFile() ? spec.file(configDir).toString() : spec.module());
        command.add(spec.attribute());
        command.add(input == null ? "" : input);
        command.add(thread == null ? "" : thread);
        return command;
    }
}
