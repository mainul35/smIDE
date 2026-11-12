package com.smide.plugins.langgraph;

import com.smide.api.problems.Diagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangGraphJsonTest {

    private static final String CONFIG = """
            {
              "dependencies": ["."],
              "graphs": {
                "agent": "./src/agent/graph.py:graph"
              },
              "env": ".env"
            }
            """;

    /** A project with the graph, the package and the environment file the configuration names. */
    private static Path project(Path dir, String config) throws IOException {
        Files.createDirectories(dir.resolve("src/agent"));
        Files.writeString(dir.resolve("src/agent/graph.py"), "builder = StateGraph(State)\ngraph = builder.compile()\n");
        Files.writeString(dir.resolve(".env"), "OPENAI_API_KEY=x\n");
        Path file = dir.resolve(LangGraph.CONFIG);
        Files.writeString(file, config);
        return file;
    }

    private static List<String> messages(Path file, String config) {
        return LangGraphJson.problemsIn(file, config).stream().map(Diagnostic::message).toList();
    }

    @Test
    void aConfigurationThatIsRightIsLeftAlone(@TempDir Path dir) throws IOException {
        Path file = project(dir, CONFIG);
        assertEquals(List.of(), messages(file, CONFIG));
    }

    @Test
    void theGraphsItNames(@TempDir Path dir) throws IOException {
        project(dir, CONFIG);
        LangGraph.GraphSpec spec = LangGraphJson.graphsOf(dir).get("agent");
        assertEquals("./src/agent/graph.py", spec.module());
        assertEquals("graph", spec.attribute());
        assertTrue(spec.isFile());
        assertEquals(dir.resolve("src/agent/graph.py"), spec.file(dir));
    }

    @Test
    void aGraphPointingAtAFileThatIsNotThere(@TempDir Path dir) throws IOException {
        String config = CONFIG.replace("./src/agent/graph.py", "./src/agent/agent.py");
        Path file = project(dir, config);
        List<String> problems = messages(file, config);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("no ./src/agent/agent.py here"), problems.get(0));
    }

    @Test
    void aGraphPointingAtAnAttributeThatIsNotThere(@TempDir Path dir) throws IOException {
        String config = CONFIG.replace(":graph\"", ":workflow\"");
        Path file = project(dir, config);
        List<String> problems = messages(file, config);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("nothing called workflow"), problems.get(0));
    }

    @Test
    void aGraphWrittenWithoutAColon(@TempDir Path dir) throws IOException {
        String config = CONFIG.replace("./src/agent/graph.py:graph", "./src/agent/graph.py");
        Path file = project(dir, config);
        assertTrue(messages(file, config).get(0).contains("separated by a colon"));
    }

    @Test
    void theProblemIsOnTheValueItIsAbout(@TempDir Path dir) throws IOException {
        String config = CONFIG.replace("./src/agent/graph.py", "./src/agent/agent.py");
        Path file = project(dir, config);
        Diagnostic problem = LangGraphJson.problemsIn(file, config).get(0);
        String line = config.split("\\R")[problem.startLine()];
        assertEquals("./src/agent/agent.py:graph",
                line.substring(problem.startColumn(), problem.endColumn()));
    }

    @Test
    void noGraphsAtAll(@TempDir Path dir) throws IOException {
        String config = "{\"dependencies\": [\".\"]}";
        Path file = project(dir, config);
        assertTrue(messages(file, config).get(0).startsWith("No graphs"));
    }

    @Test
    void noDependencies(@TempDir Path dir) throws IOException {
        String config = CONFIG.replace("\"dependencies\": [\".\"],", "");
        Path file = project(dir, config);
        assertTrue(messages(file, config).stream().anyMatch(m -> m.startsWith("No dependencies")));
    }

    @Test
    void aDependencyFolderThatIsNotThere(@TempDir Path dir) throws IOException {
        String config = CONFIG.replace("[\".\"]", "[\"./backend\", \"langchain-openai\"]");
        Path file = project(dir, config);
        List<String> problems = messages(file, config);
        assertEquals(List.of("There is no ./backend here."), problems);
    }

    @Test
    void anEnvironmentFileThatIsNotThere(@TempDir Path dir) throws IOException {
        String config = CONFIG.replace("\".env\"", "\".env.local\"");
        Path file = project(dir, config);
        assertTrue(messages(file, config).get(0).contains("no .env.local here"));
    }

    @Test
    void halfTypedJsonIsNotThisPluginsBusiness(@TempDir Path dir) throws IOException {
        Path file = project(dir, CONFIG);
        assertEquals(List.of(), messages(file, "{\"graphs\": {"));
    }

    @Test
    void whatCountsAsDefiningAName() {
        assertTrue(LangGraphJson.defines("graph = builder.compile()\n", "graph"));
        assertTrue(LangGraphJson.defines("graph: CompiledGraph = builder.compile()\n", "graph"));
        assertTrue(LangGraphJson.defines("def make_graph():\n    pass\n", "make_graph"));
        assertTrue(LangGraphJson.defines("async def make_graph():\n    pass\n", "make_graph"));
        assertTrue(LangGraphJson.defines("from .workflow import graph\n", "graph"));
        assertFalse(LangGraphJson.defines("graph_builder = StateGraph(State)\n", "graph"));
        assertFalse(LangGraphJson.defines("print(graph)\n", "graph"));
        assertFalse(LangGraphJson.defines("    graph = builder.compile()\n", "graph"));
    }
}
