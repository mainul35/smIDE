package com.smide.ui;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Keeps the window the size the reader gave it while dialogs come and go.
 *
 * <p>A dialog is a window of its own, and a desktop that gives one focus can put the window
 * behind it back to its old size - so opening Run/Debug Configurations dropped a full-screen
 * IDE to a small window in the middle of the screen, and closing it did not bring the size
 * back. Full screen is something the reader chose; a question is not a reason to undo it.
 *
 * <p>Nothing here trusts {@code isFullScreen()} alone. A window made full screen by the
 * desktop rather than by the IDE - the window manager's own shortcut, its title-bar menu - is
 * full screen without JavaFX knowing it, and the first version of this asked JavaFX and so did
 * nothing at all. What is remembered is the geometry: where the window was and how large, kept
 * up to date whenever no dialog is open, and given back if it changes while one opens.
 */
public final class FullScreen {

    /** How long after a dialog opens the desktop's changes are taken to be the dialog's doing. */
    private static final long SETTLING_MILLIS = 2500;

    private static final String WANTED = "smide.window.wanted";
    private static final String RESTORING = "smide.window.restoring";

    private FullScreen() {
    }

    /**
     * Remembers the window's geometry whenever it is the reader's doing.
     *
     * <p>Which is any change with no dialog on screen: the reader dragging an edge, maximising,
     * going full screen. While a dialog is open the geometry is left as it was, because a change
     * then is what this exists to undo.
     */
    public static void remember(Stage stage) {
        ChangeListener<Object> record = (o, was, now) -> {
            if (noDialogIsOpen(stage) && !Boolean.TRUE.equals(stage.getProperties().get(RESTORING))) {
                stage.getProperties().put(WANTED, Size.of(stage));
            }
        };
        stage.widthProperty().addListener(record);
        stage.heightProperty().addListener(record);
        stage.xProperty().addListener(record);
        stage.yProperty().addListener(record);
        stage.maximizedProperty().addListener(record);
        stage.fullScreenProperty().addListener(record);
        stage.getProperties().put(WANTED, Size.of(stage));
    }

    /** Watches every window this application opens, and puts the size back after each. */
    public static void keep(Stage stage) {
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window opened : change.getAddedSubList()) {
                    if (opened != stage && opened instanceof Stage dialog) {
                        guard(stage, dialog);
                    }
                }
            }
        });
    }

    /**
     * Holds the window to its remembered size while one dialog opens and closes.
     *
     * <p>Only for a window that filled its screen: a window of an ordinary size that the desktop
     * nudges is not worth fighting over, and the reader may well be the one moving it.
     */
    private static void guard(Stage stage, Stage dialog) {
        if (!(stage.getProperties().get(WANTED) instanceof Size wanted) || !wanted.fillsItsScreen()) {
            return;
        }
        long until = System.currentTimeMillis() + SETTLING_MILLIS;
        ChangeListener<Object> watch = (o, was, now) -> {
            if (System.currentTimeMillis() <= until) {
                restore(stage, wanted);
            }
        };
        stage.widthProperty().addListener(watch);
        stage.heightProperty().addListener(watch);
        stage.maximizedProperty().addListener(watch);
        stage.fullScreenProperty().addListener(watch);
        dialog.showingProperty().addListener((o, was, showing) -> {
            if (showing) {
                return;
            }
            // Closing hands focus back, which is the second moment the desktop may resize us.
            stage.widthProperty().removeListener(watch);
            stage.heightProperty().removeListener(watch);
            stage.maximizedProperty().removeListener(watch);
            stage.fullScreenProperty().removeListener(watch);
            restore(stage, wanted);
            Platform.runLater(() -> restore(stage, wanted));
        });
    }

    /** Puts the window back, unless it is already there. */
    private static void restore(Stage stage, Size wanted) {
        if (!stage.isShowing() || wanted.matches(stage)) {
            return;
        }
        Platform.runLater(() -> {
            if (!stage.isShowing() || wanted.matches(stage)) {
                return;
            }
            // So that what this does is not taken for the reader's own resizing.
            stage.getProperties().put(RESTORING, Boolean.TRUE);
            try {
                wanted.applyTo(stage);
            } finally {
                Platform.runLater(() -> stage.getProperties().put(RESTORING, Boolean.FALSE));
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

    /** Where a window was and how large, with the two states that are not a size. */
    private record Size(double x, double y, double width, double height, boolean maximized, boolean fullScreen) {

        static Size of(Stage stage) {
            return new Size(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(),
                    stage.isMaximized(), stage.isFullScreen());
        }

        /** Whether this is a window worth holding on to: full screen, maximised, or as good as. */
        boolean fillsItsScreen() {
            if (maximized || fullScreen) {
                return true;
            }
            Rectangle2D screen = Screen.getScreensForRectangle(x, y, Math.max(1, width), Math.max(1, height))
                    .stream().findFirst().orElse(Screen.getPrimary()).getBounds();
            return width >= screen.getWidth() * 0.94 && height >= screen.getHeight() * 0.9;
        }

        boolean matches(Stage stage) {
            return stage.isMaximized() == maximized && stage.isFullScreen() == fullScreen
                    && Math.abs(stage.getWidth() - width) < 2 && Math.abs(stage.getHeight() - height) < 2;
        }

        void applyTo(Stage stage) {
            if (fullScreen) {
                // Off and on again: the desktop may have taken it away without telling JavaFX,
                // and setting a property to what it already holds does nothing at all.
                stage.setFullScreen(false);
                stage.setFullScreen(true);
                return;
            }
            if (maximized) {
                stage.setMaximized(false);
                stage.setMaximized(true);
                return;
            }
            stage.setX(x);
            stage.setY(y);
            stage.setWidth(width);
            stage.setHeight(height);
        }
    }
}
