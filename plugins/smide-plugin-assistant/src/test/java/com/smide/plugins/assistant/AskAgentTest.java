package com.smide.plugins.assistant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void aCallWithoutItsFenceIsStillACall() {
        // What a local Harmony model sends: the routing, no fence, and the call mid-sentence.
        String reply = "Let's replace file content with added methods. to=smide"
                + " {\"tool\": \"replace_in_file\", \"path\": \"a/B.java\","
                + " \"find\": \"void x()\", \"replace\": \"void y()\"}<|eot|>";

        String call = AskAgent.callIn(reply);

        assertNotNull(call, "a turn ends half done when this is read as an answer");
        assertTrue(call.startsWith("{") && call.endsWith("}"), call);
        assertTrue(call.contains("replace_in_file"), call);
    }

    @Test
    void anObjectWithBracesInsideItIsTakenWhole() {
        String reply = "to=smide {\"tool\": \"write_file\", \"path\": \"A.java\","
                + " \"content\": \"class A { int x; }\"}";

        String call = AskAgent.callIn(reply);

        assertNotNull(call);
        assertTrue(call.endsWith("}\"}"), call);
        assertTrue(call.contains("class A { int x; }"), call);
    }

    @Test
    void writingAboutAToolIsNotCallingOne() {
        assertNull(AskAgent.callIn("The IDE takes {\"tool\": \"not_a_real_tool\"} and ignores it."));
        assertNull(AskAgent.callIn("It sends a JSON object with a \"tool\" in it."));
        assertNull(AskAgent.callIn("An unfinished one: {\"tool\": \"build\""));
    }

    @Test
    void theControlTokensOfTheModelAreNotForTheReader() {
        assertEquals("The build fails.", Replies.cleaned("The build fails.<|eot|>"));
        assertEquals("The build fails.",
                Replies.cleaned("<|start|>assistant<|message|>The build fails.<|im_end|>"));
        assertEquals("", Replies.cleaned("<|eot|>"));
        assertNull(Replies.cleaned(null));
    }

    @Test
    void whenTheModelSaysWhichPartIsTheAnswerThatIsTheAnswer() {
        String harmony = "<|channel|>analysis<|message|>They want the cause, not the fix. Look at"
                + " the field first.<|end|><|channel|>final<|message|>`total` is never declared.<|eot|>";

        assertEquals("`total` is never declared.", Replies.cleaned(harmony));
    }

    @Test
    void asentenceAboutGoingToSomewhereIsLeftAlone() {
        String said = "Set spring.datasource.url to=jdbc and it connects.";

        assertEquals(said, Replies.cleaned(said));
    }

    @Test
    void anUnclosedFenceDoesNotSwallowTheRestOfTheConversation() {
        String cutOff = "Here is the fix:\n\n```java\nreturn 0;";

        String fixed = AskPanel.closed(cutOff);

        assertTrue(fixed.endsWith("```\n"), fixed);
        assertEquals(2, fixed.lines().filter(line -> line.strip().startsWith("```")).count());
    }

    @Test
    void oneThatIsClosedAlreadyIsLeftAlone() {
        String whole = "Look:\n\n```java\nreturn 0;\n```\n";

        assertEquals(whole, AskPanel.closed(whole));
    }

    @Test
    void enterInsideACodeBlockIsALineRatherThanASend() {
        String opened = "Look at this:\n\n```java\nint a = 1;";

        assertTrue(AskPanel.insideCode(opened, opened.length()));
        // The caret before the fence is not in a block, whatever comes after it.
        assertFalse(AskPanel.insideCode(opened, 5));
    }

    @Test
    void aClosedBlockIsBehindYouAgain() {
        String closed = "Look:\n\n```java\nint a = 1;\n```\nand then?";

        assertFalse(AskPanel.insideCode(closed, closed.length()));
        assertTrue(AskPanel.insideCode(closed, closed.indexOf("int a")));
    }

    @Test
    void plainTextIsNeverACodeBlock() {
        assertFalse(AskPanel.insideCode("Why will this not build?", 24));
        assertFalse(AskPanel.insideCode("", 0));
        assertFalse(AskPanel.insideCode(null, 0));
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
