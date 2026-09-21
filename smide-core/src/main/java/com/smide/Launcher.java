package com.smide;

/**
 * Entry point for the class-path run and the packaged application.
 *
 * <p>When JavaFX is on the class path the JVM refuses to start a main class that extends
 * {@code Application}; a plain class that calls into {@link SmIdeApp} sidesteps that, so
 * {@code java -cp ... com.smide.Launcher} works with no JavaFX SDK on the machine.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        // Before the toolkit starts, which is the only time glass.gtk.uiScale is read.
        com.smide.ui.UiScale.apply();
        SmIdeApp.main(args);
    }
}
