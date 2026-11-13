package com.smide.plugins.langgraph;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScaffoldTest {

    private static String code(String notebookJson) {
        return Notebook.code(notebookJson).text();
    }

    @Test
    void theNotebookIsANotebook() {
        JsonObject nb = JsonParser.parseString(Scaffold.notebook("My Agent")).getAsJsonObject();
        assertEquals(4, nb.get("nbformat").getAsInt());
        JsonArray cells = nb.getAsJsonArray("cells");
        assertTrue(cells.size() >= 8, "cells: " + cells.size());
        assertTrue(nb.getAsJsonObject("metadata").has("kernelspec"));
    }

    @Test
    void everyCellHasWhatNbformatRequires() {
        JsonArray cells = JsonParser.parseString(Scaffold.notebook("x"))
                .getAsJsonObject().getAsJsonArray("cells");
        for (int i = 0; i < cells.size(); i++) {
            JsonObject cell = cells.get(i).getAsJsonObject();
            String kind = cell.get("cell_type").getAsString();
            assertTrue(List.of("code", "markdown").contains(kind), kind);
            assertTrue(cell.get("source").isJsonArray(), "cell " + i + " source");
            assertTrue(cell.has("metadata"), "cell " + i + " metadata");
            if (kind.equals("code")) {
                // A code cell without these is rejected by nbformat's own validator.
                assertTrue(cell.has("outputs"), "cell " + i + " outputs");
                assertTrue(cell.has("execution_count"), "cell " + i + " execution_count");
            }
        }
    }

    @Test
    void theProjectNameReachesTheNotebook() {
        assertTrue(Scaffold.notebook("Trip Planner").contains("Trip Planner"));
    }

    @Test
    void aCellsSourceIsOneLinePerEntryAndJoinsBackUp() {
        JsonArray cells = JsonParser.parseString(Scaffold.notebook("x"))
                .getAsJsonObject().getAsJsonArray("cells");
        for (int i = 0; i < cells.size(); i++) {
            JsonArray source = cells.get(i).getAsJsonObject().getAsJsonArray("source");
            StringBuilder joined = new StringBuilder();
            for (int line = 0; line < source.size(); line++) {
                String text = source.get(line).getAsString();
                joined.append(text);
                // A newline only ever ends an entry; two lines in one entry is the bug here.
                assertEquals(text.endsWith("\n") ? 1 : 0, text.chars().filter(c -> c == '\n').count(),
                        "cell " + i + " line " + line + ": " + text);
                assertFalse(text.isEmpty() && line == source.size() - 1,
                        "cell " + i + " ends with an empty entry");
            }
            assertFalse(joined.toString().isBlank(), "cell " + i + " is empty");
        }
    }

    @Test
    void theNotebooksGraphIsTheOneTheWindowWillDraw() {
        String python = code(Scaffold.notebook("x"));
        GraphChecks.Graph graph = GraphChecks.graphsIn(python).get(0);
        GraphLayout.Placed placed = GraphLayout.of(graph);
        assertEquals(List.of("0:0 START", "1:0 plan", "2:0 act", "3:0 END"), placed.labels());
        assertEquals(2, placed.links().stream().filter(GraphLayout.Link::conditional).count());
        assertEquals(1, placed.links().stream().filter(GraphLayout.Link::back).count());
        assertTrue(placed.boxes().stream().allMatch(GraphLayout.Box::reachable));
    }

    @Test
    void theNotebookHasNothingToComplainAbout() {
        assertEquals(List.of(),
                GraphChecks.problemsIn(Path.of("hello_graph.ipynb"), code(Scaffold.notebook("x"))));
    }

    @Test
    void theConfigPointsAtTheModuleAndSaysSoProperly() {
        assertTrue(LangGraphJson.graphs(Scaffold.config()).containsKey("agent"));
        LangGraph.GraphSpec spec = LangGraphJson.graphs(Scaffold.config()).get("agent");
        assertEquals("./agent.py", spec.module());
        assertEquals("graph", spec.attribute());
        assertTrue(spec.isFile());
    }

    @Test
    void theModuleDefinesWhatTheConfigNames() {
        assertTrue(LangGraphJson.defines(Scaffold.agent(), "graph"),
                "agent.py must define the name langgraph.json points at");
    }

    @Test
    void theModulesGraphMatchesTheNotebooksGraph() {
        // The two are kept in step by hand, so a test is the only thing that notices.
        assertEquals(GraphLayout.of(GraphChecks.graphsIn(Scaffold.agent()).get(0)).labels(),
                GraphLayout.of(GraphChecks.graphsIn(code(Scaffold.notebook("x"))).get(0)).labels());
    }

    @Test
    void theModuleHasNothingToComplainAboutEither() {
        assertEquals(List.of(), GraphChecks.problemsIn(Path.of("agent.py"), Scaffold.agent()));
    }

    @Test
    void aProjectNameBecomesSomethingPythonWillAccept() {
        assertEquals("my-agent", Scaffold.packageName("My Agent"));
        assertEquals("trip-planner", Scaffold.packageName("  Trip   Planner  "));
        assertEquals("agent2", Scaffold.packageName("agent2"));
        assertEquals("a-b", Scaffold.packageName("a...b"));
        // Nothing usable in it at all: a name rather than an empty one, which breaks the build.
        assertEquals("langgraph-agent", Scaffold.packageName("***"));
        assertEquals("langgraph-agent", Scaffold.packageName(""));
    }

    @Test
    void theReadmeDoesNotSendAnybodyToABlockedLauncher() {
        String readme = Scaffold.readme("x");
        assertTrue(readme.contains("-m langgraph_cli"));
        assertFalse(readme.contains("Scripts/langgraph "), readme);
        assertTrue(readme.contains(LangGraphTemplate.NOTEBOOK));
    }

    @Test
    void aBinaryWindowsHasNotClearedYetIsNotABrokenInstall() {
        // Word for word what a brand new .venv printed on this machine, five times running.
        assertTrue(LangGraphTemplate.notClearedYet(
                "ImportError: DLL load failed while importing ormsgpack:"
                        + " An Application Control policy has blocked this file."));
        assertTrue(LangGraphTemplate.notClearedYet("CreateProcess error=4551, blocked"));
        // A real breakage must not be explained away as Windows being slow.
        assertFalse(LangGraphTemplate.notClearedYet(
                "ModuleNotFoundError: No module named 'langgraph'"));
        assertFalse(LangGraphTemplate.notClearedYet(""));
        assertFalse(LangGraphTemplate.notClearedYet(null));
    }

    @Test
    void theGitignoreKeepsTheEnvironmentAndTheSecretsOut() {
        String ignore = Scaffold.gitignore();
        assertTrue(ignore.contains(".venv/"));
        assertTrue(ignore.contains(".env"));
        assertTrue(ignore.contains(".smide/"));
    }
}
