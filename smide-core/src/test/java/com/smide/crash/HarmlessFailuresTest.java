package com.smide.crash;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What is not worth a dialog.
 *
 * <p>Issue #3 was JavaFX's own: embedding Swing means grabbing the focus for the whole window on
 * the Swing component's behalf, one turn of the event loop later, and JavaFX throws if the window
 * stopped being focused in between. Nothing happens to the IDE. The reader got a crash report
 * about it anyway.
 *
 * <p>The test that matters here is the second one: this list must not quietly grow into a way of
 * hiding smIDE's own failures.
 */
class HarmlessFailuresTest {

    /** The stack from issue #3, top frame first. */
    private static IllegalStateException grabFocusFailure() {
        IllegalStateException failure =
                new IllegalStateException("The window must be focused when calling grabFocus()");
        failure.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("com.sun.glass.ui.Window", "grabFocus", "Window.java", 796),
            new StackTraceElement("com.sun.javafx.tk.quantum.WindowStage", "grabFocus", "WindowStage.java", 834),
            new StackTraceElement("com.sun.javafx.embed.swing.newimpl.SwingNodeInteropN$SwingNodeContent",
                    "lambda$focusGrabbed$0", "SwingNodeInteropN.java", 324),
            new StackTraceElement("com.sun.glass.ui.win.WinApplication", "_runLoop", "WinApplication.java", 185),
        });
        return failure;
    }

    @Test
    void theToolkitFailingToGrabFocusIsNotACrash() {
        assertTrue(CrashReporter.harmless(grabFocusFailure()));
    }

    @Test
    void smidesOwnFailuresAreNotSwallowedByIt() {
        assertFalse(CrashReporter.harmless(new IllegalStateException("The window must be focused"
                + " when calling grabFocus()")), "same words, but thrown from nowhere in particular");

        IllegalStateException ours = new IllegalStateException("The window must be focused when calling grabFocus()");
        ours.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("com.smide.ui.MainWindow", "grabFocus", "MainWindow.java", 42),
        });
        assertFalse(CrashReporter.harmless(ours), "smIDE's own code is never in this list");

        assertFalse(CrashReporter.harmless(new NullPointerException()));
        assertFalse(CrashReporter.harmless(new IllegalStateException((String) null)));
        assertFalse(CrashReporter.harmless(new RuntimeException("Editor closed while saving")));
        assertFalse(CrashReporter.harmless(new OutOfMemoryError("Java heap space")));
    }
}
