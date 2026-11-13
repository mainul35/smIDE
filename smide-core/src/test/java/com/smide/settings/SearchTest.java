package com.smide.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchTest {

    @Test
    void everyWordHasToAppearButInAnyOrder() {
        String page = Search.haystack("Editor", "font size wrap");
        assertTrue(Search.matches("font size", page));
        assertTrue(Search.matches("size font", page));
        assertTrue(Search.matches("editor wrap", page));
        assertFalse(Search.matches("font colour", page));
    }

    @Test
    void anEmptySearchHidesNothing() {
        assertTrue(Search.matches("", "anything"));
        assertTrue(Search.matches("   ", "anything"));
        assertTrue(Search.matches(null, "anything"));
    }

    @Test
    void separatorsAreNotSomethingToType() {
        // A settings key, and the two ways somebody would look for it.
        String key = Search.haystack("editor.fontFamily");
        assertTrue(Search.matches("font family", key));
        assertTrue(Search.matches("editor.font", key));
        assertTrue(Search.matches("fontfamily", Search.haystack("fontfamily")));
    }

    @Test
    void caseIsNotSomethingToGetRight() {
        assertTrue(Search.matches("DARK", Search.haystack("Dark theme")));
        assertTrue(Search.matches("dark", Search.haystack("DARK THEME")));
    }

    @Test
    void aShortcutIsFoundByTheKeysYouActuallyPress() {
        String terms = Search.shortcutTerms("shortcut+shift+N");
        // What smIDE writes down.
        assertTrue(Search.matches("shortcut shift n", terms));
        // What somebody on Windows or Linux presses, and types.
        assertTrue(Search.matches("ctrl+shift+n", terms));
        // And on a Mac.
        assertTrue(Search.matches("cmd+shift+n", terms));
        assertTrue(Search.matches("command shift n", terms));
    }

    @Test
    void aShortcutWithNoPlatformModifierIsLeftAlone() {
        assertEquals("f10", Search.shortcutTerms("F10"));
        assertTrue(Search.matches("f10", Search.shortcutTerms("F10")));
        // ctrl+alt+L is stored literally, so it is not also searchable as cmd.
        String literal = Search.shortcutTerms("ctrl+alt+L");
        assertTrue(Search.matches("ctrl alt l", literal));
        assertFalse(Search.matches("cmd alt l", literal));
    }

    @Test
    void anActionWithNoShortcutSearchesAsNothing() {
        assertEquals("", Search.shortcutTerms(""));
        assertEquals("", Search.shortcutTerms(null));
    }

    @Test
    void severalFieldsAreSearchedTogether() {
        assertTrue(Search.matchesAny("git commit", "Git", "com.smide.git", "Commit and push"));
        assertTrue(Search.matchesAny("smide.git", "Git", "com.smide.git", ""));
        assertFalse(Search.matchesAny("mercurial", "Git", "com.smide.git", "Commit and push"));
        assertTrue(Search.matchesAny("", "Git"));
    }

    @Test
    void nothingToSearchIsNotAMatchUnlessNothingWasAsked() {
        assertTrue(Search.matches("", ""));
        assertFalse(Search.matches("anything", ""));
        assertFalse(Search.matches("anything", null));
        assertEquals("", Search.haystack());
        assertEquals("", Search.haystack((String[]) null));
        assertEquals("", Search.haystack(null, "", "   "));
    }
}
