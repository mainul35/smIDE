package com.smide.plugins.langgraph;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.GsonBuilder;

/**
 * What a new LangGraph project is made of.
 *
 * <p>Apart from {@link LangGraphTemplate}, which has a dialog attached to it, so that what
 * goes in the files can be read, tested and corrected without opening a window. The notebook
 * in particular is JSON holding Python holding quotation marks, which is exactly the sort of
 * thing that is wrong in a way nobody notices until a user opens it - so it is built as a
 * JSON document rather than written out as text, and a test reads it back.
 *
 * <p>The example graph calls no model. It plans, acts and decides whether to go round again,
 * all with ordinary functions, so it runs on a fresh machine with no key, no account and no
 * network, and it still shows the two things that make LangGraph worth using: a branch and a
 * loop. Replace the bodies and the shape stays.
 */
final class Scaffold {

    private Scaffold() {
    }

    static String config() {
        return """
                {
                  "dependencies": ["."],
                  "graphs": {
                    "agent": "./agent.py:graph"
                  },
                  "env": ".env"
                }
                """;
    }

    static String agent() {
        return """
                \"""The graph this project serves.

                `langgraph.json` points here, because the CLI imports a module and a notebook is
                not importable. Work things out in the notebook, then bring them here.
                \"""

                from typing import Annotated, Sequence, TypedDict

                from langgraph.graph import END, START, StateGraph


                def add(existing: Sequence[str], incoming: Sequence[str]) -> list[str]:
                    \"""How two updates to `notes` are combined.\"""
                    return [*existing, *incoming]


                class State(TypedDict):
                    \"""What flows between the nodes. `notes` accumulates; the rest is last-write-wins.\"""

                    question: str
                    notes: Annotated[Sequence[str], add]
                    answer: str
                    tries: int


                def plan(state: State) -> dict:
                    \"""Decide what to do. A real one would ask a model.\"""
                    return {"notes": [f"planning an answer to {state['question']!r}"]}


                def act(state: State) -> dict:
                    \"""Do it, and count the attempt.\"""
                    tries = state.get("tries", 0) + 1
                    return {
                        "answer": f"Answer to {state['question']!r}, attempt {tries}",
                        "tries": tries,
                        "notes": [f"wrote attempt {tries}"],
                    }


                def good_enough(state: State) -> str:
                    \"""Round again, or out. A loop with no bound is how an afternoon disappears.\"""
                    return END if state.get("tries", 0) >= 2 else "plan"


                builder = StateGraph(State)
                builder.add_node("plan", plan)
                builder.add_node("act", act)

                builder.add_edge(START, "plan")
                builder.add_edge("plan", "act")
                builder.add_conditional_edges("act", good_enough, {"plan": "plan", END: END})

                graph = builder.compile()


                if __name__ == "__main__":
                    final = graph.invoke({"question": "what is a state graph?", "notes": [], "tries": 0})
                    for note in final["notes"]:
                        print("-", note)
                    print(final["answer"])
                """;
    }

    static String pyproject(String name) {
        return """
                [project]
                name = "%s"
                version = "0.1.0"
                description = "A LangGraph agent."
                requires-python = ">=3.11"
                dependencies = ["langgraph>=1.0"]

                [build-system]
                requires = ["setuptools>=68"]
                build-backend = "setuptools.build_meta"

                [tool.setuptools]
                py-modules = ["agent"]
                """.formatted(packageName(name));
    }

    static String env() {
        return """
                # Variables the deployment starts with. The example graph needs none of them -
                # its nodes are plain functions - but a real agent wants its model key, and
                # langgraph.json points here so that key never has to live in the code.
                # OPENAI_API_KEY=
                # ANTHROPIC_API_KEY=
                """;
    }

    static String gitignore() {
        return """
                .venv/
                __pycache__/
                *.pyc
                .smide/
                .langgraph_api/
                .env
                """;
    }

    static String readme(String name) {
        return """
                # %s

                A LangGraph agent. `agent.py` builds the graph; `%s` is the same graph to try a
                cell at a time.

                ## Running it

                The Run dropdown offers **langgraph dev** and **invoke agent** as soon as the
                project is open - smIDE reads `langgraph.json` and finds them.

                From a terminal, through the project's own interpreter:

                ```
                .venv/Scripts/python -m langgraph_cli dev      # Windows
                .venv/bin/python -m langgraph_cli dev          # elsewhere
                ```

                Through the interpreter rather than `.venv/Scripts/langgraph`, because pip's
                launchers are unsigned and Windows Smart App Control refuses to start them.
                smIDE tries the launcher once, notices, and uses this form by itself.

                ## If the environment was not made

                ```
                python -m venv .venv
                .venv/Scripts/python -m pip install langgraph "langgraph-cli[inmem]" ipykernel
                ```
                """.formatted(name, LangGraphTemplate.NOTEBOOK);
    }

