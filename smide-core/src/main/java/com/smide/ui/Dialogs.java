package com.smide.ui;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.layout.Region;
import javafx.stage.Window;

/** Small fixes every dialog needs. */
public final class Dialogs {

    private Dialogs() {
    }

    private static final boolean LINUX =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux");

    /**
     * Ties a dialog to the window it belongs to, without costing that window its size.
     *
     * <p>A modal dialog is the obvious thing to want: while it is up, the window behind it should
     * not be typed into. What that costs on Linux is the window itself. Making a window modal
     * disables the one behind it, and disabling a window is done by pinning its size - the
     * smallest it may be and the largest it may be, both set to the size it has - and a window
     * that may not be resized is one a window manager will not keep maximised. It is restored,
     * and a maximised IDE drops to whatever size it had before it was maximised, which is what
     * kept being reported here.
     *
     * <p>So on Linux the dialog is owned but not modal: it opens over its window, stays in front
     * of it, and closes with it. What is lost is that the window behind can still be clicked; what
     * is kept is the window. Everywhere else the dialog is modal as before.
     */
    public static void belongsTo(javafx.stage.Stage dialog, Window owner) {
        if (owner != null && owner.isShowing()) {
            dialog.initOwner(owner);
        }
        if (LINUX) {
            dialog.initModality(javafx.stage.Modality.NONE);
            dialog.setAlwaysOnTop(true);
        } else {
            dialog.initModality(javafx.stage.Modality.WINDOW_MODAL);
        }
    }

    /**
     * Makes sure a dialog's buttons are inside its window, whatever its text does.
     *
     * <p>A dialog sizes its window from what it holds before that has been laid out, and text
     * that wraps takes more room than was reckoned with - a long folder path, a longer
     * translation, a font a little wider than the one it was measured against. The buttons then
     * sit below the bottom edge, where they cannot be pressed: the window asks a question and
     * offers no way to answer it.
     *
     * <p>So the text is left to wrap, then held at the height it has - otherwise the dialog
     * stretches it to fill whatever room it is given and the window can never catch up - and the
     * window is grown by whatever is still below its bottom edge. Watched for a moment rather
     * than checked once, because the IDE scales each window it opens to its zoom just after it
     * appears, and only then are the buttons where they will be.
     */
    public static void fitButtonsIn(Dialog<?> dialog, Region text) {
        dialog.setResizable(true);
        Scene scene = dialog.getDialogPane().getScene();
        if (scene != null) {
            /* Again whenever the window is scaled to the IDE's zoom, which replaces the scene's
               root just after the dialog appears: only then are the buttons where they will be. */
            scene.rootProperty().addListener((o, was, now) -> rounds(dialog, text, 4));
        }
        dialog.setOnShown(e -> rounds(dialog, text, 4));
        rounds(dialog, text, 4);
    }

    /** A round per frame, since a window that has just been made taller is only that tall on the next. */
    private static void rounds(Dialog<?> dialog, Region text, int left) {
        if (left <= 0) {
            return;
        }
        Platform.runLater(() -> {
            fit(dialog, text);
            rounds(dialog, text, left - 1);
        });
    }

    private static void fit(Dialog<?> dialog, Region text) {
        Scene scene = dialog.getDialogPane().getScene();
        if (scene == null || scene.getWindow() == null) {
            return;
        }
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        hold(text);
        Node buttons = dialog.getDialogPane().lookup(".button-bar");
        if (buttons == null) {
            return;
        }
        Bounds inScene = buttons.localToScene(buttons.getBoundsInLocal());
        double past = inScene.getMaxY() - scene.getHeight();
        if (past > 1) {
            Window window = scene.getWindow();
            window.setHeight(window.getHeight() + past + 8);
        }
    }

    /** Keeps the text at the height it wrapped to, so the dialog cannot stretch it any further. */
    private static void hold(Region text) {
        if (text == null || text.getHeight() <= 0 || text.getMaxHeight() == text.getHeight()) {
            return;
        }
        double wrapped = text.getHeight();
        text.setMinHeight(wrapped);
        text.setPrefHeight(wrapped);
        text.setMaxHeight(wrapped);
    }
}
