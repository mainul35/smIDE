package com.smide.plugins.langgraph;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotebookTest {

    /** A graph written across four cells, with prose between, as a notebook actually is. */
    private static final String NOTEBOOK = """
            {
             "cells": [
              {
               "cell_type": "markdown",
               "metadata": {},
               "source": ["# An agent\\n", "\\n", "StateGraph is not mentioned here.\\n"]
              },
              {
               "cell_type": "code",
               "execution_count": 1,
               "metadata": {},
               "outputs": [],
               "source": ["from langgraph.graph import StateGraph, START, END\\n"]
              },
              {
               "cell_type": "code",
               "execution_count": 2,
               "metadata": {},
               "outputs": [],
               "source": [
                "def plan(state):\\n",
                "    return state\\n",
                "\\n",
                "def act(state):\\n",
                "    return state"
               ]
              },
              {
               "cell_type": "code",
               "execution_count": 3,
               "metadata": {},
               "outputs": [],
               "source": "builder = StateGraph(dict)\\nbuilder.add_node(\\"plan\\", plan)\\nbuilder.add_node(\\"act\\", act)\\nbuilder.add_edge(START, \\"plan\\")\\nbuilder.add_edge(\\"plan\\", \\"act\\")\\nbuilder.add_edge(\\"act\\", END)\\ngraph = builder.compile()\\n"
              }
             ],
             "metadata": {"kernelspec": {"name": "python3"}},
             "nbformat": 4,
             "nbformat_minor": 5
            }
            """;

    @Test
    void whatIsANotebook() {
        assertTrue(Notebook.isNotebook(Path.of("work/agent.ipynb")));
        assertFalse(Notebook.isNotebook(Path.of("work/agent.py")));
        assertFalse(Notebook.isNotebook(null));
    }

    @Test
    void theCodeCellsAreJoinedAndTheProseIsLeftOut() {
        String code = Notebook.code(NOTEBOOK).text();
        assertFalse(code.contains("An agent"), code);
        assertTrue(code.startsWith("from langgraph.graph import"), code);
        assertTrue(code.contains("def plan(state):"), code);
        assertTrue(code.endsWith("graph = builder.compile()\n"), code);
    }

    @Test
    void aCellThatDoesNotEndInANewlineDoesNotRunIntoTheNext() {
        // The second code cell ends "return state" with no newline; the next starts "builder =".
        assertTrue(Notebook.code(NOTEBOOK).text().contains("return state\nbuilder = StateGraph(dict)"));
    }

    @Test
    void theGraphIsFoundAcrossCells() {
        GraphChecks.Graph graph = GraphChecks.graphsIn(Notebook.code(NOTEBOOK).text()).get(0);
        GraphLayout.Placed placed = GraphLayout.of(graph);
        assertEquals(List.of("0:0 START", "1:0 plan", "2:0 act", "3:0 END"), placed.labels());
        assertEquals("plan", graph.functions.get("plan"));
    }

    @Test
    void eachCellKnowsWhatItContributed() {
        Notebook.Code code = Notebook.code(NOTEBOOK);
        // Three code cells, of which two hold anything: the import, the functions, the graph.
        assertEquals(3, code.cells().size());
        Notebook.Cell imports = code.cells().get(0);
        Notebook.Cell functions = code.cells().get(1);
        assertEquals(1, imports.index());
        assertEquals(2, functions.index());
        assertEquals(0, imports.from());
        assertEquals(imports.to(), functions.from());
    }

    @Test
    void anOffsetIsTracedBackToItsCell() {
        Notebook.Code code = Notebook.code(NOTEBOOK);
        int plan = code.text().indexOf("def plan");
        int edge = code.text().indexOf("add_edge(START");
        assertEquals(2, code.cellAt(plan).orElseThrow().index());
        assertEquals(3, code.cellAt(edge).orElseThrow().index());
        assertTrue(code.cellAt(code.text().length() + 10).isEmpty());
    }

    @Test
    void aCellIsPointedBackAtWhereItsSourceIsWritten() {
        Notebook.Code code = Notebook.code(NOTEBOOK);
        Notebook.Cell cell = code.cellAt(code.text().indexOf("builder = StateGraph")).orElseThrow();
        assertTrue(cell.at() > 0);
        assertTrue(NOTEBOOK.startsWith("\"source\"", cell.at()),
                NOTEBOOK.substring(cell.at(), cell.at() + 20));
        // It is that cell's source, not another's: what follows it is this cell's code.
        assertTrue(NOTEBOOK.indexOf("builder = StateGraph", cell.at()) - cell.at() < 40);
    }

    @Test
    void aNotebookWithNoPythonInItGivesNothing() {
        String prose = """
                {"cells": [{"cell_type": "markdown", "source": ["# Notes\\n"]}], "nbformat": 4}
                """;
        assertEquals("", Notebook.code(prose).text());
        assertEquals(List.of(), Notebook.code(prose).cells());
    }

    @Test
    void halfSavedJsonGivesNothingRatherThanSomethingWrong() {
        assertEquals("", Notebook.code("{\"cells\": [").text());
        assertEquals("", Notebook.code("").text());
        assertEquals("", Notebook.code("[]").text());
    }

    @Test
    void theOlderFormatIsReadToo() {
        String old = """
                {"worksheets": [{"cells": [
                  {"cell_type": "code", "input": [], "source": ["builder = StateGraph(dict)\\n"]}
                ]}], "nbformat": 3}
                """;
        assertEquals("builder = StateGraph(dict)\n", Notebook.code(old).text());
    }

    @Test
    void magicsAndShellLinesAreLeftAloneRatherThanBreakingTheRead() {
        String withMagics = """
                {"cells": [
                 {"cell_type": "code", "source": ["%%capture\\n", "!pip install langgraph\\n"]},
                 {"cell_type": "code", "source": ["builder = StateGraph(dict)\\n",
                   "builder.add_node(\\"plan\\", plan)\\n", "builder.add_edge(START, \\"plan\\")\\n",
                   "builder.add_edge(\\"plan\\", END)\\n"]}
                ], "nbformat": 4}
                """;
        String code = Notebook.code(withMagics).text();
        assertTrue(code.contains("%%capture"));
        // The magics are not Python, and do not stop the graph being read.
        assertEquals(List.of("0:0 START", "1:0 plan", "2:0 END"),
                GraphLayout.of(GraphChecks.graphsIn(code).get(0)).labels());
    }
}
