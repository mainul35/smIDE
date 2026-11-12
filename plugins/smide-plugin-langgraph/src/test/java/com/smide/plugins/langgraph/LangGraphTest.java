package com.smide.plugins.langgraph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangGraphTest {

    @Test
    void aGraphIsWrittenAsAFileAndWhatIsInIt() {
        LangGraph.GraphSpec spec = LangGraph.parseSpec("agent", "./src/agent/graph.py:graph").orElseThrow();
        assertEquals("agent", spec.id());
        assertEquals("./src/agent/graph.py", spec.module());
        assertEquals("graph", spec.attribute());
        assertTrue(spec.isFile());
    }

    @Test
    void orAsAModule() {
        LangGraph.GraphSpec spec = LangGraph.parseSpec("agent", "agent.graph:graph").orElseThrow();
        assertEquals("agent.graph", spec.module());
        assertFalse(spec.isFile());
    }

    @Test
    void aWindowsPathKeepsItsDriveLetter() {
        LangGraph.GraphSpec spec = LangGraph.parseSpec("agent", "C:/work/graph.py:graph").orElseThrow();
        assertEquals("C:/work/graph.py", spec.module());
        assertEquals("graph", spec.attribute());
    }

    @Test
    void whatIsNotAGraphSpec() {
        assertTrue(LangGraph.parseSpec("agent", "./graph.py").isEmpty());
        assertTrue(LangGraph.parseSpec("agent", "./graph.py:").isEmpty());
        assertTrue(LangGraph.parseSpec("agent", ":graph").isEmpty());
        assertTrue(LangGraph.parseSpec("", "./graph.py:graph").isEmpty());
    }

    @Test
    void aManifestThatAsksForLangGraph() {
        assertTrue(LangGraph.asksForLangGraph("langgraph>=0.2.0\nlangchain-openai\n"));
        assertTrue(LangGraph.asksForLangGraph("dependencies = [\"langgraph>=0.2\", \"httpx\"]"));
        assertTrue(LangGraph.asksForLangGraph("langgraph[inmem]==0.2.1"));
        assertFalse(LangGraph.asksForLangGraph("langchain\nlanggraph-sdk\n"));
        assertFalse(LangGraph.asksForLangGraph("# langgraph\n"));
        assertFalse(LangGraph.asksForLangGraph(""));
    }

    @Test
    void aProjectIsOneWithAConfigurationOrTheDependency(@TempDir Path dir) throws IOException {
        assertFalse(LangGraph.isProject(dir));
        Files.writeString(dir.resolve("requirements.txt"), "httpx\n");
        assertFalse(LangGraph.isProject(dir));
        Files.writeString(dir.resolve("requirements.txt"), "httpx\nlanggraph>=0.2\n");
        assertTrue(LangGraph.isProject(dir));
    }

    @Test
    void aGraphFileIsFoundFromItsProjectRoot(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/agent"));
        Files.writeString(dir.resolve(LangGraph.CONFIG), "{\"graphs\": {}}");
        Path graph = dir.resolve("src/agent/graph.py");
        Files.writeString(graph, "builder = StateGraph(State)\n");
        assertEquals(dir, LangGraph.rootOf(graph).orElseThrow());
    }

    @Test
    void whereLangGraphJsonIsBelowTheRoot(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("backend"));
        Files.writeString(dir.resolve("backend").resolve(LangGraph.CONFIG), "{\"graphs\": {}}");
        assertEquals(dir.resolve("backend"), LangGraphRunType.configDir(dir));
    }
}
