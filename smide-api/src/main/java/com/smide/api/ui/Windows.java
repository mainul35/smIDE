package com.smide.api.ui;

import javafx.scene.control.Dialog;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.Locale;

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
}
