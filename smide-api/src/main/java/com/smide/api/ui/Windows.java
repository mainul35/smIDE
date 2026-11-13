package com.smide.api.ui;

import javafx.scene.control.Dialog;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * How a dialog is tied to the window it belongs to.
 *
 * <p>A modal dialog is the obvious thing to want: while it is up, the window behind it should not
 * be typed into. What that costs on Linux is the window itself. Making a dialog modal disables the
 * window behind it; disabling a window is done by pinning its size - the least it may be and the
 * most it may be, both set to the size it has - and a window that may not be resized is one a
 * window manager will not keep maximised. It is put back to the size it had before it was
 * maximised, which is the "the IDE jumped to its default size when a dialog opened" that kept
 * being reported.
 *
 * <p>So on Linux a dialog is owned but not modal: it opens over its window, stays in front of it,
 * and closes with it. What is given up is that the window behind can still be clicked while a
 * question is on screen. What is kept is the window the reader arranged. Everywhere else, where
 * none of this happens, a dialog is modal exactly as before.
 */
public final class Windows {

    private static final boolean LINUX =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");

    private Windows() {
    }

    /** Ties a window of its own to its owner. */
    public static void belongsTo(Stage dialog, Window owner) {
        if (owner != null && owner.isShowing()) {
            dialog.initOwner(owner);
        }
        if (LINUX) {
            dialog.initModality(Modality.NONE);
            dialog.setAlwaysOnTop(true);
        } else {
            dialog.initModality(Modality.WINDOW_MODAL);
        }
    }

    /** The same for a JavaFX dialog - an alert, a confirmation - which owns its window itself. */
    public static void belongsTo(Dialog<?> dialog, Window owner) {
        if (owner != null && owner.isShowing()) {
            dialog.initOwner(owner);
        }
        dialog.initModality(LINUX ? Modality.NONE : Modality.APPLICATION_MODAL);
        if (LINUX && dialog.getDialogPane().getScene() != null
                && dialog.getDialogPane().getScene().getWindow() instanceof Stage stage) {
            stage.setAlwaysOnTop(true);
        }
    }

    /** Whether a dialog here is modal: for anything that needs to know what it is dealing with. */
    public static boolean isModal() {
        return !LINUX;
    }

    /**
     * The window a chooser opened right now should hang off.
     *
     * <p>Not the main window. A file or folder chooser is almost always opened from a
     * button on a dialog - "Project location" from New Project, a JDK home from Settings -
     * and handing it the main window as its owner makes it a sibling of the dialog rather
     * than a child of it, which is how it ends up behind the thing that asked for it.
     *
     * <p>The focused window is the one whose button was just clicked, so that is the one
     * taken, and the main window is the fallback for the case where nothing is focused.
     */
    public static Window frontmost(Window fallback) {
        return frontmostOf(List.copyOf(Window.getWindows()),
                window -> window.isShowing() && window.isFocused(), fallback);
    }

    /** The choosing itself, apart from the toolkit, so it can be tested. */
    static <W> W frontmostOf(List<W> windows, Predicate<W> focused, W fallback) {
        if (windows != null) {
            for (W window : windows) {
                if (focused.test(window)) {
                    return window;
                }
            }
        }
        return fallback;
    }

    /**
     * Runs something that opens a native window, with nothing of ours pinned above it.
     *
     * <p>On Linux a dialog of ours is kept in front by {@code alwaysOnTop}, for the reason
     * the top of this class explains. A window with that flag is put in a higher stacking
     * layer than ordinary windows, and the operating system's own file chooser is an
     * ordinary window - so it opens behind the dialog that asked for it even though it is
     * that dialog's child. Being a child is not enough to beat the layer.
     *
     * <p>So the flag comes off for as long as the chooser is up, and goes back on
     * afterwards, on exactly the windows it was on. Nothing else about the dialog changes,
     * and on a platform where the flag is never set this does nothing at all.
     */
    public static <T> T whileChoosing(Supplier<T> choose) {
        List<Stage> lowered = new ArrayList<>();
        for (Window window : List.copyOf(Window.getWindows())) {
            if (window instanceof Stage stage && stage.isAlwaysOnTop()) {
                stage.setAlwaysOnTop(false);
                lowered.add(stage);
            }
        }
        try {
            return choose.get();
        } finally {
            // Put back on whatever is still open: a dialog can be closed while the chooser
            // is up, and setting a flag on a window that has gone is not worth risking.
            lowered.stream().filter(Stage::isShowing).forEach(stage -> stage.setAlwaysOnTop(true));
        }
    }
}