    /** A project name as a Python distribution name: letters, digits and dashes. */
    static String packageName(String name) {
        String cleaned = name.strip().toLowerCase().replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return cleaned.isEmpty() ? "langgraph-agent" : cleaned;
    }

    // -------------------------------------------------------------- notebook

    static String notebook(String name) {
        JsonArray cells = new JsonArray();
        cells.add(markdown("""
                # %s

                A graph that runs with no API key: every node is an ordinary function. Run the
                cells in order, then change one and run it again.

                smIDE draws this graph in the **Graph** window on the right as you type - it
                reads the code cells in the order they run and leaves the prose out.
                """.formatted(name)));
        cells.add(code("""
                from typing import TypedDict, Annotated, Sequence

                from langgraph.graph import StateGraph, START, END


                def add(existing: Sequence[str], incoming: Sequence[str]) -> list[str]:
                    return [*existing, *incoming]


                class State(TypedDict):
                    question: str
                    notes: Annotated[Sequence[str], add]
                    answer: str
                    tries: int
                """));
        cells.add(markdown("""
                ## The nodes

                Nothing here calls a model. Swap the bodies when you want it to do something
                real; the shape of the graph does not change.
                """));
        cells.add(code("""
                def plan(state: State) -> dict:
                    return {"notes": [f"planning an answer to {state['question']!r}"]}


                def act(state: State) -> dict:
                    tries = state.get("tries", 0) + 1
                    return {
                        "answer": f"Answer to {state['question']!r}, attempt {tries}",
                        "tries": tries,
                        "notes": [f"wrote attempt {tries}"],
                    }


                def good_enough(state: State) -> str:
                    return END if state.get("tries", 0) >= 2 else "plan"
                """));
        cells.add(markdown("""
                ## Wiring it up

                This is the cell the Graph window is really reading: the nodes and the edges.
                Watch the picture change as you add one.
                """));
        cells.add(code("""
                builder = StateGraph(State)
                builder.add_node("plan", plan)
                builder.add_node("act", act)

                builder.add_edge(START, "plan")
                builder.add_edge("plan", "act")
                builder.add_conditional_edges("act", good_enough, {"plan": "plan", END: END})

                graph = builder.compile()
                """));
        cells.add(markdown("""
                ## Running it

                `stream_mode="updates"` gives one update per node as it finishes, which is the
                view you want while you are still working out what the graph does.
                """));
        cells.add(code("""
                for update in graph.stream(
                    {"question": "what is a state graph?", "notes": [], "tries": 0},
                    stream_mode="updates",
                ):
                    for node, change in update.items():
                        print(f"-- {node}")
                        print(f"   {change}")
                """));
        cells.add(markdown("""
                ## What LangGraph itself thinks the graph looks like

                Worth comparing with the Graph window. This one is built by compiling, so it
                knows about edges decided at runtime; the window is live as you type and needs
                no interpreter. When they disagree, this one is right.
                """));
        cells.add(code("print(graph.get_graph().draw_mermaid())\n"));

        JsonObject kernel = new JsonObject();
        kernel.addProperty("display_name", "Python 3");
        kernel.addProperty("language", "python");
        kernel.addProperty("name", "python3");
        JsonObject language = new JsonObject();
        language.addProperty("name", "python");
        JsonObject metadata = new JsonObject();
        metadata.add("kernelspec", kernel);
        metadata.add("language_info", language);

        JsonObject notebook = new JsonObject();
        notebook.add("cells", cells);
        notebook.add("metadata", metadata);
        notebook.addProperty("nbformat", 4);
        notebook.addProperty("nbformat_minor", 5);
        /* serializeNulls, or Gson drops the execution_count of every code cell on the way
           out - it is JSON null, and null is what Gson leaves out unless told otherwise.
           nbformat requires the key to be present on a code cell, so a notebook without it
           is one that strict readers reject. */
        return new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(notebook) + "\n";
    }

    private static JsonObject markdown(String source) {
        JsonObject cell = cell("markdown", source);
        return cell;
    }

    private static JsonObject code(String source) {
        JsonObject cell = cell("code", source);
        cell.add("execution_count", com.google.gson.JsonNull.INSTANCE);
        cell.add("outputs", new JsonArray());
        return cell;
    }

    /** One cell, with its source split the way nbformat keeps it: a line per entry. */
    private static JsonObject cell(String kind, String source) {
        JsonObject cell = new JsonObject();
        cell.addProperty("cell_type", kind);
        cell.add("metadata", new JsonObject());
        JsonArray lines = new JsonArray();
        String[] split = source.split("\n", -1);
        for (int i = 0; i < split.length; i++) {
            boolean last = i == split.length - 1;
            if (last && split[i].isEmpty()) {
                // A trailing newline ends the previous line; it is not a line of its own.
                break;
            }
            lines.add(last ? split[i] : split[i] + "\n");
        }
        cell.add("source", lines);
        return cell;
    }
}
