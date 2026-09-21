package com.smide.plugins.assistant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Telling a tool call from an answer, and a request to fix something from a question about it. */
class AskAgentTest {

    @Test
    void anAnswerIsNotACall() {
        assertNull(AskAgent.callIn("The build fails because `total` is never declared."));
        assertNull(AskAgent.callIn("Here is the fix:\n\n```java\nreturn 0;\n```\n"));
        assertNull(AskAgent.callIn(null));
    }

    @Test
    void aCallIsReadWhateverTheFenceSays() {
        String expected = "{\"tool\": \"read_file\", \"path\": \"a/B.java\"}";
        assertEquals(expected, AskAgent.callIn("```smide\n" + expected + "\n```"));
        assertEquals(expected, AskAgent.callIn("```json\n" + expected + "\n```"));
        assertEquals(expected, AskAgent.callIn("```\n" + expected + "\n```"));
    }

    @Test
    void theLastCallIsTheOneThatCounts() {
        String reply = "```smide\n{\"tool\": \"list_files\", \"path\": \"\"}\n```\n"
                + "on reflection:\n```smide\n{\"tool\": \"read_file\", \"path\": \"B.java\"}\n```";

        assertTrue(AskAgent.callIn(reply).contains("read_file"));
    }

    @Test
    void aFencedExampleWithNoToolInItIsJustCode() {
        assertNull(AskAgent.callIn("```json\n{\"name\": \"example\"}\n```"));
    }

    @Test
    void askingForAFixIsNotTheSameAsAskingAboutOne() {
        assertTrue(AskPanel.meansFixIt("Why will this not build? Fix it for me."));
        assertTrue(AskPanel.meansFixIt("go ahead and sort it out"));
        assertTrue(AskPanel.meansFixIt("do it yourself"));
        assertFalse(AskPanel.meansFixIt("Why does this test fail?"));
        assertFalse(AskPanel.meansFixIt("What would fix this?"));
        assertFalse(AskPanel.meansFixIt("Where is the retry handled?"));
    }
}
