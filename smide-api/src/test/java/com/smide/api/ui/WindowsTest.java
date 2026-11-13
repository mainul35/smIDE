package com.smide.api.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which window a chooser hangs off.
 *
 * <p>The toolkit is not involved: what can be wrong here is the choosing, and a JavaFX
 * window cannot be made without a display anyway. The windows are strings and "focused"
 * is a set.
 */
class WindowsTest {

    private static final String MAIN = "main";

    @Test
    void theFocusedWindowIsTheOneAChooserBelongsTo() {
        // The button that opens a chooser is on the dialog that has focus, so that is the
        // window the chooser is a child of - not the main window behind it.
        Set<String> focused = Set.of("New Project");
        assertEquals("New Project", Windows.frontmostOf(
                List.of(MAIN, "New Project"), focused::contains, MAIN));
    }

    @Test
    void withNothingFocusedTheMainWindowWillDo() {
        assertEquals(MAIN, Windows.frontmostOf(
                List.of(MAIN, "New Project"), window -> false, MAIN));
    }

    @Test
    void withNoWindowsAtAllTheFallbackStands() {
        assertEquals(MAIN, Windows.frontmostOf(List.of(), window -> true, MAIN));
        assertEquals(MAIN, Windows.frontmostOf(null, window -> true, MAIN));
    }

    @Test
    void theFirstFocusedWindowIsTaken() {
        // Two claiming focus is not something to arbitrate; it is something not to crash on.
        assertEquals("a", Windows.frontmostOf(List.of("a", "b"), window -> true, MAIN));
    }

    @Test
    void aFallbackOfNothingIsStillAnAnswer() {
        // showDialog(null) is legal and means an unowned chooser, which is better than
        // refusing to open one.
        assertEquals(null, Windows.frontmostOf(List.of("a"), window -> false, null));
    }
}
