package com.smide.plugins.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading what a local Ollama says it has.
 *
 * <p>Everything else the assistant talks to answers in the OpenAI shape, and so does Ollama when
 * it is addressed at {@code /v1}. Addressed at the root - which is the address its own
 * documentation gives - it answers in this one instead, and the model dropdown came back empty.
 */
class OllamaTest {

    @Test
    void theNamesOutOfItsOwnAnswer() {
        String tags = """
                {"models":[
                  {"name":"gemma4:31b","size":18000000000},
                  {"name":"qwen3-coder-agent:latest","size":9000000000},
                  {"name":"llama3.3:70b","size":42000000000}]}
                """;

        assertEquals(List.of("gemma4:31b", "qwen3-coder-agent:latest", "llama3.3:70b"),
                Ollama.namesIn(tags), "in the order it gave them");
    }

    @Test
    void newerBuildsThatSayModelRatherThanName() {
        assertEquals(List.of("phi4:latest"),
                Ollama.namesIn("{\"models\":[{\"model\":\"phi4:latest\"}]}"));
    }

    @Test
    void oneModelListedTwiceIsOneModel() {
        assertEquals(List.of("gemma4:31b"), Ollama.namesIn(
                "{\"models\":[{\"name\":\"gemma4:31b\"},{\"name\":\"gemma4:31b\"}]}"));
    }

    @Test
    void anythingElseIsNoAnswerRatherThanAnError() {
        assertTrue(Ollama.namesIn(null).isEmpty());
        assertTrue(Ollama.namesIn("").isEmpty());
        assertTrue(Ollama.namesIn("not json at all").isEmpty());
        assertTrue(Ollama.namesIn("[]").isEmpty(), "an array where an object was expected");
        assertTrue(Ollama.namesIn("{\"error\":\"model not found\"}").isEmpty());
        assertTrue(Ollama.namesIn("{\"models\":\"all of them\"}").isEmpty());
        assertTrue(Ollama.namesIn("{\"models\":[{\"size\":1},{\"name\":\"\"}]}").isEmpty(),
                "entries with no name in them are not names");
    }
}
