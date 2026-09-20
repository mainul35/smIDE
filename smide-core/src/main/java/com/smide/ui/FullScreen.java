package com.smide.ui;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Keeps the window full screen while dialogs come and go.
 *
 * <p>A dialog is a window of its own, and a window manager that gives one focus can take the
 * window behind it out of full screen - so asking a question about a build dropped the IDE
 * back to its old size, and answering it did not bring it back. Full screen is something the
 * reader chose; a question is not a reason to undo it.
 */
public final class FullScreen {

    private FullScreen() {
    }

    /** Watches every window this application opens, and restores full screen after each closes. */
    public static void keep(Stage stage) {
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window opened : change.getAddedSubList()) {
                    if (opened != stage && opened instanceof Stage dialog && stage.isFullScreen()) {
                        watch(stage, dialog);
                    }
                }
            }
        });
    }

    private static void watch(Stage stage, Stage dialog) {
        // While it is open, and once more when it closes: the manager may take full screen away
        // at either point, and the answer is the same both times.
        dialog.showingProperty().addListener((o, was, showing) -> {
            if (!showing) {
                restore(stage);
            }
        });
        restore(stage);
    }

    private static void restore(Stage stage) {
        Platform.runLater(() -> {
            if (stage.isShowing() && !stage.isFullScreen() && Boolean.TRUE.equals(stage.getProperties().get(WANTED))) {
                stage.setFullScreen(true);
            }
        });
    }

    private static final String WANTED = "smide.fullScreen.wanted";

    /** Remembers what the reader asked for, so it can be given back after a dialog. */
    public static void remember(Stage stage) {
        stage.fullScreenProperty().addListener((o, was, now) -> {
            if (now || noDialogIsOpen(stage)) {
                // Full screen turned on, or turned off with nothing else on screen: their doing.
                stage.getProperties().put(WANTED, now);
            }
        });
    }

    private static boolean noDialogIsOpen(Stage stage) {
        for (Window window : Window.getWindows()) {
            if (window != stage && window.isShowing() && window instanceof Stage) {
                return false;
            }
        }
        return true;
    }
}
