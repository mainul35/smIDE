package com.smide.plugins.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Picking the model that can do the job.
 *
 * <p>A screenshot pasted into a question went to a model that reads only text and came back with
 * nothing, which from the outside looks like a broken connection. Names are the only thing an
 * OpenAI-compatible endpoint gives away about what its models can do, so this is about reading
 * names - and about being careful with the answer a model gives when it is asked to choose.
 */
class ModelChoiceTest {

    @Test
    void theOnesWithEyes() {
        assertTrue(ModelChoice.canSee("llava:13b"));
        assertTrue(ModelChoice.canSee("qwen2.5-vl:7b"));
        assertTrue(ModelChoice.canSee("llama3.2-vision:11b"));
        assertTrue(ModelChoice.canSee("gemma3:27b"));
        assertTrue(ModelChoice.canSee("gpt-4o-mini"));
        assertTrue(ModelChoice.canSee("MiniCPM-V-2_6"), "whatever case it is written in");
    }

    @Test
    void andTheOnesWithout() {
        assertFalse(ModelChoice.canSee("qwen3-coder-agent:latest"),
                "the one that was sent a screenshot and said nothing");
        assertFalse(ModelChoice.canSee("deepseek-r1:14b"));
        assertFalse(ModelChoice.canSee("codellama:7b"));
        assertFalse(ModelChoice.canSee(""));
        assertFalse(ModelChoice.canSee(null));
    }

    @Test
    void aPictureGoesToSomethingThatCanSeeIt() {
        List<String> offered = List.of("qwen3-coder-agent:latest", "deepseek-r1:14b", "llava:13b");

        assertEquals("llava:13b",
                ModelChoice.fallback(offered, "qwen3-coder-agent:latest", true));
    }

    @Test
    void andTheModelThatWasChosenIsKeptWhenItWillDo() {
        List<String> offered = List.of("llava:13b", "gemma3:27b");

        assertEquals("gemma3:27b", ModelChoice.fallback(offered, "gemma3:27b", true),
                "somebody who set a model that can see meant it");
        assertEquals("qwen3-coder-agent:latest",
                ModelChoice.fallback(offered, "qwen3-coder-agent:latest", false),
                "and a question without a picture is nobody else's business");
    }

    @Test
    void whenNothingOnTheEndpointCanSeeThereIsNothingToPick() {
        assertNull(ModelChoice.fallback(List.of("codellama:7b", "deepseek-r1:14b"),
                "codellama:7b", true));
    }

    @Test
    void theSmallestThingToAskAOneLineQuestionOf() {
        assertEquals("qwen3:1.7b", ModelChoice.smallest(
                List.of("llama3.3:70b", "qwen3:1.7b", "gemma3:27b"), "fallback"));
        assertEquals("gpt-4o-mini", ModelChoice.smallest(
                List.of("gpt-4o", "gpt-4o-mini"), "fallback"));
        assertEquals("fallback", ModelChoice.smallest(List.of("llama3.3:70b"), "fallback"),
                "nothing small here, so do not pretend");
    }

    @Test
    void aBigModelIsNotASmallOneBecauseOfHowItsSizeIsSpelled() {
        assertEquals("fallback", ModelChoice.smallest(
                List.of("deepseek-r1:14b", "llama3.3:70b", "qwen2.5:32b"), "fallback"),
                "14b ends in 4b and is not 4b");
        assertEquals("qwen3:1.7b", ModelChoice.smallest(
                List.of("deepseek-r1:14b", "qwen3:1.7b"), "fallback"));
        assertEquals("gemma:2b", ModelChoice.smallest(List.of("gemma:2b"), "fallback"));
    }

    @Test
    void readingTheAnswerOutOfWhateverTheModelSaid() {
        List<String> offered = List.of("llava:13b", "gemma3:27b", "qwen3-coder-agent:latest");

        assertEquals("llava:13b", ModelChoice.named("llava:13b", offered));
        assertEquals("llava:13b", ModelChoice.named("`llava:13b`", offered));
        assertEquals("gemma3:27b",
                ModelChoice.named("I would use gemma3:27b for this - it reads pictures.", offered));
        assertEquals("gemma3:27b", ModelChoice.named("- **gemma3:27b**", offered));
    }

    @Test
    void andRefusingToGuessWhenItSaidSomethingElse() {
        List<String> offered = List.of("llava:13b", "gemma3:27b");

        assertNull(ModelChoice.named("whichever you like", offered));
        assertNull(ModelChoice.named("gpt-4o", offered), "a model it was not offered");
        assertNull(ModelChoice.named(null, offered));
        assertNull(ModelChoice.named("llava:13b", null));
    }

    @Test
    void theLongerNameWinsWhenOneContainsTheOther() {
        List<String> offered = List.of("qwen3:8b", "qwen3:8b-instruct");

        assertEquals("qwen3:8b-instruct", ModelChoice.named("use qwen3:8b-instruct", offered));
    }

    @Test
    void theQuestionItIsAskedNamesTheCandidatesAndTheJob() {
        String asked = ModelChoice.question(List.of("llava:13b", "gemma3:27b"),
                "why does this dialog appear after login?", true);

        assertTrue(asked.contains("llava:13b"), asked);
        assertTrue(asked.contains("gemma3:27b"), asked);
        assertTrue(asked.contains("includes a picture"), asked);
        assertTrue(asked.contains("after login"), asked);
    }

    @Test
    void andIsNotTheWholeConversation() {
        String asked = ModelChoice.question(List.of("a", "b"), "x".repeat(5000), false);

        assertTrue(asked.length() < 1200, "the choosing pays for every word: " + asked.length());
    }
}
