package com.smide.plugins.assistant;

import com.mdviewer.ai.ChatProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A conversation belongs to a project, and outlives the session it was had in.
 *
 * <p>Two projects open at once are two different questions in hand; closing the IDE in the middle
 * of one of them should not be the same as ending it.
 */
class AskConversationTest {

    @TempDir
    Path home;
    @TempDir
    Path projects;

    private AskConversation about(String project) {
        return AskConversation.read(home, projects.resolve(project));
    }

    @Test
    void whatWasSaidComesBack() {
        AskConversation first = about("shop");
        first.said.append("### You asked\n\nwhy will it not build?\n");
        first.questions.add("why will it not build?");
        first.draft = "and the other module?";
        first.write(home);

        AskConversation again = about("shop");

        assertTrue(again.said.toString().contains("why will it not build?"));
        assertEquals(List.of("why will it not build?"), again.questions);
        assertEquals("and the other module?", again.draft);
        assertFalse(again.isEmpty());
    }

    @Test
    void theModelsOwnMemoryComesBackToo() {
        AskConversation first = about("shop");
        first.rememberFrom(null);
        first.said.append("something");
        first.write(home);
        // What the agent would have had: the questions and its own answers, not the prompt.
        Path file = AskConversation.fileIn(home, projects.resolve("shop"));
        assertTrue(Files.exists(file));

        AskConversation withHistory = new AskConversation(projects.resolve("orders"));
        withHistory.said.append("x");
        withHistory.write(home);
        assertEquals(List.of(), about("orders").remembered());
    }

    @Test
    void twoProjectsAreTwoConversations() {
        AskConversation shop = about("shop");
        shop.said.append("about the shop");
        shop.questions.add("shop?");
        shop.write(home);

        AskConversation orders = about("orders");
        orders.said.append("about the orders");
        orders.questions.add("orders?");
        orders.write(home);

        assertTrue(about("shop").said.toString().contains("shop"));
        assertFalse(about("shop").said.toString().contains("orders"));
        assertEquals(List.of("orders?"), about("orders").questions);
        assertNotEquals(AskConversation.fileIn(home, projects.resolve("shop")),
                AskConversation.fileIn(home, projects.resolve("orders")));
    }

    @Test
    void twoProjectsOfTheSameNameAreStillTwoConversations() {
        Path one = projects.resolve("work/server");
        Path two = projects.resolve("experiments/server");

        assertNotEquals(AskConversation.fileIn(home, one), AskConversation.fileIn(home, two));
        assertTrue(AskConversation.fileIn(home, one).getFileName().toString().startsWith("server-"),
                "named after the project, so the folder can be read by a person");
    }

    @Test
    void aProjectNeverAskedAboutIsSimplyEmpty() {
        AskConversation fresh = about("untouched");

        assertTrue(fresh.isEmpty());
        assertEquals("", fresh.draft);
        assertEquals(List.of(), fresh.remembered());
    }

    @Test
    void newQuestionLeavesNothingBehind() {
        AskConversation conversation = about("shop");
        conversation.said.append("all of it");
        conversation.questions.add("one");
        conversation.write(home);

        conversation.forget(home);

        assertTrue(conversation.isEmpty());
        assertFalse(Files.exists(AskConversation.fileIn(home, projects.resolve("shop"))));
        assertTrue(about("shop").isEmpty());
    }

    @Test
    void aConversationTooLongToKeepKeepsItsEnd() {
        AskConversation conversation = about("shop");
        conversation.said.append("x".repeat(900_000)).append("THE LATEST ANSWER");
        conversation.write(home);

        String back = about("shop").said.toString();

        assertTrue(back.endsWith("THE LATEST ANSWER"), "the end is what anybody scrolls to");
        assertTrue(back.length() < 900_000, "and the rest is let go: " + back.length());
        assertTrue(back.startsWith("*The beginning"), back.substring(0, 40));
    }

    @Test
    void aFileThatMakesNoSenseIsNotAnError() throws IOException {
        Path file = AskConversation.fileIn(home, projects.resolve("shop"));
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        AskConversation conversation = about("shop");

        assertTrue(conversation.isEmpty(), "one conversation lost, not a broken IDE");
    }

    @Test
    void aTurnIsNotLostWhenTheIdeStopsWhileWriting() throws IOException {
        AskConversation conversation = about("shop");
        conversation.said.append("the good one");
        conversation.write(home);
        Path file = AskConversation.fileIn(home, projects.resolve("shop"));
        String good = Files.readString(file, StandardCharsets.UTF_8);

        // What a half-finished write would have left beside it, had it not been moved into place.
        Files.writeString(file.resolveSibling(file.getFileName() + ".new"), "{ half of it",
                StandardCharsets.UTF_8);

        assertEquals(good, Files.readString(file, StandardCharsets.UTF_8));
        assertTrue(about("shop").said.toString().contains("the good one"));
    }

    @Test
    void whatIsRememberedIsWhatTheModelWasTold() {
        AskConversation conversation = about("shop");
        Stub agent = new Stub(List.of(
                new ChatProvider.Message("user", "why will it not build?"),
                new ChatProvider.Message("assistant", "because `total` is never declared")));
        conversation.rememberFrom(agent.agent());
        conversation.write(home);

        List<ChatProvider.Message> back = about("shop").remembered();

        assertEquals(2, back.size());
        assertEquals("user", back.get(0).role());
        assertEquals("because `total` is never declared", back.get(1).content());
    }

    /** An agent with a history and nothing else, since that is all the conversation asks of it. */
    private static final class Stub {

        private final List<ChatProvider.Message> history;

        Stub(List<ChatProvider.Message> history) {
            this.history = history;
        }

        AskAgent agent() {
            AskAgent agent = new AskAgent(null, null, null, null);
            agent.restore(history);
            return agent;
        }
    }
}
