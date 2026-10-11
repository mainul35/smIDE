package com.smide.plugins.assistant;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the model is told it may do to the project.
 *
 * <p>The wording used to be "Do not change a file unless the developer asks you to fix it", and
 * models took it for a cage: they announced that tools were blocked, described the edit they
 * would have made, and stopped - so the card that asks the developer never appeared, and the
 * developer was never asked. Nothing was blocked. The test is that what the model is told matches
 * what the IDE actually does.
 */
class AskPromptsTest {

    @Test
    void askingAQuestionStillLetsItProposeAChange() {
        String said = AskPrompts.permission(false).toLowerCase(Locale.ROOT);

        assertTrue(said.contains("write_file"), said);
        assertTrue(said.contains("nothing is written unless they accept it"), said);
        assertFalse(said.contains("do not change a file"),
                "which is what made it announce that tools were blocked");
    }

    @Test
    void andNeverToClaimItCannot() {
        String said = AskPrompts.permission(false).toLowerCase(Locale.ROOT);

        assertTrue(said.contains("never say you are unable to change files"), said);
    }

    @Test
    void itIsStillNotAnInvitationToRewriteEverything() {
        String said = AskPrompts.permission(false).toLowerCase(Locale.ROOT);

        assertTrue(said.contains("not do is go further than the question"), said);
    }

    @Test
    void toldToFixItTheWordingIsAboutDoingRatherThanProposing() {
        String said = AskPrompts.permission(true).toLowerCase(Locale.ROOT);

        assertTrue(said.contains("carry the fix out"), said);
        assertTrue(said.contains("build and test"), said);
    }
}
